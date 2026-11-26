package finances.web;

import finances.accounts.Account;
import finances.accounts.AccountSummary;
import finances.accounts.AsOfBasis;
import finances.accounts.BalanceEntry;
import finances.accounts.BankBalanceInfo;
import finances.accounts.Kind;
import finances.accounts.Scope;
import finances.money.Freshness;
import finances.outbound.BankApiException;
import finances.sync.AfterTaxReason;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.stream.Stream;

/**
 * What a page shows of accounts: texts are message keys (the templates hold none) and amounts are
 * formatted by {@code Money}. Every balance carries its date and its age (hard rule 9).
 */
final class AccountViews {

    /**
     * The short codes a failed bank run can carry: what {@code BankSyncService} stores (each {@link BankApiException.Reason}
     * lower-cased, a consent that ended written as {@code consent_expired}, or {@code store_failed}) and {@code unexpected}.
     * Only a code on this list becomes a message key.
     */
    static final Set<String> BANK_REASON_CODES = Stream.concat(
            Arrays.stream(BankApiException.Reason.class.getEnumConstants())
                    .map(reason -> reason == BankApiException.Reason.CONSENT_ENDED ? "consent_expired" : reason.name().toLowerCase(Locale.ROOT)),
            Stream.of("store_failed", "unexpected")).collect(java.util.stream.Collectors.toUnmodifiableSet());

    /** The codes of a failed fetch of the after-tax source ({@link AfterTaxReason}) and {@code unexpected}. */
    static final Set<String> SOURCE_REASON_CODES = Stream.concat(Arrays.stream(AfterTaxReason.values()).map(AfterTaxReason::code),
            Stream.of("unexpected")).collect(java.util.stream.Collectors.toUnmodifiableSet());

    private AccountViews() {
    }

    /**
     * A balance as shown. {@code debt}: shown as owed, never as money that is available. {@code ageKey} is the age in
     * words and {@code ageArg} its argument: the number of days, or the time of day for "today 07:10".
     */
    record BalanceView(String amount, String date, String ageKey, long ageDays, String noteKey, String noteArg, Object ageArg) {

        BalanceView(String amount, String date, String ageKey, long ageDays, String noteKey, String noteArg) {
            this(amount, date, ageKey, ageDays, noteKey, noteArg, ageDays);
        }

        public boolean hasNote() {
            return noteKey != null;
        }
    }

    /**
     * Why a figure is stale, as message keys. {@code key} takes {@code arg} (a number); a failed sync names its own
     * reason through {@code detailKey}, which is then the argument of {@code key}.
     */
    record StaleView(String key, Long arg, String detailKey) {
        public boolean hasDetail() {
            return detailKey != null;
        }
    }

    /** When an account's transactions were last delivered, as a day and its age (hard rule 9). */
    record DeliveryView(String date, String ageKey, long ageDays) {
    }

    record AccountRow(long id, String name, String scopeKey, String kindKey, String iban, boolean debt,
            BalanceView balance, DeliveryView delivery, StaleView stale) {

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
        if (bank.basis() == AsOfBasis.BANK_TIME && bank.asOf() != null) {
            plain = withTime(plain, bank.asOf(), today, zone);
        }
        if (!bank.booked()) {
            return new BalanceView(plain.amount(), plain.date(), plain.ageKey(), plain.ageDays(), "bank.balance.note.other", bank.type(), plain.ageArg());
        }
        if (bank.basis() == AsOfBasis.FETCHED) {
            return new BalanceView(plain.amount(), plain.date(), plain.ageKey(), plain.ageDays(), "bank.balance.note.fetched",
                    BankViews.moment(bank.asOf(), zone), plain.ageArg());
        }
        return plain;
    }

    /** The bank's own timestamp shows as "today 07:10": the age in words carries the time of day. */
    private static BalanceView withTime(BalanceView plain, Instant asOf, LocalDate today, ZoneId zone) {
        java.time.LocalDateTime moment = java.time.LocalDateTime.ofInstant(asOf, zone);
        long days = Math.max(0, ChronoUnit.DAYS.between(moment.toLocalDate(), today));
        if (days > 1) {
            return new BalanceView(plain.amount(), moment.toLocalDate().toString(), "age.days", days, null, null, days);
        }
        String time = BankViews.time(asOf, zone);
        return new BalanceView(plain.amount(), moment.toLocalDate().toString(), days == 0 ? "age.todayAt" : "age.yesterdayAt", days,
                null, null, time);
    }

    /** The reason as message keys and a number; the templates hold no text. */
    static StaleView stale(Freshness.Stale stale) {
        if (stale == null) {
            return null;
        }
        return switch (stale.reason()) {
            case CONSENT_EXPIRED -> new StaleView("stale.reason.consent_expired", null, null);
            case SYNC_FAILED -> failed("stale.reason.sync_failed", "bank.reason.", BANK_REASON_CODES, stale.detail());
            case SYNC_OLD -> stale.age().toHours() >= 48
                    ? new StaleView("stale.reason.sync_old.days", stale.age().toDays(), null)
                    : new StaleView("stale.reason.sync_old.hours", stale.age().toHours(), null);
            case NOT_UPDATED -> new StaleView("stale.reason.not_updated", stale.age().toDays(), null);
            case SOURCE_FAILED -> failed("stale.reason.source_failed", "aftertax.reason.", SOURCE_REASON_CODES, stale.detail());
            case SOURCE_OLD -> stale.age().toHours() >= 48
                    ? new StaleView("stale.reason.source_old.days", stale.age().toDays(), null)
                    : new StaleView("stale.reason.source_old.hours", stale.age().toHours(), null);
        };
    }

    /** A failed run or fetch names its reason only when the code is one this application knows; any other text is never a key. */
    private static StaleView failed(String key, String prefix, Set<String> known, String detail) {
        if (detail == null) {
            return new StaleView(key, null, prefix + "unexpected");
        }
        return known.contains(detail) ? new StaleView(key, null, prefix + detail) : new StaleView("stale.reason.unknown", null, null);
    }

    static AccountRow row(AccountSummary summary, LocalDate today, Map<Long, Instant> delivered, ZoneId zone,
            Map<Long, BankBalanceInfo> banks) {
        return row(summary, today, delivered, zone, banks, Map.of());
    }

    static AccountRow row(AccountSummary summary, LocalDate today, Map<Long, Instant> delivered, ZoneId zone,
            Map<Long, BankBalanceInfo> banks, Map<Long, Freshness.Stale> stale) {
        Account account = summary.account();
        Instant at = delivered.get(account.id());
        BankBalanceInfo bank = banks.get(account.id());
        return new AccountRow(account.id(), account.name(), scopeKey(account.scope()), kindKey(account.kind()),
                account.iban(), account.kind() == Kind.DEBT, summary.latest().map(e -> balance(e, today, bank, zone)).orElse(null),
                at == null ? null : delivery(at, zone, today), stale(stale.get(account.id())));
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
        return groups(summaries, today, delivered, zone, banks, Map.of());
    }

    static List<Group> groups(List<AccountSummary> summaries, LocalDate today, Map<Long, Instant> delivered, ZoneId zone,
            Map<Long, BankBalanceInfo> banks, Map<Long, Freshness.Stale> stale) {
        List<Group> groups = new ArrayList<>();
        for (Scope scope : Scope.values()) {
            List<AccountRow> rows = summaries.stream()
                    .filter(s -> s.account().scope() == scope)
                    .map(s -> row(s, today, delivered, zone, banks, stale))
                    .toList();
            if (!rows.isEmpty()) {
                groups.add(new Group(scopeKey(scope), rows));
            }
        }
        return groups;
    }
}
