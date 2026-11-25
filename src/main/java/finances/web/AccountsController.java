package finances.web;

import finances.accounts.Account;
import finances.accounts.AccountException;
import finances.accounts.AccountService;
import finances.accounts.BalanceEntry;
import finances.accounts.Kind;
import finances.accounts.Scope;
import finances.accounts.Source;
import finances.accounts.TransactionService;
import finances.money.AmountFormatException;
import finances.sync.BankStatus;
import finances.money.Money;
import jakarta.servlet.http.HttpServletResponse;
import java.time.Instant;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
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
 * The accounts pages. Rules live in {@code finances.accounts} and {@code finances.money}; this
 * class reads the forms, calls them and shows what they refuse as message keys. A refused form is
 * shown again, filled in, with status 400. Nothing here deletes an account.
 */
@Controller
class AccountsController {

    private static final List<String> SCOPE_CODES = List.of("private", "business");
    private static final List<String> SOURCE_CODES = List.of("manual", "csv");
    private static final List<String> KIND_CODES = List.of("current", "savings", "investment", "debt");

    private final AccountService accounts;
    private final TransactionService transactions;
    private final BankStatus bank;

    AccountsController(AccountService accounts, TransactionService transactions, BankStatus bank) {
        this.accounts = accounts;
        this.transactions = transactions;
        this.bank = bank;
    }

    // ----- the list

    @GetMapping("/accounts")
    String list(Model model) {
        LocalDate today = accounts.today();
        Map<Long, Instant> delivered = transactions.lastDelivered();
        var banks = accounts.latestBankBalances();
        model.addAttribute("groups", AccountViews.groups(accounts.list(false), today, delivered, transactions.zone(), banks));
        model.addAttribute("archivedGroups", AccountViews.groups(accounts.list(true), today, delivered, transactions.zone(), banks));
        return "accounts";
    }

    // ----- one account: add, edit, archive

    @GetMapping("/accounts/new")
    String newForm(Model model) {
        return accountForm(model, null, "", "private", "current", "", "manual");
    }

    @PostMapping("/accounts")
    String create(@RequestParam(defaultValue = "") String name, @RequestParam(defaultValue = "") String scope,
            @RequestParam(defaultValue = "") String kind, @RequestParam(defaultValue = "") String iban,
            @RequestParam(defaultValue = "manual") String source, Model model, HttpServletResponse response) {
        try {
            Account account = accounts.add(name, Scope.parse(scope), Kind.parse(kind), iban, Source.choose(source));
            return "redirect:/accounts/" + account.id();
        } catch (AccountException e) {
            return refused(response, model, e, accountForm(model, null, name, scope, kind, iban, source));
        }
    }

    @GetMapping("/accounts/{id}/edit")
    String editForm(@PathVariable long id, Model model) {
        Account account = found(id);
        return accountForm(model, account, account.name(), account.scope().code(), account.kind().code(),
                account.iban() == null ? "" : account.iban(), account.source().code());
    }

    @PostMapping("/accounts/{id}")
    String update(@PathVariable long id, @RequestParam(defaultValue = "") String name,
            @RequestParam(defaultValue = "") String scope, @RequestParam(defaultValue = "") String kind,
            @RequestParam(defaultValue = "") String iban, Model model, HttpServletResponse response) {
        Account account = found(id);
        try {
            accounts.update(id, name, Scope.parse(scope), Kind.parse(kind), iban);
            return "redirect:/accounts/" + id;
        } catch (AccountException e) {
            return refused(response, model, e, accountForm(model, account, name, scope, kind, iban, account.source().code()));
        }
    }

    @PostMapping("/accounts/{id}/archive")
    String archive(@PathVariable long id) {
        found(id);
        accounts.archive(id);
        return "redirect:/accounts";
    }

    @PostMapping("/accounts/{id}/restore")
    String restore(@PathVariable long id) {
        found(id);
        accounts.unarchive(id);
        return "redirect:/accounts/" + id;
    }

    // ----- the page of one account, with its balance history

    @GetMapping("/accounts/{id}")
    String detail(@PathVariable long id, @RequestParam(defaultValue = "0") String page, Model model) {
        return detailPage(model, found(id), "", "", TransactionViews.pageNumber(page));
    }

    @PostMapping("/accounts/{id}/balances")
    String addBalance(@PathVariable long id, @RequestParam(defaultValue = "") String amount,
            @RequestParam(defaultValue = "") String date, Model model, HttpServletResponse response) {
        Account account = found(id);
        try {
            accounts.addBalance(id, Money.parse(amount), parseDate(date));
            return "redirect:/accounts/" + id;
        } catch (AccountException | AmountFormatException e) {
            return refused(response, model, e, detailPage(model, account, amount, date));
        }
    }

    @GetMapping("/accounts/{id}/balances/{entryId}/edit")
    String editBalanceForm(@PathVariable long id, @PathVariable long entryId, Model model) {
        BalanceEntry entry = entry(id, entryId);
        return balanceForm(model, found(id), entry, plainAmount(entry.amount()), entry.date().toString());
    }

    @PostMapping("/accounts/{id}/balances/{entryId}")
    String correctBalance(@PathVariable long id, @PathVariable long entryId,
            @RequestParam(defaultValue = "") String amount, @RequestParam(defaultValue = "") String date, Model model, HttpServletResponse response) {
        Account account = found(id);
        BalanceEntry entry = entry(id, entryId);
        try {
            accounts.correctBalance(id, entryId, Money.parse(amount), parseDate(date));
            return "redirect:/accounts/" + id;
        } catch (AccountException | AmountFormatException e) {
            return refused(response, model, e, balanceForm(model, account, entry, amount, date));
        }
    }

    @PostMapping("/accounts/{id}/balances/{entryId}/remove")
    String removeBalance(@PathVariable long id, @PathVariable long entryId, Model model, HttpServletResponse response) {
        Account account = found(id);
        BalanceEntry entry = entry(id, entryId);
        try {
            accounts.removeBalance(id, entryId);
            return "redirect:/accounts/" + id;
        } catch (AccountException e) {
            return refused(response, model, e, balanceForm(model, account, entry, plainAmount(entry.amount()), entry.date().toString()));
        }
    }

    // ----- pages

    private String accountForm(Model model, Account account, String name, String scope, String kind, String iban, String source) {
        model.addAttribute("account", account);
        model.addAttribute("name", name);
        model.addAttribute("scope", scope);
        model.addAttribute("kind", kind);
        model.addAttribute("iban", iban);
        model.addAttribute("source", source);
        model.addAttribute("sources", SOURCE_CODES);
        model.addAttribute("scopes", SCOPE_CODES);
        model.addAttribute("kinds", KIND_CODES);
        return "account-form";
    }

    private String detailPage(Model model, Account account, String amount, String date) {
        return detailPage(model, account, amount, date, 0);
    }

    private String detailPage(Model model, Account account, String amount, String date, int page) {
        LocalDate today = accounts.today();
        model.addAttribute("transactions", TransactionViews.page(transactions.forAccount(account.id(), page)));
        model.addAttribute("transactionsPath", "/accounts/" + account.id());
        model.addAttribute("delivery", transactions.lastDelivered(account.id())
                .map(at -> AccountViews.delivery(at, transactions.zone(), today))
                .orElse(null));
        model.addAttribute("account", account);
        model.addAttribute("scopeKey", AccountViews.scopeKey(account.scope()));
        model.addAttribute("kindKey", AccountViews.kindKey(account.kind()));
        model.addAttribute("sourceKey", "source." + account.source().code());
        model.addAttribute("debt", account.kind() == Kind.DEBT);
        // Balances are typed in for an account that no bank feeds: a manual one or a CSV one.
        model.addAttribute("manual", account.source() != Source.BANK);
        model.addAttribute("csv", account.source() == Source.CSV);
        model.addAttribute("history", accounts.history(account.id()).stream()
                .map(e -> new AccountViews.EntryRow(e.id(), AccountViews.balance(e, today)))
                .toList());
        model.addAttribute("bank", account.source() != Source.BANK ? null : bank.consentOfAccount(account.id())
                .map(consent -> BankViews.account(consent, accounts.latestBankBalance(account.id()).orElse(null),
                        bank.lastRun(account.id()).orElse(null), transactions.zone()))
                .orElse(null));
        model.addAttribute("amount", amount);
        model.addAttribute("date", date.isEmpty() ? today.toString() : date);
        model.addAttribute("today", today.toString());
        return "account";
    }

    private String balanceForm(Model model, Account account, BalanceEntry entry, String amount, String date) {
        model.addAttribute("account", account);
        model.addAttribute("entry", entry);
        model.addAttribute("amount", amount);
        model.addAttribute("date", date);
        model.addAttribute("today", accounts.today().toString());
        model.addAttribute("debt", account.kind() == Kind.DEBT);
        return "balance-form";
    }

    // ----- helpers

    /** Marks the response as refused (400) and names the reason as a message key; returns the view given. */
    private String refused(HttpServletResponse response, Model model, RuntimeException e, String view) {
        model.addAttribute("error", errorKey(e));
        response.setStatus(HttpStatus.BAD_REQUEST.value());
        return view;
    }

    static String errorKey(RuntimeException e) {
        if (e instanceof AccountException account) {
            return "error.account." + account.reason().name().toLowerCase(Locale.ROOT);
        }
        if (e instanceof AmountFormatException amount) {
            return "error.amount." + amount.reason().name().toLowerCase(Locale.ROOT);
        }
        throw e;
    }

    private Account found(long id) {
        return accounts.find(id).orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));
    }

    private BalanceEntry entry(long id, long entryId) {
        try {
            return accounts.getEntry(id, entryId);
        } catch (AccountException e) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND);
        }
    }

    private static LocalDate parseDate(String date) {
        if (date.isBlank()) {
            return null;
        }
        try {
            return LocalDate.parse(date.strip());
        } catch (DateTimeParseException e) {
            throw new AccountException(AccountException.Reason.DATE_INVALID);
        }
    }

    /** The amount as the owner can type it back: {@code 1234.56}, no grouping, no sign confusion. */
    private static String plainAmount(Money money) {
        long absolute = Math.abs(money.cents());
        return (money.isNegative() ? "-" : "") + absolute / 100 + "." + (absolute % 100 < 10 ? "0" : "") + absolute % 100;
    }
}
