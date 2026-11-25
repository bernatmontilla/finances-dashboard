package finances.accounts;

import finances.money.Money;
import java.time.Instant;
import java.time.LocalDate;

/**
 * A stored transaction as listed: the source's fields and, apart from them, the owner's note
 * ({@code null} when there is none). The raw record is not part of it, so a page stays small;
 * {@link TransactionService#raw} reads it.
 *
 * @param noLongerReported a pending transaction the bank stopped reporting, kept because it has a note of the owner
 * @param accountName the name of its account, for pages that list several accounts
 */
public record Transaction(
        long id,
        long accountId,
        String accountName,
        LocalDate bookingDate,
        LocalDate valueDate,
        Money amount,
        String counterpartyName,
        String counterpartyIban,
        String description,
        TransactionStatus status,
        Source source,
        String reference,
        Instant firstSeenAt,
        Instant lastSeenAt,
        boolean noLongerReported,
        String note) {

    public boolean pending() {
        return status == TransactionStatus.PENDING;
    }
}
