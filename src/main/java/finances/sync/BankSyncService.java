package finances.sync;

import finances.accounts.Account;
import finances.accounts.AccountService;
import finances.accounts.BankDeliveryReport;
import finances.accounts.DeliveredTransaction;
import finances.accounts.DeliveryReport;
import finances.accounts.TransactionService;
import finances.outbound.BankApiException;
import finances.outbound.EnableBankingClient;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.Predicate;
import java.util.function.Supplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.JsonNode;

/**
 * Fetches balances and transactions of the linked accounts and hands them to the stores
 * (docs/decisions/10-bank-sync.md). The rules:
 * <ul>
 * <li>Everything is fetched first, with no database transaction open (SQLite has one writer); only then are
 * the balance, the transactions and the run's record stored in one short transaction. A failed fetch changes
 * no stored balance or transaction and never clears one.</li>
 * <li>One attempt per account per run, no retry loop. A rate limit is a failed run with that reason; the next
 * daily run tries again. One account failing does not stop the others.</li>
 * <li>Two runs never overlap.</li>
 * <li>The first sync of an account asks for as far back as the bank allows; later ones start at the last
 * successful sync minus an overlap of some days.</li>
 * <li>The daily run sends no PSU headers (a background fetch, about four a day per account at many banks);
 * a run the user started sends the user's headers when the bank's required ones can all be sent, and is a
 * background fetch otherwise.</li>
 * </ul>
 * Only counts and reason codes are ever logged.
 */
@Service
public class BankSyncService {

    private static final Logger LOG = LoggerFactory.getLogger(BankSyncService.class);
    /** A run of the link flow waits this long for a run that is going on: the full history is only there for about an hour. */
    static final Duration LINK_WAIT = Duration.ofMinutes(2);
    private static final int MAX_PAGES = 200;
    /** Headers that mark a fetch as the user's own whenever they are available, besides those the bank lists. */
    private static final List<String> USER_HEADERS = List.of("Psu-Ip-Address", "Psu-User-Agent");

    private final EnableBankingClient client;
    private final BankStore store;
    private final SessionFiles sessions;
    private final AccountService accounts;
    private final TransactionService transactions;
    private final Clock clock;
    private final TransactionTemplate inTransaction;
    private final int overlapDays;
    private final ReentrantLock running = new ReentrantLock();

    public BankSyncService(EnableBankingClient client, BankStore store, SessionFiles sessions, AccountService accounts,
            TransactionService transactions, Clock clock, PlatformTransactionManager transactionManager,
            @Value("${finances.sync.overlap-days:7}") int overlapDays) {
        this.client = client;
        this.store = store;
        this.sessions = sessions;
        this.accounts = accounts;
        this.transactions = transactions;
        this.clock = clock;
        this.inTransaction = new TransactionTemplate(transactionManager);
        this.overlapDays = Math.max(1, overlapDays);
    }

    // ----- entry points

    /** The daily run or "Sync now": every linked account of every consent that has not ended. Busy at once when a run is going on. */
    public SyncSummary syncAll(SyncTrigger trigger, Map<String, String> userHeaders) {
        if (!client.configured()) {
            return SyncSummary.notConfigured();
        }
        if (!running.tryLock()) {
            return SyncSummary.busy();
        }
        try {
            return execute(link -> true, trigger, userHeaders);
        } finally {
            running.unlock();
        }
    }

    /** The first sync of the link flow for the accounts just linked: waits a while for a run that is going on. */
    SyncSummary syncLinks(Set<Long> linkIds, SyncTrigger trigger, Map<String, String> userHeaders) {
        if (!client.configured()) {
            return SyncSummary.notConfigured();
        }
        try {
            if (!running.tryLock(LINK_WAIT.toMillis(), TimeUnit.MILLISECONDS)) {
                return SyncSummary.busy();
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return SyncSummary.busy();
        }
        try {
            return execute(link -> linkIds.contains(link.id()), trigger, userHeaders);
        } finally {
            running.unlock();
        }
    }

    /**
     * Runs something that must not overlap a sync (unlinking a bank changes what a run reads). Empty when a
     * run did not end in time.
     */
    <T> Optional<T> whileNotSyncing(Duration wait, Supplier<T> action) {
        try {
            if (!running.tryLock(wait.toMillis(), TimeUnit.MILLISECONDS)) {
                return Optional.empty();
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return Optional.empty();
        }
        try {
            return Optional.ofNullable(action.get());
        } finally {
            running.unlock();
        }
    }

    // ----- one run

    private SyncSummary execute(Predicate<BankAccountLink> which, SyncTrigger trigger, Map<String, String> userHeaders) {
        int ok = 0;
        int failed = 0;
        for (Consent consent : store.consents()) {
            if (consent.ended()) {
                continue; // its sync has stopped; the accounts keep their history and show "consent expired"
            }
            List<BankAccountLink> links = store.links(consent.id()).stream().filter(BankAccountLink::linked).filter(which).toList();
            if (links.isEmpty()) {
                continue;
            }
            Headers headers = headersFor(consent, trigger, userHeaders);
            ConsentStatus ended = endedStatus(consent);
            for (BankAccountLink link : links) {
                if (accounts.find(link.accountId()).map(Account::archived).orElse(true)) {
                    continue; // an archived account is not synced
                }
                boolean good;
                if (ended != null) {
                    record(link, Instant.now(clock), trigger, headers, "consent_expired");
                    good = false;
                } else {
                    Outcome outcome = syncOne(link, trigger, headers);
                    good = outcome.ok();
                    if (outcome.endedConsent() != null) {
                        ended = outcome.endedConsent();
                    }
                }
                if (good) {
                    ok++;
                } else {
                    failed++;
                }
            }
            if (ended != null) {
                store.setStatus(consent.id(), ended, Instant.now(clock));
            }
        }
        LOG.info("Bank sync finished: {} accounts synced, {} failed.", ok, failed);
        return new SyncSummary(SyncSummary.State.DONE, ok, failed);
    }

    /** Whether the consent has ended, found without a fetch (its date, its missing session file) or by asking for the session. */
    private ConsentStatus endedStatus(Consent consent) {
        Instant now = Instant.now(clock);
        Optional<String> sessionId = sessions.read(consent.id());
        if (!consent.validUntil().isAfter(now) || sessionId.isEmpty()) {
            return ConsentStatus.EXPIRED;
        }
        try {
            JsonNode session = client.session(sessionId.get());
            String status = session.path("status").isString() ? session.path("status").asString() : "AUTHORIZED";
            return switch (status) {
                case "EXPIRED" -> ConsentStatus.EXPIRED;
                case "REVOKED", "CLOSED", "CANCELLED", "INVALID" -> ConsentStatus.REVOKED;
                default -> null;
            };
        } catch (BankApiException e) {
            // "It is still possible for the session to expire after a valid answer", so this check is a help only:
            // an answer other than "ended" lets the fetch decide.
            return e.reason() == BankApiException.Reason.CONSENT_ENDED ? endedBy(e) : null;
        }
    }

    private record Outcome(boolean ok, ConsentStatus endedConsent) {
    }

    private Outcome syncOne(BankAccountLink link, SyncTrigger trigger, Headers headers) {
        Instant started = Instant.now(clock);
        ZoneId zone = clock.getZone();
        boolean first = link.lastSuccessAt() == null;
        LocalDate from = first ? null : LocalDate.ofInstant(link.lastSuccessAt(), zone).minusDays(overlapDays);
        List<DeliveredTransaction> batch;
        BankMapping.ChosenBalance balance;
        try {
            JsonNode balances = client.balances(link.uid(), headers.values());
            balance = BankMapping.balance(balances, started, zone);
            batch = fetchTransactions(link, from, first, headers, LocalDate.ofInstant(started, zone));
        } catch (BankApiException e) {
            record(link, started, trigger, headers, code(e));
            return new Outcome(false, e.reason() == BankApiException.Reason.CONSENT_ENDED ? endedBy(e) : null);
        }
        try {
            BankDeliveryReport report = inTransaction.execute(status -> {
                // A write first, so the single SQLite writer is taken up front (docs/gotchas.md).
                // The link may have changed while the fetch was in flight (a restore, an unlink, a renewal): what was
                // fetched belongs to the link as it was, so a changed one stores nothing.
                if (!store.markSuccess(link, started)) {
                    throw new IllegalStateException("The link changed during the fetch.");
                }
                if (balance != null) {
                    accounts.addBankBalance(link.accountId(), finances.money.Money.eur(balance.cents()), balance.date(),
                            balance.type(), balance.asOf(), balance.basis());
                }
                BankDeliveryReport delivered = transactions.deliverFromBank(link.accountId(), batch, from);
                DeliveryReport d = delivered.delivered();
                store.insertRun(link.accountId(), started, Instant.now(clock), trigger, headers.sent(), true, null,
                        d.added(), d.updated(), d.known(), d.refused(), delivered.pendingRemoved(), delivered.pendingKept());
                return delivered;
            });
            return new Outcome(report != null, null);
        } catch (RuntimeException e) {
            // Rolled back as a whole: no balance, no transaction and no success time changed. Only the kind of
            // exception is kept, never its message.
            LOG.warn("A bank sync could not be stored ({}).", e.getClass().getSimpleName());
            record(link, started, trigger, headers, "store_failed");
            return new Outcome(false, null);
        }
    }

    /** All pages of one fetch, collected in memory before anything is stored. Any unreadable item fails the whole fetch. */
    private List<DeliveredTransaction> fetchTransactions(BankAccountLink link, LocalDate from, boolean longest, Headers headers, LocalDate fetchDay) {
        List<DeliveredTransaction> batch = new ArrayList<>();
        String key = null;
        for (int page = 0; page < MAX_PAGES; page++) {
            JsonNode answer = client.transactions(link.uid(), from == null ? null : from.toString(), longest, key, headers.values());
            for (JsonNode node : answer.path("transactions")) {
                DeliveredTransaction delivered = BankMapping.transaction(node, fetchDay);
                if (delivered != null && BankMapping.undatedPending(node)) {
                    delivered = withKnownDay(link.accountId(), delivered);
                }
                if (delivered != null) {
                    batch.add(delivered);
                }
            }
            String next = answer.path("continuation_key").isString() ? answer.path("continuation_key").asString() : null;
            if (next == null || next.isBlank()) {
                return batch;
            }
            if (next.equals(key)) {
                break; // the same key again: the bank is not moving on
            }
            key = next;
        }
        // A fetch that never ended is not a complete one, and an incomplete one must not remove anything.
        throw new BankApiException(BankApiException.Reason.UNEXPECTED, null, 0);
    }

    /** Without a date its fingerprint would change every day: a pending item the bank gave no date keeps the day it was first seen. */
    private DeliveredTransaction withKnownDay(long accountId, DeliveredTransaction t) {
        return transactions.pendingBankDate(accountId, t.amountCents(), t.counterpartyName(), t.description())
                .map(day -> new DeliveredTransaction(day, t.valueDate(), t.amountCents(), t.currency(), t.counterpartyName(),
                        t.counterpartyIban(), t.description(), t.status(), t.reference(), t.raw()))
                .orElse(t);
    }

    // ----- records and reasons

    private void record(BankAccountLink link, Instant started, SyncTrigger trigger, Headers headers, String reason) {
        try {
            store.insertRun(link.accountId(), started, Instant.now(clock), trigger, headers.sent(), false, reason, 0, 0, 0, 0, 0, 0);
        } catch (RuntimeException e) {
            LOG.warn("A bank sync run could not be recorded ({}).", e.getClass().getSimpleName());
        }
    }

    /** A short, fixed code for the settings page; never a message from the bank. */
    private static String code(BankApiException e) {
        return switch (e.reason()) {
            case CONSENT_ENDED -> "consent_expired";
            default -> e.reason().name().toLowerCase(java.util.Locale.ROOT);
        };
    }

    private static ConsentStatus endedBy(BankApiException e) {
        return "EXPIRED_SESSION".equals(e.apiError()) ? ConsentStatus.EXPIRED : ConsentStatus.REVOKED;
    }

    // ----- PSU headers

    /** The headers of a fetch; none means a background fetch. */
    record Headers(Map<String, String> values) {
        boolean sent() {
            return !values.isEmpty();
        }
    }

    /**
     * The daily run sends none. A run the user started sends the headers the bank lists, all of them or (when one
     * cannot be given) none at all, because a bank answers 422 to some of them (docs/gotchas.md), and then the fetch
     * is a background one and counts against its daily limit. The user's own headers are never logged.
     */
    private static Headers headersFor(Consent consent, SyncTrigger trigger, Map<String, String> user) {
        if (!trigger.startedByUser() || user == null || user.isEmpty()) {
            return new Headers(Map.of());
        }
        Map<String, String> available = new TreeMap<>(String.CASE_INSENSITIVE_ORDER);
        available.putAll(user);
        Map<String, String> chosen = new LinkedHashMap<>();
        for (String name : consent.requiredPsuHeaders()) {
            String value = available.get(name);
            if (value == null || value.isBlank()) {
                return new Headers(Map.of());
            }
            chosen.put(name, value);
        }
        for (String name : USER_HEADERS) {
            String value = available.get(name);
            if (value != null && !value.isBlank()) {
                chosen.putIfAbsent(name, value);
            }
        }
        return new Headers(chosen);
    }
}
