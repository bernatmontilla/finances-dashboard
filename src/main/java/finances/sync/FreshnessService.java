package finances.sync;

import finances.accounts.Account;
import finances.accounts.AccountService;
import finances.accounts.AccountSummary;
import finances.accounts.BalanceEntry;
import finances.accounts.Source;
import finances.money.Freshness;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.springframework.stereotype.Service;

/**
 * Whether the figure of an account is stale and why (hard rule 9): the facts about each source gathered in one
 * place and handed to the pure rules of {@link Freshness}. It reads what the bank sync already stores (the
 * consent, the recorded runs of the account, the balance and its date) and keeps nothing of its own. Read only;
 * nothing here logs.
 *
 * <p>A bank account is judged by its sync, not by the date of its balance: stale when the consent has ended, when
 * the newest run failed, or when the last successful run is older than the limit. A manual or CSV account is
 * judged by the date of its newest balance against the limit of its kind.
 */
@Service
public class FreshnessService {

    /** A consent that ends within this many days is announced before it makes anything stale. */
    public static final int CONSENT_WARNING_DAYS = 14;

    private final AccountService accounts;
    private final BankStatus bank;
    private final SessionFiles sessions;
    private final Freshness.Limits limits;
    private final Clock clock;

    FreshnessService(AccountService accounts, BankStatus bank, SessionFiles sessions, Freshness.Limits limits, Clock clock) {
        this.accounts = accounts;
        this.bank = bank;
        this.sessions = sessions;
        this.limits = limits;
        this.clock = clock;
    }

    /** An authorised consent that ends soon and still feeds an account that is in use. */
    public record ExpiringConsent(Consent consent, LocalDate endsOn, long daysLeft) {
    }

    /** The stale reason of every account given that is stale, by account id; a fresh account is not in the map. */
    public Map<Long, Freshness.Stale> stale(List<AccountSummary> summaries) {
        Map<Long, Freshness.Stale> stale = new HashMap<>();
        for (AccountSummary summary : summaries) {
            standing(summary.account(), summary.latest().orElse(null)).ifPresent(reason -> stale.put(summary.account().id(), reason));
        }
        return stale;
    }

    /** The stale reason of one account given its newest balance (null when it has none), or empty when it is fresh. */
    public Optional<Freshness.Stale> standing(Account account, BalanceEntry latest) {
        Instant now = Instant.now(clock);
        if (account.source() == Source.BANK) {
            Optional<BankAccountLink> link = bank.linkOfAccount(account.id());
            Optional<Consent> consent = link.flatMap(l -> bank.consent(l.consentId()));
            if (link.isPresent() && consent.isPresent()) {
                return bankStanding(account, link.get(), consent.get(), latest, now);
            }
        }
        if (latest == null) {
            return Optional.empty(); // "no balance yet" is said by the page; it is not a stale figure
        }
        return Freshness.byHand(latest.date(), LocalDate.now(clock), daysFor(account));
    }

    private Optional<Freshness.Stale> bankStanding(Account account, BankAccountLink link, Consent consent, BalanceEntry latest,
            Instant now) {
        boolean ended = ended(consent, now);
        Optional<SyncRunRow> lastRun = bank.lastRun(account.id());
        String failure = lastRun.isPresent() && !lastRun.get().ok()
                ? (lastRun.get().reason() == null ? "unexpected" : lastRun.get().reason())
                : null;
        // The recorded runs say when the last sync ended well; the link's own stamp and the moment the balance was
        // stored are only fallbacks for a database that has no run for it.
        Instant lastSuccess = bank.lastSuccessfulSync(account.id())
                .or(() -> Optional.ofNullable(link.lastSuccessAt()))
                .orElse(latest == null ? null : latest.enteredAt());
        return Freshness.bank(now, lastSuccess, failure, ended, limits.bankSync());
    }

    /** Ended, past its date, or without its session file (a restore on another machine): found without a call to the bank. */
    private boolean ended(Consent consent, Instant now) {
        return consent.ended() || !consent.validUntil().isAfter(now) || !sessions.exists(consent.id());
    }

    private int daysFor(Account account) {
        return switch (account.kind()) {
            case CURRENT -> limits.currentDays();
            case SAVINGS -> limits.savingsDays();
            case INVESTMENT -> limits.investmentDays();
            case DEBT -> limits.debtDays();
        };
    }

    /** Authorised consents that end within {@value #CONSENT_WARNING_DAYS} days and still feed an account that is not archived. */
    public List<ExpiringConsent> expiringConsents() {
        Instant now = Instant.now(clock);
        LocalDate today = LocalDate.now(clock);
        List<ExpiringConsent> expiring = new ArrayList<>();
        for (Consent consent : bank.consents()) {
            if (ended(consent, now) || consent.validUntil().isAfter(now.plus(Duration.ofDays(CONSENT_WARNING_DAYS)))) {
                continue;
            }
            boolean inUse = bank.links(consent.id()).stream()
                    .filter(BankAccountLink::linked)
                    .anyMatch(link -> accounts.find(link.accountId()).map(a -> !a.archived()).orElse(false));
            if (inUse) {
                LocalDate endsOn = consent.validUntil().atZone(clock.getZone()).toLocalDate();
                expiring.add(new ExpiringConsent(consent, endsOn, Math.max(0, ChronoUnit.DAYS.between(today, endsOn))));
            }
        }
        return expiring;
    }
}
