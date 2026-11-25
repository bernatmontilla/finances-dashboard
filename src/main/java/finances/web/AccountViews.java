package finances.web;

import finances.accounts.Account;
import finances.accounts.AccountSummary;
import finances.accounts.BalanceEntry;
import finances.accounts.Kind;
import finances.accounts.Scope;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;

/**
 * What a page shows of accounts: texts are message keys (the templates hold none) and amounts are
 * formatted by {@code Money}. Every balance carries its date and its age (hard rule 9).
 */
final class AccountViews {

    private AccountViews() {
    }

    /** A balance as shown. {@code debt}: shown as owed, never as money that is available. */
    record BalanceView(String amount, String date, String ageKey, long ageDays) {
    }

    record AccountRow(long id, String name, String scopeKey, String kindKey, String iban, boolean debt,
            BalanceView balance) {

        boolean hasBalance() {
            return balance != null;
        }
    }

    record Group(String labelKey, List<AccountRow> rows) {
    }

    record EntryRow(long id, BalanceView balance) {
    }

    static String scopeKey(Scope scope) {
        return "scope." + scope.code();
    }

    static String kindKey(Kind kind) {
        return "kind." + kind.code();
    }

    static BalanceView balance(BalanceEntry entry, LocalDate today) {
        long days = Math.max(0, ChronoUnit.DAYS.between(entry.date(), today));
        String ageKey = days == 0 ? "age.today" : days == 1 ? "age.yesterday" : "age.days";
        return new BalanceView(entry.amount().format(), entry.date().toString(), ageKey, days);
    }

    static AccountRow row(AccountSummary summary, LocalDate today) {
        Account account = summary.account();
        return new AccountRow(account.id(), account.name(), scopeKey(account.scope()), kindKey(account.kind()),
                account.iban(), account.kind() == Kind.DEBT, summary.latest().map(e -> balance(e, today)).orElse(null));
    }

    /** One group per scope that has accounts, in the order of the list given (already sorted). */
    static List<Group> groups(List<AccountSummary> summaries, LocalDate today) {
        List<Group> groups = new ArrayList<>();
        for (Scope scope : Scope.values()) {
            List<AccountRow> rows = summaries.stream()
                    .filter(s -> s.account().scope() == scope)
                    .map(s -> row(s, today))
                    .toList();
            if (!rows.isEmpty()) {
                groups.add(new Group(scopeKey(scope), rows));
            }
        }
        return groups;
    }
}
