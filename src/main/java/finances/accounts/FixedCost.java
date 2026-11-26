package finances.accounts;

import finances.money.ChargeSchedule;
import finances.money.Money;
import finances.money.Rhythm;
import java.time.Instant;
import java.time.LocalDate;

/**
 * A fixed cost as stored (issue #21): kept by hand, never changed by a sync, an import or a restore. {@code firstDue} is the first
 * charge and sets the day and the months; {@code pausedAt} is null while it runs; {@code endedOn} is null until the owner ends it
 * (the last day it may be charged). The counterparty, its IBAN and {@code accountId} (the account it leaves from) are optional.
 */
public record FixedCost(long id, String name, Scope scope, Money amount, Rhythm rhythm, LocalDate firstDue, String counterpartyName,
        String counterpartyIban, Long accountId, Instant pausedAt, LocalDate endedOn, Instant createdAt, Instant changedAt) {

    public boolean isPaused() {
        return pausedAt != null;
    }

    /**
     * Ended before {@code today}: it is read-only and no longer counts. {@code endedOn} is the last day it may be charged, so on that
     * day a charge still falls (and can be ticked); an end date still ahead leaves it running.
     */
    public boolean isEnded(LocalDate today) {
        return endedOn != null && endedOn.isBefore(today);
    }

    public ChargeSchedule schedule() {
        return new ChargeSchedule(firstDue, rhythm, endedOn);
    }
}
