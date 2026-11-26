package finances.accounts;

import finances.money.Money;
import java.util.Optional;

/** An open reservation as of today: what is reserved so far, what to set aside per month (none once due), and whether it is overdue. */
public record ReservationStanding(Reservation reservation, Money reserved, Optional<Money> perMonth, boolean overdue) {
}
