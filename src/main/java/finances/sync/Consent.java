package finances.sync;

import java.time.Instant;
import java.util.List;

/**
 * One authorisation at a bank. The session id is a secret and is not part of this: it lives in a file
 * ({@link SessionFiles}).
 *
 * @param requiredPsuHeaders the headers the bank wants on a fetch the user started; empty when it lists none
 * @param sessionTag the random value the consent's session file must carry; null for a consent from before tags
 */
public record Consent(long id, String bankName, String bankCountry, List<String> requiredPsuHeaders, Instant createdAt,
        Instant validUntil, ConsentStatus status, String sessionTag) {

    public boolean ended() {
        return status != ConsentStatus.AUTHORIZED;
    }
}
