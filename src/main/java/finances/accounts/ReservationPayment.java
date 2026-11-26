package finances.accounts;

import finances.money.Money;

/**
 * The payment a closed reservation is linked to (issue #51). {@code difference} is paid minus reserved: positive means more was
 * paid than reserved, zero means as reserved. Nothing is adjusted by it.
 */
public record ReservationPayment(Transaction transaction, Money paid, Money difference) {

    /** How far the payment is from what was reserved, without a sign: the page says "more" or "less" in words. */
    public Money gap() {
        return difference.isNegative() ? difference.negate() : difference;
    }

    public boolean isMore() {
        return difference.cents() > 0;
    }

    public boolean isLess() {
        return difference.cents() < 0;
    }
}
