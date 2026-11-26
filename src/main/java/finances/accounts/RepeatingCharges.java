package finances.accounts;

import finances.money.Rhythm;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * The rule that says whether the booked spending to one counterparty repeats like a fixed cost (issue #71). Plain values in, a run or
 * nothing out: no clock, no database, no names. The caller groups the charges by counterparty and scope (what the counterparty is
 * stays {@code FixedCostService.windowCharges}'s rule, issue #63).
 *
 * <p>For each rhythm, monthly first, the run starts at the newest charge and walks back one charge at a time, never skipping one. The
 * next older charge joins when it is booked within {@link FixedCostService#MATCH_DAYS} days (both ends included) of the date one rhythm
 * before the charge that was just taken, and its amount is {@link ReservationService#amountClose close} to the newest amount: the
 * same date window and the same tolerance the matching of a fixed cost uses, so a charge detected here is one that matching accepts.
 * The walk stops at the first charge that does not join; a charge in between (weekly groceries) therefore ends a run. The rhythm fits
 * when the run has enough charges and the group is still running: its next charge, a rhythm after the newest one plus the window,
 * is not already in the past. The first rhythm that fits wins.
 */
final class RepeatingCharges {

    private RepeatingCharges() {
    }

    /** One booked spending: its amount in positive cents. */
    record Charge(long id, LocalDate booked, long cents) {
    }

    /** The charges of a run, newest first, and the rhythm they repeat in. */
    record Run(Rhythm rhythm, List<Charge> charges) {

        Charge newest() {
            return charges.getFirst();
        }
    }

    /** How many charges a run needs: three for a rhythm of up to a quarter, two for half-yearly and yearly (a year is long to wait). */
    static int minimumCharges(Rhythm rhythm) {
        return rhythm.months() >= 6 ? 2 : 3;
    }

    /**
     * @param chargesOldestFirst the group's booked spending ordered by booking date, then id
     * @param today the application's calendar day
     */
    static Optional<Run> find(List<Charge> chargesOldestFirst, LocalDate today) {
        if (chargesOldestFirst.isEmpty()) {
            return Optional.empty();
        }
        Charge newest = chargesOldestFirst.getLast();
        for (Rhythm rhythm : Rhythm.values()) {
            if (newest.booked().plusMonths(rhythm.months()).plusDays(FixedCostService.MATCH_DAYS).isBefore(today)) {
                continue;
            }
            List<Charge> run = new ArrayList<>();
            run.add(newest);
            Charge previous = newest;
            for (int i = chargesOldestFirst.size() - 2; i >= 0; i--) {
                Charge older = chargesOldestFirst.get(i);
                long daysOff = Math.abs(ChronoUnit.DAYS.between(older.booked(), previous.booked().minusMonths(rhythm.months())));
                long gap = ReservationService.amountGap(older.cents(), newest.cents());
                if (daysOff > FixedCostService.MATCH_DAYS || !ReservationService.amountClose(gap, newest.cents())) {
                    break;
                }
                run.add(older);
                previous = older;
            }
            if (run.size() >= minimumCharges(rhythm)) {
                return Optional.of(new Run(rhythm, List.copyOf(run)));
            }
        }
        return Optional.empty();
    }
}
