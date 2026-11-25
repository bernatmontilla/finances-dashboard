package finances.web;

import finances.accounts.AccountService;
import finances.accounts.TransactionService;
import java.time.LocalDate;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;

/** The page of all transactions across accounts, newest first, paged. Read only. */
@Controller
class TransactionsController {

    private final AccountService accounts;
    private final TransactionService transactions;

    TransactionsController(AccountService accounts, TransactionService transactions) {
        this.accounts = accounts;
        this.transactions = transactions;
    }

    @GetMapping("/transactions")
    String list(@RequestParam(defaultValue = "0") String page, Model model) {
        LocalDate today = accounts.today();
        model.addAttribute("transactions", TransactionViews.page(transactions.all(TransactionViews.pageNumber(page))));
        model.addAttribute("transactionsPath", "/transactions");
        model.addAttribute("groups", AccountViews.groups(accounts.list(false), today, transactions.lastDelivered(), transactions.zone()));
        return "transactions";
    }
}
