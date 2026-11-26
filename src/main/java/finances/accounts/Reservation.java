package finances.accounts;

import finances.money.Money;
import java.time.Instant;
import java.time.LocalDate;

/**
 * A reservation as stored (issue #20). {@code anchorDate} and {@code anchorAmount} say how much was reserved on which day; what
 * is reserved so far on any day is worked out from them by {@link ReservationService} and never stored. {@code followsId} is the
 * round this one follows (yearly reservations), {@code changedAt} is null until the owner edits it, {@code closedAt} is null
 * while it is open.
 */
public record Reservation(long id, String name, Scope scope, Money amount, LocalDate dueDate, boolean yearly,
        LocalDate anchorDate, Money anchorAmount, Long followsId, Instant createdAt, Instant changedAt, Instant closedAt) {

    public boolean isOpen() {
        return closedAt == null;
    }

    public boolean isUntouched() {
        return changedAt == null;
    }
}
