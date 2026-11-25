package finances.web;

import finances.accounts.AccountService;
import finances.accounts.AccountSummary;
import finances.money.Freshness;
import finances.sync.BankStatus;
import finances.sync.FreshnessService;
import java.util.List;
import java.util.Map;
import org.springframework.boot.info.BuildProperties;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;

@Controller
class OverviewController {

    private final BuildProperties build;
    private final AccountService accounts;
    private final BankStatus bank;
    private final FreshnessService freshness;

    OverviewController(BuildProperties build, AccountService accounts, BankStatus bank, FreshnessService freshness) {
        this.build = build;
        this.accounts = accounts;
        this.bank = bank;
        this.freshness = freshness;
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
        model.addAttribute("groups", AccountViews.groups(active, accounts.today(), Map.of(), accounts.zone(), accounts.latestBankBalances(), stale));
        model.addAttribute("attention", active.stream().filter(s -> stale.containsKey(s.account().id()))
                .map(s -> new AttentionRow(s.account().id(), s.account().name())).toList());
        model.addAttribute("expiring", freshness.expiringConsents().stream()
                .map(e -> new ExpiringRow(e.consent().id(), e.consent().bankName(), e.endsOn().toString(), e.daysLeft())).toList());
        return "overview";
    }
}
