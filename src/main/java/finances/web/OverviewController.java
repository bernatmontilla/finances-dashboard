package finances.web;

import finances.accounts.AccountService;
import finances.sync.BankStatus;
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

    OverviewController(BuildProperties build, AccountService accounts, BankStatus bank) {
        this.build = build;
        this.accounts = accounts;
        this.bank = bank;
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
        model.addAttribute("groups", AccountViews.groups(accounts.list(false), accounts.today()));
        return "overview";
    }
}
