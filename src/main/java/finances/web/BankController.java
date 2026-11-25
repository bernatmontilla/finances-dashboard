package finances.web;

import finances.accounts.Account;
import finances.accounts.AccountException;
import finances.accounts.AccountService;
import finances.accounts.Kind;
import finances.accounts.Scope;
import finances.sync.BankAccountLink;
import finances.sync.BankLinkService;
import finances.sync.BankStatus;
import finances.sync.BankSyncService;
import finances.sync.Consent;
import finances.sync.LinkException;
import finances.sync.LinkOutcome;
import finances.sync.SyncSummary;
import finances.sync.SyncTrigger;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.time.Clock;
import java.util.List;
import java.util.Locale;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.servlet.view.RedirectView;

/**
 * The bank link pages: choosing a bank, coming back from the bank, confirming the accounts, renewing,
 * unlinking, and "Sync now". The rules live in {@code finances.sync}; this class reads the forms and shows what
 * was refused as a message key. Every action that changes data is a POST (the same-origin filter covers it);
 * the one GET that stores something is the bank's callback, which is only accepted with a state this server
 * issued and which works once. Nothing here logs, and nothing the bank sent as text is shown.
 */
@Controller
class BankController {

    private static final List<String> SCOPE_CODES = List.of("private", "business");
    private static final List<String> KIND_CODES = List.of("current", "savings", "investment", "debt");

    private final BankLinkService links;
    private final BankSyncService sync;
    private final BankStatus status;
    private final AccountService accounts;
    private final Clock clock;

    BankController(BankLinkService links, BankSyncService sync, BankStatus status, AccountService accounts, Clock clock) {
        this.clock = clock;
        this.links = links;
        this.sync = sync;
        this.status = status;
        this.accounts = accounts;
    }

    // ----- choose a bank

    @GetMapping("/settings/bank/link")
    String linkForm(@RequestParam(required = false) String country, Model model, HttpServletResponse response) {
        try {
            if (country == null || country.isBlank()) {
                model.addAttribute("countries", links.countries().stream()
                        .map(code -> new CountryOption(code, BankViews.countryName(code))).toList());
                model.addAttribute("country", null);
            } else {
                model.addAttribute("country", country.strip().toUpperCase(Locale.ROOT));
                model.addAttribute("countryName", BankViews.countryName(country.strip().toUpperCase(Locale.ROOT)));
                model.addAttribute("banks", links.banks(country));
            }
        } catch (LinkException e) {
            model.addAttribute("error", e.messageKey());
            response.setStatus(HttpStatus.BAD_REQUEST.value());
        }
        model.addAttribute("renewId", null);
        return "bank-link";
    }

    record CountryOption(String code, String name) {
    }

    @PostMapping("/settings/bank/link")
    Object start(@RequestParam(defaultValue = "") String country, @RequestParam(defaultValue = "") String bank) {
        return startAuthorisation(country, bank, null);
    }

    @PostMapping("/settings/bank/consents/{id}/renew")
    Object renew(@PathVariable long id) {
        Consent consent = status.consent(id).orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));
        return startAuthorisation(consent.bankCountry(), consent.bankName(), id);
    }

    private Object startAuthorisation(String country, String bank, Long renewId) {
        try {
            // The bank's own page is opened by the browser: only an address of this application's choosing is ever
            // followed, and it is the address the API gave for this authorisation.
            return new RedirectView(links.start(country, bank, renewId), false, false, false);
        } catch (LinkException e) {
            return settingsRefused(e);
        }
    }

    // ----- coming back from the bank

    @GetMapping("/settings/bank/callback")
    Object callback(@RequestParam(required = false) String state, @RequestParam(required = false) String code,
            @RequestParam(required = false) String error, HttpServletRequest request) {
        try {
            LinkOutcome outcome = links.complete(state, code, error, BankViews.userHeaders(request));
            if (outcome.empty()) {
                return "redirect:/settings?bank=empty";
            }
            return "redirect:/settings/bank/consents/" + outcome.consentId() + "?reattached=" + outcome.reattached();
        } catch (LinkException e) {
            return settingsRefused(e);
        }
    }

    // ----- one consent: its accounts, confirming them, renewing, unlinking

    @GetMapping("/settings/bank/consents/{id}")
    String consent(@PathVariable long id, @RequestParam(required = false) String sync, @RequestParam(required = false) Integer reattached,
            Model model) {
        return consentPage(model, id, null, sync, reattached == null ? 0 : reattached);
    }

    private String consentPage(Model model, long id, String error, String syncState, int reattached) {
        Consent consent = status.consent(id).orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));
        List<BankAccountLink> all = status.links(id);
        model.addAttribute("consent", consent);
        model.addAttribute("validUntil", consent.validUntil().atZone(clock.getZone()).toLocalDate().toString());
        model.addAttribute("linked", all.stream().filter(BankAccountLink::linked)
                .map(link -> new LinkedRow(link.accountId(), accounts.find(link.accountId()).map(Account::name).orElse(""), link.iban())).toList());
        model.addAttribute("offered", all.stream().filter(l -> !l.linked() && l.supported()).map(this::offered).toList());
        model.addAttribute("unsupported", all.stream().filter(l -> !l.linked() && !l.supported()).map(l -> new UnsupportedRow(l.details(), l.currency())).toList());
        model.addAttribute("attachable", accounts.list(false).stream().map(s -> s.account())
                .filter(links::attachable).toList());
        model.addAttribute("scopes", SCOPE_CODES);
        model.addAttribute("kinds", KIND_CODES);
        model.addAttribute("reattached", reattached);
        model.addAttribute("syncKey", syncState == null ? null : "bank.consent.sync." + (syncState.equals("failed") ? "failed" : syncState.equals("busy") ? "busy" : "done"));
        model.addAttribute("error", error);
        return "bank-consent";
    }

    record LinkedRow(long accountId, String name, String iban) {
    }

    record UnsupportedRow(String details, String currency) {
    }

    record OfferedRow(long id, String iban, String suggestedName, String kind, boolean hasIban) {
    }

    private OfferedRow offered(BankAccountLink link) {
        String kind = switch (link.cashAccountType() == null ? "" : link.cashAccountType()) {
            case "SVGS" -> "savings";
            case "LOAN" -> "debt";
            default -> "current";
        };
        String name = (status.consent(link.consentId()).map(Consent::bankName).orElse("")
                + (link.details() == null ? "" : " " + link.details())).strip();
        return new OfferedRow(link.id(), link.iban() == null ? "" : link.iban(), name.length() > 100 ? name.substring(0, 100) : name, kind,
                link.iban() != null);
    }

    @PostMapping("/settings/bank/consents/{id}/accounts/{linkId}/create")
    String create(@PathVariable long id, @PathVariable long linkId, @RequestParam(defaultValue = "") String name,
            @RequestParam(defaultValue = "") String scope, @RequestParam(defaultValue = "") String kind,
            HttpServletRequest request, Model model, HttpServletResponse response) {
        try {
            SyncSummary summary = links.create(id, linkId, name, Scope.parse(scope), Kind.parse(kind), BankViews.userHeaders(request));
            return "redirect:/settings/bank/consents/" + id + "?sync=" + syncParam(summary);
        } catch (LinkException e) {
            return consentRefused(model, response, id, e.messageKey());
        } catch (AccountException e) {
            return consentRefused(model, response, id, "error.account." + e.reason().name().toLowerCase(Locale.ROOT));
        }
    }

    @PostMapping("/settings/bank/consents/{id}/accounts/{linkId}/attach")
    String attach(@PathVariable long id, @PathVariable long linkId, @RequestParam(defaultValue = "0") long account,
            HttpServletRequest request, Model model, HttpServletResponse response) {
        try {
            SyncSummary summary = links.attach(id, linkId, account, BankViews.userHeaders(request));
            return "redirect:/settings/bank/consents/" + id + "?sync=" + syncParam(summary);
        } catch (LinkException e) {
            return consentRefused(model, response, id, e.messageKey());
        } catch (AccountException e) {
            return consentRefused(model, response, id, "error.account." + e.reason().name().toLowerCase(Locale.ROOT));
        }
    }

    @PostMapping("/settings/bank/consents/{id}/unlink")
    String unlink(@PathVariable long id, Model model, HttpServletResponse response) {
        try {
            links.unlink(id);
            return "redirect:/settings?bank=unlinked";
        } catch (LinkException e) {
            if (e.reason() == LinkException.Reason.NOT_FOUND) {
                throw new ResponseStatusException(HttpStatus.NOT_FOUND);
            }
            return consentRefused(model, response, id, e.messageKey());
        }
    }

    private String consentRefused(Model model, HttpServletResponse response, long id, String errorKey) {
        response.setStatus(HttpStatus.BAD_REQUEST.value());
        return consentPage(model, id, errorKey, null, 0);
    }

    private static String syncParam(SyncSummary summary) {
        return summary.state() == SyncSummary.State.BUSY ? "busy" : summary.failed() > 0 ? "failed" : "done";
    }

    // ----- Sync now

    @PostMapping("/sync")
    String syncNow(HttpServletRequest request) {
        SyncSummary summary = sync.syncAll(SyncTrigger.USER, BankViews.userHeaders(request));
        return "redirect:/?sync=" + (summary.state() == SyncSummary.State.DONE ? (summary.failed() > 0 ? "failed" : "done")
                : summary.state() == SyncSummary.State.BUSY ? "busy" : "notConfigured")
                + "&ok=" + summary.ok() + "&failed=" + summary.failed();
    }

    // ----- errors shown on the settings page

    /** Back to the settings page with the reason as a short code (the page maps it to a message; nothing from the bank is shown). */
    private static String settingsRefused(LinkException e) {
        return "redirect:/settings?bankFailed=" + e.code();
    }
}
