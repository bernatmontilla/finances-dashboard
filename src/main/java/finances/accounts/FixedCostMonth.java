package finances.accounts;

import finances.money.Money;
import java.time.LocalDate;
import java.util.Optional;

/**
 * One fixed cost as it stands this month (issues #21 and #63). {@code expected} is this month's charge date, empty when it has none
 * (a yearly one in another month, one that has not started, one whose end date falls before its charge, one that ended).
 * {@code nextDue} is the first charge after this month, empty when paused, ended or never charged again. {@code overdue} says that it
 * is still to come although its expected date has passed: no tick and no matching payment says it was paid, so the owner is asked to
 * tick it.
 *
 * <p>A month is paid by hand ({@code byHand}: the owner ticked it, and a tick wins over everything) or by a match ({@code payment}:
 * the booked transaction that paid it, empty otherwise); a month the owner unticked after a match is still to come.
 */
public record FixedCostMonth(FixedCost cost, Status status, Optional<LocalDate> expected, Optional<LocalDate> nextDue,
        boolean overdue, Optional<Transaction> payment, boolean byHand) {

    public enum Status {
        /** It has a charge this month and it was ticked as paid or a matching payment was found. */
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

    /** Paid because a matching booked transaction was found, and not ticked by the owner. */
    public boolean isPaidByMatch() {
        return status == Status.PAID && payment.isPresent();
    }

    public Money amount() {
        return cost.amount();
    }
}
