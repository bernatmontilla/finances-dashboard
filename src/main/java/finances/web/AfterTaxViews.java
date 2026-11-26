package finances.web;

import finances.sync.AfterTaxFetchRow;
import finances.sync.AfterTaxReason;
import finances.sync.AfterTaxService;
import finances.sync.AfterTaxValue;
import java.time.Clock;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import java.util.Arrays;
import java.util.List;

/**
 * What the settings page shows of the after-tax source (issue #18): texts are message keys (the templates hold none),
 * amounts are formatted by {@code Money}, the age is shown as every balance's is. The address of the source and its token
 * are never shown, only whether they are there. A label comes from the source and is only ever shown as text. Nothing
 * here logs.
 */
final class AfterTaxViews {

    static final int FETCHES_SHOWN = 10;

    private AfterTaxViews() {
    }

    record ReservedLine(String label, String amount) {
    }

    /** The newest value: its amount as a balance (with the age of its own {@code as_of}), and the build-up the source sent. */
    record ValueView(AccountViews.BalanceView balance, String gross, boolean mismatch, List<ReservedLine> reserved) {
        public boolean hasGross() {
            return gross != null;
        }

        public boolean hasReserved() {
            return !reserved.isEmpty();
        }
    }

    /** {@code reasonKey} says why a fetch failed, or, for one that went well, what was special about it (an answer older than the value shown). */
    record FetchRow(String when, String triggerKey, boolean ok, String reasonKey, Integer status) {
        public boolean hasStatus() {
            return status != null;
        }

        public boolean hasNote() {
            return ok && reasonKey != null;
        }
    }

    record Section(boolean configured, boolean https, boolean tokenPresent, boolean tokenOverPlainHttp, ValueView value,
            AccountViews.StaleView stale, List<FetchRow> fetches, String noticeKey) {
        public boolean hasValue() {
            return value != null;
        }

        public boolean hasFetches() {
            return !fetches.isEmpty();
        }

        public boolean hasNotice() {
            return noticeKey != null;
        }
    }

    /** Only the three outcomes of the Test button become a message: whatever else is in the address is ignored. */
    static String testNotice(String code) {
        if (code == null) {
            return null;
        }
        return switch (code) {
            case "ok", "failed", "busy" -> "aftertax.test." + code;
            default -> null;
        };
    }

    static Section section(AfterTaxService service, Clock clock, String noticeKey) {
        if (!service.configured()) {
            return new Section(false, false, false, false, null, null, List.of(), noticeKey);
        }
        ZoneId zone = clock.getZone();
        AfterTaxValue newest = service.current().orElse(null);
        List<FetchRow> fetches = service.recentFetches(FETCHES_SHOWN).stream().map(row -> fetch(row, zone)).toList();
        return new Section(true, service.https(), service.tokenPresent(), service.tokenOverPlainHttp(),
                newest == null ? null : value(newest, LocalDate.now(clock), zone),
                AccountViews.stale(service.standing(newest).orElse(null)), fetches, noticeKey);
    }

    static ValueView value(AfterTaxValue value, LocalDate today, ZoneId zone) {
        List<ReservedLine> lines = value.reserved().stream().map(r -> new ReservedLine(r.label(), r.amount().format())).toList();
        return new ValueView(balance(value, today, zone), value.hasGross() ? value.gross().format() : null, value.mismatch(), lines);
    }

    /** The amount with the moment the source calculated it and its age: "today 06:00", "yesterday 06:00" or "3 days ago". */
    static AccountViews.BalanceView balance(AfterTaxValue value, LocalDate today, ZoneId zone) {
        LocalDateTime moment = LocalDateTime.ofInstant(value.asOf(), zone);
        long days = Math.max(0, ChronoUnit.DAYS.between(moment.toLocalDate(), today));
        String amount = value.available().format();
        if (days > 1) {
            return new AccountViews.BalanceView(amount, moment.toLocalDate().toString(), "age.days", days, null, null, days);
        }
        return new AccountViews.BalanceView(amount, moment.toLocalDate().toString(), days == 0 ? "age.todayAt" : "age.yesterdayAt",
                days, null, null, BankViews.time(value.asOf(), zone));
    }

    private static FetchRow fetch(AfterTaxFetchRow row, ZoneId zone) {
        return new FetchRow(BankViews.moment(row.startedAt(), zone), "aftertax.trigger." + triggerCode(row.trigger()), row.ok(),
                row.ok() ? okNote(row.reason()) : "aftertax.reason." + reasonCode(row.reason()), row.httpStatus());
    }

    /** The one note a fetch that went well can carry; any other stored text shows nothing. */
    private static String okNote(String reason) {
        return AfterTaxReason.OLDER_THAN_KNOWN.code().equals(reason) ? "aftertax.reason." + AfterTaxReason.OLDER_THAN_KNOWN.code() : null;
    }

    private static String triggerCode(String trigger) {
        return "scheduled".equals(trigger) || "user".equals(trigger) ? trigger : "test";
    }

    /** Only a code this application names becomes a message key. */
    private static String reasonCode(String reason) {
        return Arrays.stream(AfterTaxReason.values()).map(AfterTaxReason::code).filter(code -> code.equals(reason)).findFirst()
                .orElse("unexpected");
    }
}
