package finances.accounts;

/**
 * What one delivery did: stored for the first time, changed, seen before and unchanged, and refused
 * (a currency other than EUR). The four add up to the size of the batch.
 */
public record DeliveryReport(int added, int updated, int known, int refused) {

    public int total() {
        return added + updated + known + refused;
    }
}
