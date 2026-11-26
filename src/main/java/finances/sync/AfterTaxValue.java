package finances.sync;

import finances.money.Money;
import java.time.Instant;
import java.util.List;

/**
 * One value of the after-tax source: what the source calculated (as of a moment) and when it was received. The gross
 * balance and the reserved lines are shown in the build-up only; nothing is calculated with them. {@code mismatch}
 * says that gross minus the reserved lines is not the available amount.
 */
public record AfterTaxValue(long id, Instant receivedAt, Instant asOf, long availableCents, Long grossBalanceCents,
        boolean mismatch, List<Reserved> reserved) {

    /** A line of what the source held back. */
    public record Reserved(String label, long amountCents) {
        public Money amount() {
            return Money.eur(amountCents);
        }
    }

    public AfterTaxValue {
        reserved = List.copyOf(reserved);
    }

    public Money available() {
        return Money.eur(availableCents);
    }

    public boolean hasGross() {
        return grossBalanceCents != null;
    }

    public Money gross() {
        return grossBalanceCents == null ? null : Money.eur(grossBalanceCents);
    }

    /** The same answer again: same moment, amounts and lines (when it was received does not matter). */
    boolean sameAnswerAs(AfterTaxValue other) {
        return asOf.equals(other.asOf) && availableCents == other.availableCents
                && java.util.Objects.equals(grossBalanceCents, other.grossBalanceCents) && reserved.equals(other.reserved)
                && mismatch == other.mismatch;
    }
}
