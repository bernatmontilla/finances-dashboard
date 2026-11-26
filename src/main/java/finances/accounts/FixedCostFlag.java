package finances.accounts;

import finances.money.Money;
import java.time.LocalDate;
import java.util.Optional;

/**
 * Something about a running fixed cost that the owner should look at (issue #22). It is worked out when read and never stored.
 * {@code month} is the month of the occurrence's expected date and {@code expected} that date. For a price change, {@code transactionId}
 * is the booked charge, {@code charged} its amount as a positive {@link Money} and {@code booked} its booking date (hard rule 9);
 * for a missing charge all three are empty.
 */
public record FixedCostFlag(Kind kind, FixedCost cost, java.time.YearMonth month, LocalDate expected, Optional<Long> transactionId,
        Optional<Money> charged, Optional<LocalDate> booked) {

    public enum Kind {
        /** The latest charge differs from the stored amount beyond the matching tolerance. */
        PRICE_CHANGE,
        /** The expected charge did not arrive within the window around its date. */
        MISSING
    }

    public boolean isPriceChange() {
        return kind == Kind.PRICE_CHANGE;
    }

    public boolean isMissing() {
        return kind == Kind.MISSING;
    }
}
