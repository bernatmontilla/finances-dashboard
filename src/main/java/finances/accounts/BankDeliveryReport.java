package finances.accounts;

/**
 * What a bank sync's delivery did: the usual report, how many pending transactions the bank no longer
 * reports were removed, and how many were kept and marked because they hold a note of the owner.
 */
public record BankDeliveryReport(DeliveryReport delivered, int pendingRemoved, int pendingKept) {
}
