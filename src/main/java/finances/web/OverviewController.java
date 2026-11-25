package finances.web;

import org.springframework.boot.info.BuildProperties;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;

@Controller
class OverviewController {

    private final BuildProperties build;

    OverviewController(BuildProperties build) {
        this.build = build;
    }

    @GetMapping("/")
    String overview(Model model) {
        model.addAttribute("version", build.getVersion());
        return "overview";
    }
}
