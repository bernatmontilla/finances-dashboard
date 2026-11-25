package finances.accounts;

import java.time.LocalDate;

/**
 * One transaction as a source delivers it (bank sync, CSV import), before it is stored. The amount
 * is signed whole cents, spending negative, with its currency: a currency other than EUR is not
 * refused here but by the delivery, which counts it and stores the rest of the batch.
 *
 * @param reference the source's own reference; null or blank when it has none
 * @param raw the record as delivered, kept next to the normalised fields
 */
public record DeliveredTransaction(
        LocalDate bookingDate,
        LocalDate valueDate,
        long amountCents,
        String currency,
        String counterpartyName,
        String counterpartyIban,
        String description,
        TransactionStatus status,
        String reference,
        String raw) {

    public DeliveredTransaction {
        if (bookingDate == null || status == null) {
            throw new TransactionException(TransactionException.Reason.INCOMPLETE);
        }
    }
}
