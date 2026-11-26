package finances.accounts;

import java.time.Instant;
import java.util.Optional;
import java.util.Set;

/**
 * What the "still to come" of one scope rests on once fixed costs are matched against transactions (issue #63, hard rule 9): the
 * accounts whose transactions decide it, and the oldest of their latest deliveries. {@code oldestDelivery} is empty when one of those
 * accounts never had a delivery. Whether they are stale is {@code FreshnessService}'s to say.
 */
public record FixedCostBasis(Set<Long> accountIds, Optional<Instant> oldestDelivery) {

    public FixedCostBasis {
        accountIds = Set.copyOf(accountIds);
    }
}
