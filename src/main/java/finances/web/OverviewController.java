package finances.web;

import finances.accounts.AccountService;
import org.springframework.boot.info.BuildProperties;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;

@Controller
class OverviewController {

    private final BuildProperties build;
    private final AccountService accounts;

    OverviewController(BuildProperties build, AccountService accounts) {
        this.build = build;
        this.accounts = accounts;
    }

    @GetMapping("/")
    String overview(Model model) {
        model.addAttribute("version", build.getVersion());
        model.addAttribute("groups", AccountViews.groups(accounts.list(false), accounts.today()));
        return "overview";
    }
}
