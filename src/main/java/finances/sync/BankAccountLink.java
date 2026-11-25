package finances.sync;

import java.time.Instant;

/**
 * An account a consent covers, as the bank described it when the session was made. {@code accountId} is
 * null while it is only offered to the owner: nothing becomes an account without a confirmation.
 */
public record BankAccountLink(long id, long consentId, String uid, String identificationHash, String iban, String holderName,
        String details, String currency, String cashAccountType, Long accountId, Instant lastSuccessAt) {

    public boolean linked() {
        return accountId != null;
    }

    /** Only the euro exists (hard rule 8): an account in another currency, or a multi-currency one ({@code XXX}), is not supported. */
    public boolean supported() {
        return "EUR".equals(currency);
    }
}
