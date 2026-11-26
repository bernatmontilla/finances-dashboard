package finances.accounts;

import finances.money.Money;

/** A booked payment the owner may close a reservation against (issue #51); {@code likely} when amount and date are close. */
public record ReservationCandidate(Transaction transaction, Money paid, boolean likely) {
}
