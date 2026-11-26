package finances.sync;

import finances.accounts.AccountService;
import finances.accounts.AccountSummary;
import finances.accounts.AsOfBasis;
import finances.accounts.BalanceEntry;
import finances.accounts.BankBalanceInfo;
import finances.accounts.Kind;
import finances.accounts.Scope;
import finances.money.BuildUp;
import finances.money.Freshness;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.springframework.stereotype.Service;

/**
 * The two headline numbers (issue #19): gathers the facts and hands them to {@link BuildUp}, where the definitions are. It
 * decides only <i>which</i> accounts count (active private current accounts), <i>whether</i> a source is configured, and
 * which parts are stale (from {@link FreshnessService} for an account and {@link AfterTaxService} for the after-tax value:
 * no third rule). Archived accounts, savings, investment and debt accounts, and every business account are in neither
 * number. It sits in {@code sync}, like {@link FreshnessService}, because it reads both. Read only: no request to the source or
 * the bank; nothing here logs.
 */
@Service
public class HeadlineService {

    /** The label of the after-tax part inside {@link BuildUp}; the page shows its own text. */
    static final String AFTER_TAX = "after-tax";

    private final AccountService accounts;
    private final FreshnessService freshness;
    private final AfterTaxService afterTax;

    HeadlineService(AccountService accounts, FreshnessService freshness, AfterTaxService afterTax) {
        this.accounts = accounts;
        this.freshness = freshness;
        this.afterTax = afterTax;
    }

    /** Private free to spend: the number and the accounts that were counted, in the order of its lines. */
    public record PrivateNumber(BuildUp.Result result, List<AccountSummary> counted) {
    }

    /**
     * Business available: the number, the current after-tax value behind it (null when none), and, clearly apart, the active
     * business current accounts, which are never counted (the gross money the after-tax amount replaces).
     */
    public record BusinessNumber(BuildUp.Result result, AfterTaxValue afterTax, List<AccountSummary> notCounted) {
        public boolean hasAfterTax() {
            return afterTax != null;
        }
    }

    /** Both numbers side by side, and why each account that is listed is stale (a fresh account is not in the map). */
    public record Headlines(PrivateNumber privateNumber, BusinessNumber business, Map<Long, Freshness.Stale> stale) {
    }

    public Headlines headlines() {
        List<AccountSummary> active = accounts.list(false);
        return headlines(active, freshness.stale(active));
    }

    /** The same for what the caller has already read: {@code active} must be {@code accounts.list(false)}. */
    public Headlines headlines(List<AccountSummary> active, Map<Long, Freshness.Stale> stale) {
        return new Headlines(privateNumber(active, stale), businessNumber(active), stale);
    }

    private PrivateNumber privateNumber(List<AccountSummary> active, Map<Long, Freshness.Stale> stale) {
        List<AccountSummary> counted = active.stream().filter(s -> isOf(s, Scope.PRIVATE)).toList();
        Map<Long, BankBalanceInfo> banks = accounts.latestBankBalances();
        List<BuildUp.Line> lines = new ArrayList<>();
        for (AccountSummary summary : counted) {
            Optional<BalanceEntry> latest = summary.latest();
            lines.add(new BuildUp.Line(summary.account().name(), latest.map(BalanceEntry::amount).orElse(null),
                    latest.map(entry -> asOf(entry, banks.get(summary.account().id()))).orElse(null),
                    stale.get(summary.account().id())));
        }
        return new PrivateNumber(BuildUp.privateFreeToSpend(lines, openReservations(Scope.PRIVATE)), counted);
    }

    private BusinessNumber businessNumber(List<AccountSummary> active) {
        List<AccountSummary> notCounted = active.stream().filter(s -> isOf(s, Scope.BUSINESS)).toList();
        if (!afterTax.configured()) {
            // Whatever an earlier source left behind is not this installation's number any more.
            return new BusinessNumber(BuildUp.noSource(), null, notCounted);
        }
        AfterTaxValue current = afterTax.current().orElse(null);
        BuildUp.Line line = current == null ? null
                : new BuildUp.Line(AFTER_TAX, current.available(), current.asOf(), afterTax.standing(current).orElse(null));
        return new BusinessNumber(BuildUp.businessAvailable(line, openReservations(Scope.BUSINESS)), current, notCounted);
    }

    /** Only the current accounts of a scope count: the business ones are listed, not counted. */
    private static boolean isOf(AccountSummary summary, Scope scope) {
        return summary.account().scope() == scope && summary.account().kind() == Kind.CURRENT;
    }

    /**
     * The open reservations of a scope. There are none until reservations exist (issue #20), which plugs in here and nowhere
     * else: {@link BuildUp} already takes them.
     */
    private static List<BuildUp.Reservation> openReservations(Scope scope) {
        return List.of();
    }

    /** The moment a balance is correct as of, for finding the oldest part: the bank's own time, else the start of its day. */
    private Instant asOf(BalanceEntry entry, BankBalanceInfo bank) {
        if (bank != null && bank.basis() == AsOfBasis.BANK_TIME && bank.asOf() != null && bank.amount().equals(entry.amount())
                && bank.date().equals(entry.date())) {
            return bank.asOf();
        }
        return entry.date().atStartOfDay(accounts.zone()).toInstant();
    }
}
