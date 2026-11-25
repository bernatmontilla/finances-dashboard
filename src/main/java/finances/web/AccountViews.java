package finances.web;

import finances.accounts.Account;
import finances.accounts.AccountSummary;
import finances.accounts.AsOfBasis;
import finances.accounts.BalanceEntry;
import finances.accounts.BankBalanceInfo;
import finances.accounts.Kind;
import finances.accounts.Scope;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * What a page shows of accounts: texts are message keys (the templates hold none) and amounts are
 * formatted by {@code Money}. Every balance carries its date and its age (hard rule 9).
 */
final class AccountViews {

    private AccountViews() {
    }

    /** A balance as shown. {@code debt}: shown as owed, never as money that is available. */
    record BalanceView(String amount, String date, String ageKey, long ageDays, String noteKey, String noteArg) {
        public boolean hasNote() {
            return noteKey != null;
        }
    }

    /** When an account's transactions were last delivered, as a day and its age (hard rule 9). */
    record DeliveryView(String date, String ageKey, long ageDays) {
    }

    record AccountRow(long id, String name, String scopeKey, String kindKey, String iban, boolean debt,
            BalanceView balance, DeliveryView delivery) {

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
        return new BalanceView(entry.amount().format(), entry.date().toString(), ageKey, days, null, null);
    }

    static DeliveryView delivery(Instant deliveredAt, ZoneId zone, LocalDate today) {
        LocalDate day = LocalDate.ofInstant(deliveredAt, zone);
        long days = Math.max(0, ChronoUnit.DAYS.between(day, today));
        String ageKey = days == 0 ? "age.today" : days == 1 ? "age.yesterday" : "age.days";
        return new DeliveryView(day.toString(), ageKey, days);
    }

    /**
     * A bank balance is never shown as more than it is (hard rule 9): a type other than the booked ones says which
     * type it is, and one the bank gave no age for says that it was fetched, not that it is correct as of a day.
     */
    static BalanceView balance(BalanceEntry entry, LocalDate today, BankBalanceInfo bank, ZoneId zone) {
        BalanceView plain = balance(entry, today);
        if (bank == null || !bank.amount().equals(entry.amount()) || !bank.date().equals(entry.date())) {
            return plain;
        }
        if (!bank.booked()) {
            return new BalanceView(plain.amount(), plain.date(), plain.ageKey(), plain.ageDays(), "bank.balance.note.other", bank.type());
        }
        if (bank.basis() == AsOfBasis.FETCHED) {
            return new BalanceView(plain.amount(), plain.date(), plain.ageKey(), plain.ageDays(), "bank.balance.note.fetched",
                    BankViews.moment(bank.asOf(), zone));
        }
        return plain;
    }

    static AccountRow row(AccountSummary summary, LocalDate today, Map<Long, Instant> delivered, ZoneId zone,
            Map<Long, BankBalanceInfo> banks) {
        Account account = summary.account();
        Instant at = delivered.get(account.id());
        BankBalanceInfo bank = banks.get(account.id());
        return new AccountRow(account.id(), account.name(), scopeKey(account.scope()), kindKey(account.kind()),
                account.iban(), account.kind() == Kind.DEBT, summary.latest().map(e -> balance(e, today, bank, zone)).orElse(null),
                at == null ? null : delivery(at, zone, today));
    }

    /** One group per scope that has accounts, in the order of the list given (already sorted). */
    static List<Group> groups(List<AccountSummary> summaries, LocalDate today) {
        return groups(summaries, today, Map.of(), ZoneId.systemDefault());
    }

    static List<Group> groups(List<AccountSummary> summaries, LocalDate today, Map<Long, Instant> delivered, ZoneId zone) {
        return groups(summaries, today, delivered, zone, Map.of());
    }

    static List<Group> groups(List<AccountSummary> summaries, LocalDate today, Map<Long, Instant> delivered, ZoneId zone,
            Map<Long, BankBalanceInfo> banks) {
        List<Group> groups = new ArrayList<>();
        for (Scope scope : Scope.values()) {
            List<AccountRow> rows = summaries.stream()
                    .filter(s -> s.account().scope() == scope)
                    .map(s -> row(s, today, delivered, zone, banks))
                    .toList();
            if (!rows.isEmpty()) {
                groups.add(new Group(scopeKey(scope), rows));
            }
        }
        return groups;
    }
}
