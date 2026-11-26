package finances.accounts;

import java.time.LocalDate;

/**
 * One transaction as a source delivers it (bank sync, CSV import), before it is stored. The amount
 * is signed whole cents, spending negative, with its currency: a currency other than EUR is not
 * refused here but by the delivery, which counts it and stores the rest of the batch.
 *
 * @param reference the source's own reference; null or blank when it has none
 * @param raw the record as delivered, kept next to the normalised fields
 * @param datedByFetch true for a pending bank transaction the bank gave no date at all, so that {@code bookingDate} is the
 *     day it was fetched (or first seen), not a date the bank stated; only such a row is also taken over by a booked
 *     transaction up to {@link TransactionService#PENDING_TAKEOVER_DAYS} days dated <em>earlier</em>
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
        String raw,
        boolean datedByFetch) {

    public DeliveredTransaction(LocalDate bookingDate, LocalDate valueDate, long amountCents, String currency,
            String counterpartyName, String counterpartyIban, String description, TransactionStatus status, String reference,
            String raw) {
        this(bookingDate, valueDate, amountCents, currency, counterpartyName, counterpartyIban, description, status, reference,
                raw, false);
    }

    public DeliveredTransaction {
        if (bookingDate == null || status == null) {
            throw new TransactionException(TransactionException.Reason.INCOMPLETE);
        }
    }
}
