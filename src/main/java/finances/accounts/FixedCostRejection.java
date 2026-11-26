package finances.accounts;

import finances.money.Money;
import java.time.Instant;

/**
 * "Not a fixed cost" (issue #71): what the owner rejected, kept until the owner undoes it. A proposal of the same scope and
 * counterparty whose amount is within the matching tolerance ({@link ReservationService#amountClose}) of {@code amount} is hidden.
 */
public record FixedCostRejection(long id, Scope scope, String name, String counterpartyIban, String nameKey, Money amount,
        Instant rejectedAt) {
}
