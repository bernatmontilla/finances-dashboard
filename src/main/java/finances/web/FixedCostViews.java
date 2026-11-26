package finances.web;

import finances.accounts.FixedCost;
import finances.accounts.FixedCostMonth;
import finances.accounts.FixedCostSummary;
import finances.accounts.Scope;
import finances.accounts.Transaction;
import finances.money.Freshness;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.LongFunction;

/**
 * What the fixed costs page and the overview show of fixed costs (issues #21 and #63): the figures come from {@code FixedCostService}
 * and {@code finances.money.ChargeSchedule}; this class only formats them and picks message keys. It adds, subtracts and compares no
 * amounts, and nothing here logs.
 */
final class FixedCostViews {

    private FixedCostViews() {
    }

    /**
     * One fixed cost. {@code status} is the lower-case status code (paid, still_to_come, not_this_month, paused, ended);
     * {@code expected} is this month's charge date and {@code nextDue} the first charge after this month (both null when none).
     * {@code paidOn} and {@code paidAmount} are the booking date and the amount of the transaction that marked it paid (both null
     * unless a match did, and not when the owner ticked it).
     */
    record Row(long id, String name, String amount, String rhythmKey, String firstDue, String counterparty, String account,
            String status, String expected, String nextDue, String endedOn, boolean overdue, String paidOn, String paidAmount) {

        public boolean hasPayment() {
            return paidOn != null;
        }

        public boolean hasCounterparty() {
            return counterparty != null;
        }

        public boolean hasAccount() {
            return account != null;
        }

        public boolean hasExpected() {
            return expected != null;
        }

        public boolean hasNextDue() {
            return nextDue != null;
        }

        public boolean hasEndedOn() {
            return endedOn != null;
        }

        /** It can be ticked or unticked: a charge this month and still running. */
        public boolean tickable() {
            return status.equals("paid") || status.equals("still_to_come");
        }

        public boolean paid() {
            return status.equals("paid");
        }

        public boolean paused() {
            return status.equals("paused");
        }

        public boolean ended() {
            return status.equals("ended");
        }
    }

    /** The fixed costs of one scope: totals, then the running ones, the paused ones and the ended ones. */
    record Group(String slug, String labelKey, String perMonth, String perYear, List<Row> running, List<Row> paused, List<Row> ended,
            Basis basis) {

        public boolean hasBasis() {
            return basis != null;
        }

        public boolean isEmpty() {
            return running.isEmpty() && paused.isEmpty() && ended.isEmpty();
        }

        public boolean hasRunning() {
            return !running.isEmpty();
        }
    }

    /** What is still to come this month in one scope of the overview. */
    record Coming(String slug, String labelKey, String total, List<Row> rows, Basis basis) {

        public boolean hasBasis() {
            return basis != null;
        }
    }

    /**
     * How old the transactions are that "still to come" rests on (hard rule 9): the delivery of the least current account involved
     * ({@code delivery} is null when one of them never had one) and, when one of them is stale, why.
     */
    record Basis(AccountViews.DeliveryView delivery, AccountViews.StaleView stale) {
    }

    static Row row(FixedCostMonth month, LongFunction<String> accountName) {
        FixedCost cost = month.cost();
        return new Row(cost.id(), cost.name(), cost.amount().format(), "rhythm." + cost.rhythm().code(), cost.firstDue().toString(),
                cost.counterpartyName(), cost.accountId() == null ? null : accountName.apply(cost.accountId()),
                month.status().name().toLowerCase(Locale.ROOT), month.expected().map(Object::toString).orElse(null),
                month.nextDue().map(Object::toString).orElse(null), cost.endedOn() == null ? null : cost.endedOn().toString(), month.overdue(),
                month.payment().map(t -> t.bookingDate().toString()).orElse(null), month.payment().map(FixedCostViews::paid).orElse(null));
    }

    /** What was paid, as a positive amount: the transaction is spending, so its amount is negative. */
    private static String paid(Transaction payment) {
        return payment.amount().negate().format();
    }

    /** The age and the stale mark of what a summary rests on; null when none of its fixed costs can be matched. */
    static Basis basis(FixedCostSummary summary, ZoneId zone, LocalDate today, Map<Long, Freshness.Stale> stale) {
        return summary.basis().map(basis -> {
            Freshness.Stale first = basis.accountIds().stream().sorted().map(stale::get).filter(java.util.Objects::nonNull).findFirst()
                    .orElse(null);
            return new Basis(basis.oldestDelivery().map(at -> AccountViews.delivery(at, zone, today)).orElse(null), AccountViews.stale(first));
        }).orElse(null);
    }

    static Group group(Scope scope, FixedCostSummary summary, List<FixedCostMonth> months, LongFunction<String> accountName, Basis basis) {
        List<Row> running = months.stream().filter(m -> m.status() != FixedCostMonth.Status.PAUSED
                && m.status() != FixedCostMonth.Status.ENDED).map(m -> row(m, accountName)).toList();
        List<Row> paused = months.stream().filter(m -> m.status() == FixedCostMonth.Status.PAUSED).map(m -> row(m, accountName)).toList();
        List<Row> ended = months.stream().filter(m -> m.status() == FixedCostMonth.Status.ENDED).map(m -> row(m, accountName)).toList();
        return new Group(scope.code(), AccountViews.scopeKey(scope), summary.perMonth().format(), summary.perYear().format(), running,
                paused, ended, basis);
    }

    /** One scope of the overview: the total still to come and each one with its expected date; null when nothing is to come. */
    static Coming coming(Scope scope, FixedCostSummary summary, LongFunction<String> accountName, Basis basis) {
        if (!summary.hasStillToCome()) {
            return null;
        }
        return new Coming(scope.code(), AccountViews.scopeKey(scope), summary.stillToComeTotal().format(),
                summary.stillToCome().stream().map(m -> row(m, accountName)).toList(), basis);
    }
}
