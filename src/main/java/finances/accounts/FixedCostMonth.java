package finances.accounts;

import finances.money.Money;
import java.time.LocalDate;
import java.util.Optional;

/**
 * One fixed cost as it stands this month (issue #21). {@code expected} is this month's charge date, empty when it has none (a
 * yearly one in another month, one that has not started, one whose end date falls before its charge, one that ended). {@code nextDue} is the first charge
 * after this month, empty when paused, ended or never charged again. {@code overdue} says that it is still to come although its
 * expected date has passed: nothing says it was paid (nothing is matched against transactions yet), so the owner is asked to tick it.
 */
public record FixedCostMonth(FixedCost cost, Status status, Optional<LocalDate> expected, Optional<LocalDate> nextDue,
        boolean overdue) {

    public enum Status {
        /** It has a charge this month and it was ticked as paid. */
        PAID,
        /** It has a charge this month and nothing says it was paid; a charge date that has passed counts as well. */
        STILL_TO_COME,
        /** It runs, but is charged in another month. */
        NOT_THIS_MONTH,
        PAUSED,
        /** It ended on or before today: listed, read-only, never to come. */
        ENDED
    }

    public boolean isStillToCome() {
        return status == Status.STILL_TO_COME;
    }

    public Money amount() {
        return cost.amount();
    }
}
