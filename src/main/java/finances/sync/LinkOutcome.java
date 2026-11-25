package finances.sync;

/**
 * What coming back from the bank's authorisation did. {@code empty}: the consent covers no account (none was
 * linked in Enable Banking's control panel beforehand), and nothing was stored. Otherwise the consent is stored;
 * {@code offered} accounts wait for the owner's confirmation, {@code unsupported} are listed as not supported,
 * and {@code reattached} accounts were already linked and now use the new consent (a renewal). {@code firstSync}
 * is the sync that ran at once for the re-attached ones, or null when there was none.
 */
public record LinkOutcome(long consentId, boolean empty, int offered, int unsupported, int reattached, SyncSummary firstSync) {

    static LinkOutcome emptyConsent() {
        return new LinkOutcome(0, true, 0, 0, 0, null);
    }
}
