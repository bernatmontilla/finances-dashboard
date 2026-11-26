package finances.sync;

import finances.accounts.Account;
import finances.accounts.AccountService;
import finances.accounts.Kind;
import finances.accounts.Scope;
import finances.accounts.Source;
import finances.outbound.BankApiException;
import finances.outbound.EnableBankingClient;
import java.net.URI;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.JsonNode;

/**
 * Linking a bank: choose it from the list the API offers, go through the bank's own authorisation, come
 * back, and let the owner confirm each account (create a new one or attach an existing one). Nothing becomes
 * an account of the app without that confirmation. The first sync runs inside the flow, as soon as an account is
 * linked, because the full history is only there for about an hour after the authorisation
 * (docs/decisions/6-enable-banking.md). Account information only: no payment scope is asked for and no payment
 * call exists. Only counts are logged.
 */
@Service
public class BankLinkService {

    private static final Logger LOG = LoggerFactory.getLogger(BankLinkService.class);
    private static final Duration ATTEMPT_LIFETIME = Duration.ofMinutes(30);
    private static final Duration LONGEST_CONSENT = Duration.ofDays(180);
    private static final Duration UNLINK_WAIT = Duration.ofSeconds(30);
    private static final Pattern HEADER_NAME = Pattern.compile("[A-Za-z][A-Za-z0-9-]{0,40}");
    private static final SecureRandom RANDOM = new SecureRandom();

    /** A bank from the API's list. A bank is identified by name and country: it has no id. */
    public record Bank(String name, String country, long maximumConsentValiditySeconds, List<String> requiredPsuHeaders) {
    }

    /** An authorisation that was started and has not come back yet. Kept in memory only: nothing is stored until it does. */
    private record Attempt(String country, String bankName, List<String> requiredHeaders, Long renewConsentId, Instant validUntil,
            Instant startedAt) {
    }

    private final EnableBankingClient client;
    private final BankStore store;
    private final SessionFiles sessions;
    private final AccountService accounts;
    private final BankSyncService sync;
    private final Clock clock;
    private final TransactionTemplate inTransaction;
    private final String redirectUrl;
    private final Map<String, Attempt> attempts = new ConcurrentHashMap<>();

    public BankLinkService(EnableBankingClient client, BankStore store, SessionFiles sessions, AccountService accounts,
            BankSyncService sync, Clock clock, PlatformTransactionManager transactionManager,
            @Value("${finances.sync.redirect-url:}") String redirectUrl) {
        this.client = client;
        this.store = store;
        this.sessions = sessions;
        this.accounts = accounts;
        this.sync = sync;
        this.clock = clock;
        this.inTransaction = new TransactionTemplate(transactionManager);
        this.redirectUrl = redirectUrl == null ? "" : redirectUrl.strip();
    }

    // ----- the bank list

    /** The countries that have a bank offering account information to a private person, sorted. */
    public List<String> countries() {
        requireConfigured();
        Set<String> countries = new TreeSet<>();
        for (Bank bank : parseBanks(call(() -> client.banks(null)))) {
            countries.add(bank.country());
        }
        return List.copyOf(countries);
    }

    /** The banks of one country, by name. Their logos are not used: nothing is loaded from another host. */
    public List<Bank> banks(String country) {
        requireConfigured();
        String code = country == null ? "" : country.strip().toUpperCase(Locale.ROOT);
        if (!code.matches("[A-Z]{2}")) {
            throw new LinkException(LinkException.Reason.BANK_UNKNOWN);
        }
        return parseBanks(call(() -> client.banks(code))).stream()
                .filter(bank -> bank.country().equals(code))
                .sorted(Comparator.comparing(bank -> bank.name().toLowerCase(Locale.ROOT)))
                .toList();
    }

    private static List<Bank> parseBanks(JsonNode answer) {
        List<Bank> banks = new ArrayList<>();
        for (JsonNode node : answer.path("aspsps")) {
            String name = node.path("name").isString() ? node.path("name").asString().strip() : "";
            String country = node.path("country").isString() ? node.path("country").asString().strip().toUpperCase(Locale.ROOT) : "";
            if (name.isEmpty() || !country.matches("[A-Z]{2}")) {
                continue;
            }
            long validity = node.path("maximum_consent_validity").isNumber() ? node.path("maximum_consent_validity").asLong() : 90L * 24 * 3600;
            List<String> headers = new ArrayList<>();
            for (JsonNode header : node.path("required_psu_headers")) {
                if (header.isString() && HEADER_NAME.matcher(header.asString()).matches()) {
                    headers.add(header.asString());
                }
            }
            banks.add(new Bank(name, country, validity, List.copyOf(headers)));
        }
        return banks;
    }

    // ----- starting

    /**
     * Starts an authorisation (a renewal when {@code renewConsentId} is given) and returns the address of the
     * bank's own page to send the browser to. The state is random, kept here and good for one callback.
     */
    public String start(String country, String bankName, Long renewConsentId) {
        requireConfigured();
        Bank bank = banks(country).stream().filter(b -> b.name().equals(bankName)).findFirst()
                .orElseThrow(() -> new LinkException(LinkException.Reason.BANK_UNKNOWN));
        Instant now = Instant.now(clock);
        // The longest the bank allows, never longer than 180 days, a little short of the limit so that a clock
        // slightly ahead of the bank's is not refused.
        Duration validity = Duration.ofSeconds(Math.max(3600, bank.maximumConsentValiditySeconds() - 60));
        Instant validUntil = now.plus(validity.compareTo(LONGEST_CONSENT) > 0 ? LONGEST_CONSENT : validity);
        String state = HexFormat.of().formatHex(randomBytes());
        JsonNode answer = call(() -> client.startAuthorisation(bank.name(), bank.country(), redirectUrl, state, validUntil));
        String url = answer.path("url").isString() ? answer.path("url").asString().strip() : "";
        if (!authorisationAddress(url)) {
            throw new LinkException(new BankApiException(BankApiException.Reason.UNEXPECTED, null, 0));
        }
        attempts.values().removeIf(a -> a.startedAt().plus(ATTEMPT_LIFETIME).isBefore(now));
        attempts.put(state, new Attempt(bank.country(), bank.name(), bank.requiredPsuHeaders(), renewConsentId, validUntil, now));
        return url;
    }

    private static byte[] randomBytes() {
        byte[] bytes = new byte[16];
        RANDOM.nextBytes(bytes);
        return bytes;
    }

    /** The browser is sent to the bank's page: https, and a host. (It is the browser that goes there, not this server.) */
    private static boolean authorisationAddress(String url) {
        try {
            URI uri = URI.create(url);
            return "https".equals(uri.getScheme()) && uri.getHost() != null && uri.getUserInfo() == null;
        } catch (RuntimeException e) {
            return false;
        }
    }

    // ----- coming back

    /**
     * The bank sent the browser back. An unknown, used or old state, an error or a missing code stores nothing.
     * Otherwise the code becomes a session, and what the session says about its accounts is stored at once (it
     * is shown only once). Accounts that were linked before (found by {@code identification_hash}, else the IBAN)
     * move to the new consent and sync at once: that is a renewal. The others wait for the owner's confirmation.
     */
    public LinkOutcome complete(String state, String code, String error, Map<String, String> userHeaders) {
        Attempt attempt = state == null ? null : attempts.remove(state);
        Instant now = Instant.now(clock);
        if (attempt == null || attempt.startedAt().plus(ATTEMPT_LIFETIME).isBefore(now)) {
            throw new LinkException(LinkException.Reason.UNKNOWN_STATE);
        }
        if ((error != null && !error.isBlank()) || code == null || code.isBlank()) {
            throw new LinkException(LinkException.Reason.DENIED);
        }
        requireConfigured();
        JsonNode session = call(() -> client.createSession(code.strip()));
        String sessionId;
        Instant validUntil;
        List<BankMapping.Offered> offered;
        try {
            sessionId = BankMapping.sessionId(session);
            validUntil = BankMapping.validUntil(session);
            offered = BankMapping.accounts(session);
        } catch (BankApiException e) {
            throw new LinkException(e);
        }
        if (offered.isEmpty()) {
            // Restricted mode strips every account that was not linked in the control panel: that is an empty list,
            // not an error. Nothing to keep, so the session is closed again.
            closeQuietly(sessionId);
            return LinkOutcome.emptyConsent();
        }
        Set<Long> reattached = new HashSet<>();
        Set<Long> replaced = new HashSet<>();
        long[] consentId = new long[1];
        int[] counts = new int[2]; // offered, unsupported
        try {
            inTransaction.executeWithoutResult(status -> {
                String tag = SessionFiles.newTag();
                consentId[0] = store.insertConsent(attempt.bankName(), attempt.country(), attempt.requiredHeaders(), now, validUntil, tag);
                sessions.write(consentId[0], sessionId, tag);
                for (BankMapping.Offered account : offered) {
                    Optional<BankAccountLink> known = store.linkedWithKey(account.hash(), account.iban());
                    if (known.isPresent() && "EUR".equals(account.currency())) {
                        store.rebind(known.get().id(), consentId[0], account.uid(), account.iban(), account.holder(), account.details(),
                                account.cashAccountType());
                        reattached.add(known.get().id());
                        replaced.add(known.get().consentId());
                        continue;
                    }
                    store.insertOffered(consentId[0], account.uid(), account.hash(), account.iban(), account.holder(), account.details(),
                            account.currency(), account.cashAccountType());
                    counts["EUR".equals(account.currency()) ? 0 : 1]++;
                }
            });
        } catch (RuntimeException e) {
            if (consentId[0] != 0) {
                sessions.delete(consentId[0]);
            }
            closeQuietly(sessionId);
            LOG.warn("A bank link could not be stored ({}).", e.getClass().getSimpleName());
            throw new LinkException(LinkException.Reason.FAILED);
        }
        replaced.remove(consentId[0]);
        replaced.forEach(this::dropWhenEmpty);
        SyncSummary firstSync = reattached.isEmpty() ? null : sync.syncLinks(reattached, SyncTrigger.LINK, userHeaders);
        return new LinkOutcome(consentId[0], false, counts[0], counts[1], reattached.size(), firstSync);
    }

    /** An old consent whose accounts all moved to a new one is closed at the bank and forgotten. */
    private void dropWhenEmpty(long consentId) {
        if (store.consent(consentId).isEmpty() || store.linkedCount(consentId) > 0) {
            return;
        }
        store.consent(consentId).flatMap(sessions::read).ifPresent(this::closeQuietly);
        inTransaction.executeWithoutResult(status -> store.deleteConsent(consentId));
        sessions.delete(consentId);
    }

    // ----- confirming

    /** Creates a new account of the app for an account the consent covers, after the owner chose its name, scope and kind. */
    public SyncSummary create(long consentId, long linkId, String name, Scope scope, Kind kind, Map<String, String> userHeaders) {
        BankAccountLink link = offeredLink(consentId, linkId);
        inTransaction.executeWithoutResult(status -> {
            String iban = AccountService.isValidIban(link.iban()) ? link.iban() : null;
            Account account = accounts.addFromBank(name, scope, kind, iban);
            if (!store.confirm(link.id(), account.id(), Instant.now(clock))) {
                // A second request confirmed it first: roll this one back, creating no orphan account.
                throw new LinkException(LinkException.Reason.ALREADY_LINKED);
            }
        });
        return sync.syncLinks(Set.of(link.id()), SyncTrigger.LINK, userHeaders);
    }

    /** Attaches an account the consent covers to an existing account without a bank link; its history stays. */
    public SyncSummary attach(long consentId, long linkId, long accountId, Map<String, String> userHeaders) {
        BankAccountLink link = offeredLink(consentId, linkId);
        inTransaction.executeWithoutResult(status -> {
            Account account = accounts.attachToBank(accountId);
            if (!store.confirm(link.id(), account.id(), Instant.now(clock))) {
                // A second request confirmed it first: roll this one back, creating no orphan account.
                throw new LinkException(LinkException.Reason.ALREADY_LINKED);
            }
        });
        return sync.syncLinks(Set.of(link.id()), SyncTrigger.LINK, userHeaders);
    }

    private BankAccountLink offeredLink(long consentId, long linkId) {
        Consent consent = store.consent(consentId).orElseThrow(() -> new LinkException(LinkException.Reason.NOT_FOUND));
        BankAccountLink link = store.link(linkId).filter(l -> l.consentId() == consentId)
                .orElseThrow(() -> new LinkException(LinkException.Reason.NOT_FOUND));
        if (consent.ended()) {
            throw new LinkException(LinkException.Reason.CONSENT_ENDED);
        }
        if (link.linked()) {
            throw new LinkException(LinkException.Reason.ALREADY_LINKED);
        }
        if (!link.supported()) {
            throw new LinkException(LinkException.Reason.UNSUPPORTED_CURRENCY);
        }
        return link;
    }

    // ----- unlinking

    /**
     * Removes the consent and its session (at the bank too, as far as that works), keeps the accounts and all their
     * history, and turns them into manual accounts.
     */
    public void unlink(long consentId) {
        Consent consent = store.consent(consentId).orElseThrow(() -> new LinkException(LinkException.Reason.NOT_FOUND));
        Optional<Boolean> done = sync.whileNotSyncing(UNLINK_WAIT, () -> {
            sessions.read(consent).ifPresent(this::closeQuietly);
            inTransaction.executeWithoutResult(status -> {
                for (BankAccountLink link : store.links(consent.id())) {
                    if (link.linked()) {
                        accounts.detachFromBank(link.accountId());
                    }
                }
                store.deleteConsent(consent.id());
            });
            sessions.delete(consent.id());
            return true;
        });
        if (done.isEmpty()) {
            throw new LinkException(LinkException.Reason.SYNC_BUSY);
        }
    }

    // ----- helpers

    private void requireConfigured() {
        if (!client.configured()) {
            throw new LinkException(LinkException.Reason.NOT_CONFIGURED);
        }
        if (redirectUrl.isEmpty()) {
            throw new LinkException(LinkException.Reason.NO_REDIRECT_URL);
        }
    }

    private <T> T call(java.util.function.Supplier<T> request) {
        try {
            return request.get();
        } catch (BankApiException e) {
            throw new LinkException(e);
        }
    }

    /** Closes a session at the bank; failing to is no reason to fail what is going on. */
    private void closeQuietly(String sessionId) {
        try {
            client.closeSession(sessionId);
        } catch (BankApiException ignored) {
            // an ended consent answers with an error: the local side is cleaned up all the same
        }
    }

    /** True when the owner may attach this account: no bank link yet and not archived. Used by the pages to offer a choice. */
    public boolean attachable(Account account) {
        return !account.archived() && account.source() != Source.BANK;
    }
}
