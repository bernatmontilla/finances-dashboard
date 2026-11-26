package finances.web;

import finances.accounts.FixedCost;
import finances.accounts.FixedCostMonth;
import finances.accounts.FixedCostSummary;
import finances.accounts.Scope;
import java.util.List;
import java.util.Locale;
import java.util.function.LongFunction;

/**
 * What the fixed costs page and the overview show of fixed costs (issue #21): the figures come from {@code FixedCostService} and
 * {@code finances.money.ChargeSchedule}; this class only formats them and picks message keys. It adds, subtracts and compares no
 * amounts, and nothing here logs.
 */
final class FixedCostViews {

    private FixedCostViews() {
    }

    /**
     * One fixed cost. {@code status} is the lower-case status code (paid, still_to_come, not_this_month, paused, ended);
     * {@code expected} is this month's charge date and {@code nextDue} the first charge after this month (both null when none).
     */
    record Row(long id, String name, String amount, String rhythmKey, String firstDue, String counterparty, String account,
            String status, String expected, String nextDue, String endedOn, boolean overdue) {

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
    record Group(String slug, String labelKey, String perMonth, String perYear, List<Row> running, List<Row> paused, List<Row> ended) {

        public boolean isEmpty() {
            return running.isEmpty() && paused.isEmpty() && ended.isEmpty();
        }

        public boolean hasRunning() {
            return !running.isEmpty();
        }
    }

    /** What is still to come this month in one scope of the overview. */
    record Coming(String slug, String labelKey, String total, List<Row> rows) {
    }

    static Row row(FixedCostMonth month, LongFunction<String> accountName) {
        FixedCost cost = month.cost();
        return new Row(cost.id(), cost.name(), cost.amount().format(), "rhythm." + cost.rhythm().code(), cost.firstDue().toString(),
                cost.counterpartyName(), cost.accountId() == null ? null : accountName.apply(cost.accountId()),
                month.status().name().toLowerCase(Locale.ROOT), month.expected().map(Object::toString).orElse(null),
                month.nextDue().map(Object::toString).orElse(null), cost.endedOn() == null ? null : cost.endedOn().toString(), month.overdue());
    }

    static Group group(Scope scope, FixedCostSummary summary, List<FixedCostMonth> months, LongFunction<String> accountName) {
        List<Row> running = months.stream().filter(m -> m.status() != FixedCostMonth.Status.PAUSED
                && m.status() != FixedCostMonth.Status.ENDED).map(m -> row(m, accountName)).toList();
        List<Row> paused = months.stream().filter(m -> m.status() == FixedCostMonth.Status.PAUSED).map(m -> row(m, accountName)).toList();
        List<Row> ended = months.stream().filter(m -> m.status() == FixedCostMonth.Status.ENDED).map(m -> row(m, accountName)).toList();
        return new Group(scope.code(), AccountViews.scopeKey(scope), summary.perMonth().format(), summary.perYear().format(), running,
                paused, ended);
    }

    /** One scope of the overview: the total still to come and each one with its expected date; null when nothing is to come. */
    static Coming coming(Scope scope, FixedCostSummary summary, LongFunction<String> accountName) {
        if (!summary.hasStillToCome()) {
            return null;
        }
        return new Coming(scope.code(), AccountViews.scopeKey(scope), summary.stillToComeTotal().format(),
                summary.stillToCome().stream().map(m -> row(m, accountName)).toList());
    }
}
