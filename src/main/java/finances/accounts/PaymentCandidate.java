package finances.accounts;

/**
 * A booked payment the owner may close a reservation against (issue #51). {@code likely} says that its amount and its date are close
 * to the reservation's: it is decided by {@link ReservationService#paymentCandidates}, never in a view.
 */
public record PaymentCandidate(Transaction transaction, boolean likely) {
}
