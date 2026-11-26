package finances.web;

import finances.accounts.Account;
import finances.accounts.AccountException;
import finances.accounts.AccountService;
import finances.accounts.AccountSummary;
import finances.accounts.FixedCost;
import finances.accounts.FixedCostException;
import finances.accounts.FixedCostService;
import finances.accounts.FixedCostSummary;
import finances.accounts.Scope;
import finances.money.AmountFormatException;
import finances.money.Freshness;
import finances.money.Money;
import finances.money.Rhythm;
import finances.sync.FreshnessService;
import jakarta.servlet.http.HttpServletResponse;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.server.ResponseStatusException;

/**
 * The fixed costs pages (issue #21). Rules live in {@code FixedCostService} and {@code finances.money.ChargeSchedule}; this class
 * reads the forms, calls them and shows what they refuse as message keys. A refused form is shown again, filled in, with status 400.
 * Nothing here adds or compares amounts.
 */
@Controller
class FixedCostsController {

    private static final List<String> SCOPE_CODES = List.of("private", "business");

    private final FixedCostService fixedCosts;
    private final AccountService accounts;
    private final FreshnessService freshness;

    FixedCostsController(FixedCostService fixedCosts, AccountService accounts, FreshnessService freshness) {
        this.fixedCosts = fixedCosts;
        this.accounts = accounts;
        this.freshness = freshness;
    }

    // ----- the list

    @GetMapping("/fixed-costs")
    String list(Model model) {
        return listPage(model);
    }

    private String listPage(Model model) {
        List<FixedCostViews.Group> groups = new ArrayList<>();
        Map<Long, Freshness.Stale> stale = freshness.stale(accounts.list(false));
        for (Scope scope : Scope.values()) {
            FixedCostSummary summary = fixedCosts.summary(scope);
            FixedCostViews.Group group = FixedCostViews.group(scope, summary, fixedCosts.months(scope), this::accountName,
                    FixedCostViews.basis(summary, accounts.zone(), accounts.today(), stale));
            if (!group.isEmpty()) {
                groups.add(group);
            }
        }
        model.addAttribute("groups", groups);
        return "fixed-costs";
    }

    // ----- one fixed cost: add, edit

    @GetMapping("/fixed-costs/new")
    String newForm(Model model) {
        return form(model, null, "", "private", "", Rhythm.MONTHLY.code(), "", "", "", "");
    }

    @PostMapping("/fixed-costs")
    String create(@RequestParam(defaultValue = "") String name, @RequestParam(defaultValue = "") String scope,
            @RequestParam(defaultValue = "") String amount, @RequestParam(defaultValue = "") String rhythm,
            @RequestParam(defaultValue = "") String firstDue, @RequestParam(defaultValue = "") String counterparty,
            @RequestParam(defaultValue = "") String iban, @RequestParam(defaultValue = "") String account, Model model,
            HttpServletResponse response) {
        try {
            fixedCosts.create(name, Scope.parse(scope), Money.parse(amount), parseRhythm(rhythm), parseDate(firstDue), counterparty, iban,
                    parseAccount(account));
            return "redirect:/fixed-costs";
        } catch (FixedCostException | AccountException | AmountFormatException e) {
            return refused(response, model, e, form(model, null, name, scope, amount, rhythm, firstDue, counterparty, iban, account));
        }
    }

    @GetMapping("/fixed-costs/{id}/edit")
    String editForm(@PathVariable long id, Model model) {
        FixedCost cost = editable(id);
        return form(model, cost, cost.name(), cost.scope().code(), AccountsController.plainAmount(cost.amount()), cost.rhythm().code(),
                cost.firstDue().toString(), cost.counterpartyName() == null ? "" : cost.counterpartyName(),
                cost.counterpartyIban() == null ? "" : cost.counterpartyIban(), cost.accountId() == null ? "" : cost.accountId().toString());
    }

    @PostMapping("/fixed-costs/{id}")
    String update(@PathVariable long id, @RequestParam(defaultValue = "") String name, @RequestParam(defaultValue = "") String scope,
            @RequestParam(defaultValue = "") String amount, @RequestParam(defaultValue = "") String rhythm,
            @RequestParam(defaultValue = "") String firstDue, @RequestParam(defaultValue = "") String counterparty,
            @RequestParam(defaultValue = "") String iban, @RequestParam(defaultValue = "") String account, Model model,
            HttpServletResponse response) {
        FixedCost cost = editable(id);
        try {
            fixedCosts.update(id, name, Scope.parse(scope), Money.parse(amount), parseRhythm(rhythm), parseDate(firstDue), counterparty,
                    iban, parseAccount(account));
            return "redirect:/fixed-costs";
        } catch (FixedCostException | AccountException | AmountFormatException e) {
            return refused(response, model, e, form(model, cost, name, scope, amount, rhythm, firstDue, counterparty, iban, account));
        }
    }

    // ----- pause, resume, end, remove, tick

    @PostMapping("/fixed-costs/{id}/pause")
    String pause(@PathVariable long id, Model model, HttpServletResponse response) {
        return act(id, model, response, () -> fixedCosts.pause(id));
    }

    @PostMapping("/fixed-costs/{id}/resume")
    String resume(@PathVariable long id, Model model, HttpServletResponse response) {
        return act(id, model, response, () -> fixedCosts.resume(id));
    }

    /** Ends a fixed cost on the date given, or today when it is blank. */
    @PostMapping("/fixed-costs/{id}/end")
    String end(@PathVariable long id, @RequestParam(defaultValue = "") String endedOn, Model model, HttpServletResponse response) {
        return act(id, model, response, () -> fixedCosts.end(id, parseEndDate(endedOn)));
    }

    @PostMapping("/fixed-costs/{id}/remove")
    String remove(@PathVariable long id, Model model, HttpServletResponse response) {
        return act(id, model, response, () -> fixedCosts.remove(id));
    }

    @PostMapping("/fixed-costs/{id}/tick")
    String tick(@PathVariable long id, Model model, HttpServletResponse response) {
        return act(id, model, response, () -> fixedCosts.tick(id));
    }

    @PostMapping("/fixed-costs/{id}/untick")
    String untick(@PathVariable long id, Model model, HttpServletResponse response) {
        return act(id, model, response, () -> fixedCosts.untick(id));
    }

    // ----- pages and helpers

    private String act(long id, Model model, HttpServletResponse response, Runnable change) {
        found(id);
        try {
            change.run();
            return "redirect:/fixed-costs";
        } catch (FixedCostException e) {
            return refused(response, model, e, listPage(model));
        }
    }

    private String form(Model model, FixedCost cost, String name, String scope, String amount, String rhythm, String firstDue,
            String counterparty, String iban, String account) {
        model.addAttribute("cost", cost);
        model.addAttribute("name", name);
        model.addAttribute("scope", scope);
        model.addAttribute("amount", amount);
        model.addAttribute("rhythm", rhythm);
        model.addAttribute("firstDue", firstDue);
        model.addAttribute("counterparty", counterparty);
        model.addAttribute("iban", iban);
        model.addAttribute("account", account);
        model.addAttribute("scopes", SCOPE_CODES);
        model.addAttribute("rhythms", java.util.Arrays.stream(Rhythm.values()).map(Rhythm::code).toList());
        model.addAttribute("accountOptions", accountOptions(account));
        return "fixed-cost-form";
    }

    /**
     * The accounts in use that a fixed cost can leave from, with their scope so that the owner sees which one fits. An archived
     * account that is the one selected (still linked, or posted back) is listed too, marked archived, so saving the form keeps the link.
     */
    private List<AccountOption> accountOptions(String selected) {
        List<AccountOption> options = new ArrayList<>();
        for (AccountSummary summary : accounts.list(false)) {
            Account account = summary.account();
            options.add(new AccountOption(Long.toString(account.id()), account.name(), AccountViews.scopeKey(account.scope()), false));
        }
        Long selectedId = selectedId(selected);
        if (selectedId != null && options.stream().noneMatch(option -> option.value().equals(selectedId.toString()))) {
            accounts.find(selectedId).filter(Account::archived).ifPresent(account -> options
                    .add(new AccountOption(Long.toString(account.id()), account.name(), AccountViews.scopeKey(account.scope()), true)));
        }
        return options;
    }

    private static Long selectedId(String selected) {
        try {
            return selected == null || selected.isBlank() ? null : Long.valueOf(selected.strip());
        } catch (NumberFormatException e) {
            return null;
        }
    }

    record AccountOption(String value, String name, String scopeKey, boolean archived) {
    }

    private String accountName(long id) {
        return accounts.find(id).map(Account::name).orElse(null);
    }

    private String refused(jakarta.servlet.http.HttpServletResponse response, Model model, RuntimeException e, String view) {
        model.addAttribute("error", errorKey(e));
        response.setStatus(HttpStatus.BAD_REQUEST.value());
        return view;
    }

    static String errorKey(RuntimeException e) {
        if (e instanceof FixedCostException fixedCost) {
            return "error.fixedcost." + fixedCost.reason().name().toLowerCase(Locale.ROOT);
        }
        return AccountsController.errorKey(e);
    }

    private FixedCost found(long id) {
        return fixedCosts.find(id).orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));
    }

    /** An ended fixed cost is not edited: it is resumed first. */
    private FixedCost editable(long id) {
        FixedCost cost = found(id);
        if (cost.isEnded(accounts.today())) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND);
        }
        return cost;
    }

    private static Rhythm parseRhythm(String rhythm) {
        try {
            return Rhythm.parse(rhythm);
        } catch (IllegalArgumentException e) {
            throw new FixedCostException(FixedCostException.Reason.INVALID_RHYTHM);
        }
    }

    private static Long parseAccount(String account) {
        if (account.isBlank()) {
            return null;
        }
        try {
            return Long.valueOf(account.strip());
        } catch (NumberFormatException e) {
            throw new FixedCostException(FixedCostException.Reason.ACCOUNT_NOT_FOUND);
        }
    }

    private static LocalDate parseDate(String date) {
        if (date.isBlank()) {
            return null;
        }
        try {
            return LocalDate.parse(date.strip());
        } catch (DateTimeParseException e) {
            throw new FixedCostException(FixedCostException.Reason.FIRST_DUE_INVALID);
        }
    }

    private static LocalDate parseEndDate(String date) {
        if (date.isBlank()) {
            return null;
        }
        try {
            return LocalDate.parse(date.strip());
        } catch (DateTimeParseException e) {
            throw new FixedCostException(FixedCostException.Reason.END_DATE_INVALID);
        }
    }
}
