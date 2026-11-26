package finances.accounts;

import finances.money.Money;

/**
 * A closed reservation with the payment it was closed against (issue #51). {@code payment}, {@code paid} and {@code difference}
 * are null when it was closed without one. {@code paid} is what left the account (positive); {@code difference} is paid minus
 * reserved: positive when more was paid than reserved, negative when less. Nothing is adjusted by it.
 */
public record ClosedReservation(Reservation reservation, Transaction payment, Money paid, Money difference) {

    public boolean hasPayment() {
        return payment != null;
    }
}
