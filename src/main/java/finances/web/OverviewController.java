package finances.web;

import finances.accounts.AccountService;
import finances.accounts.AccountSummary;
import finances.accounts.BankBalanceInfo;
import finances.accounts.FixedCostService;
import finances.accounts.ReservationService;
import finances.accounts.Scope;
import finances.money.Freshness;
import finances.sync.BankStatus;
import finances.sync.FreshnessService;
import finances.sync.HeadlineService;
import java.util.List;
import java.util.Map;
import org.springframework.boot.info.BuildProperties;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.server.ResponseStatusException;

/**
 * The overview and the build-up pages of its two headline numbers (issue #19). Only reads: no GET here asks the bank or the
 * after-tax source for anything. The numbers are {@code finances.money.BuildUp}, fed by {@link HeadlineService}.
 */
@Controller
class OverviewController {

    /** How many of the next reservations that come due the overview lists per scope. */
    private static final int NEXT_DUE = 3;

    private final BuildProperties build;
    private final AccountService accounts;
    private final BankStatus bank;
    private final FreshnessService freshness;
    private final HeadlineService headlines;
    private final ReservationService reservations;
    private final FixedCostService fixedCosts;

    OverviewController(BuildProperties build, AccountService accounts, BankStatus bank, FreshnessService freshness,
            HeadlineService headlines, ReservationService reservations, FixedCostService fixedCosts) {
        this.build = build;
        this.accounts = accounts;
        this.bank = bank;
        this.freshness = freshness;
        this.headlines = headlines;
        this.reservations = reservations;
        this.fixedCosts = fixedCosts;
    }

    /** An account whose figure may be out of date, as the line at the top links to it. */
    record AttentionRow(long id, String name) {
    }

    /** A consent that ends soon, announced with its Renew action before it makes anything stale. */
    record ExpiringRow(long consentId, String bankName, String endsOn, long daysLeft) {
    }

    @GetMapping("/")
    String overview(@RequestParam(required = false) String sync, @RequestParam(defaultValue = "0") int ok,
            @RequestParam(defaultValue = "0") int failed, Model model) {
        // "Sync now" is offered when a bank is linked and the application is set up.
        model.addAttribute("canSync", bank.applicationConfigured() && !bank.consents().isEmpty());
        String key = sync == null ? null : switch (sync) {
            case "done" -> "bank.sync.done";
            case "failed" -> "bank.sync.failed";
            case "busy" -> "bank.sync.busy";
            case "notConfigured" -> "bank.sync.notConfigured";
            default -> null;
        };
        model.addAttribute("syncKey", key);
        model.addAttribute("syncOk", Math.max(0, ok));
        model.addAttribute("syncFailed", Math.max(0, failed));
        model.addAttribute("version", build.getVersion());
        // Only accounts in use count: an archived one is not listed here and is not synced.
        List<AccountSummary> active = accounts.list(false);
        Map<Long, Freshness.Stale> stale = freshness.stale(active);
        Map<Long, BankBalanceInfo> banks = accounts.latestBankBalances();
        model.addAttribute("headlines", HeadlineViews.headlines(headlines.headlines(active, stale), accounts.today(), accounts.zone(), banks));
        model.addAttribute("reservationGroups", reservationGroups());
        // A fixed cost may rest on an account archived since: its figures keep the stale mark (hard rule 9), though the line at the top
        // below counts accounts in use only.
        model.addAttribute("fixedCostsComing", fixedCostsComing(FixedCostViews.staleOfAll(accounts, freshness)));
        model.addAttribute("fixedCostFlags", fixedCosts.flags().size());
        model.addAttribute("groups", AccountViews.groups(active, accounts.today(), Map.of(), accounts.zone(), banks, stale));
        model.addAttribute("attention", active.stream().filter(s -> stale.containsKey(s.account().id()))
                .map(s -> new AttentionRow(s.account().id(), s.account().name())).toList());
        model.addAttribute("expiring", freshness.expiringConsents().stream()
                .map(e -> new ExpiringRow(e.consent().id(), e.consent().bankName(), e.endsOn().toString(), e.daysLeft())).toList());
        return "overview";
    }

    /** Per scope with an open reservation: the total reserved and the next ones that come due (issue #20). */
    private List<ReservationViews.Group> reservationGroups() {
        boolean source = headlines.businessSourceConfigured();
        List<ReservationViews.Group> groups = new java.util.ArrayList<>();
        for (Scope scope : Scope.values()) {
            var summary = reservations.summary(scope, NEXT_DUE);
            if (summary.hasOpen()) {
                groups.add(ReservationViews.overview(scope, summary, source));
            }
        }
        return groups;
    }

    /**
     * Per scope with a fixed cost still to come this month: the total and each one with its expected date (issue #21), and how old the
     * transactions are that it rests on (issue #63).
     */
    private List<FixedCostViews.Coming> fixedCostsComing(Map<Long, Freshness.Stale> stale) {
        List<FixedCostViews.Coming> groups = new java.util.ArrayList<>();
        for (Scope scope : Scope.values()) {
            var summary = fixedCosts.summary(scope);
            FixedCostViews.Coming coming = FixedCostViews.coming(scope, summary, id -> null,
                    FixedCostViews.basis(summary, accounts.zone(), accounts.today(), stale));
            if (coming != null) {
                groups.add(coming);
            }
        }
        return groups;
    }

    /** The build-up of one number: every line that goes into it, with its own amount and age. */
    @GetMapping("/build-up/{number}")
    String buildUp(@PathVariable String number, Model model) {
        HeadlineViews.Page page = HeadlineViews.page(number, headlines.headlines(), accounts.today(), accounts.zone(),
                accounts.latestBankBalances());
        if (page == null) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND);
        }
        model.addAttribute("page", page);
        return "build-up";
    }
}
