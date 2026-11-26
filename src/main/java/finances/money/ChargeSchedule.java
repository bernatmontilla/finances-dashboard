package finances.money;

import java.time.LocalDate;
import java.time.YearMonth;
import java.time.temporal.ChronoUnit;
import java.util.Optional;

/**
 * When a fixed cost is charged (issue #21), the only place where that is worked out. Pure rules on plain values: nothing reads
 * a clock or a database, nothing logs.
 *
 * <p>Charge {@code k} falls on {@code firstDue.plusMonths(k * rhythm.months())} for {@code k >= 0}, always counted from
 * {@code firstDue} and never from the previous charge: a charge on the 31st falls on the last day of a short month and comes
 * back to the 31st afterwards (31 Jan, 29 Feb, 31 Mar), and a yearly 29 Feb falls on 28 Feb in a year without one. Nothing is
 * charged after {@code endedOn} (null when it never ended).
 */
public record ChargeSchedule(LocalDate firstDue, Rhythm rhythm, LocalDate endedOn) {

    /** The charge that falls in this month, if any: not before the month of the first charge, and on the rhythm's months. */
    public Optional<LocalDate> chargeIn(YearMonth month) {
        long gap = ChronoUnit.MONTHS.between(YearMonth.from(firstDue), month);
        if (gap < 0 || gap % rhythm.months() != 0) {
            return Optional.empty();
        }
        return survives(firstDue.plusMonths(gap));
    }

    /** The first charge on or after {@code day}. */
    public Optional<LocalDate> nextOnOrAfter(LocalDate day) {
        long gap = ChronoUnit.MONTHS.between(YearMonth.from(firstDue), YearMonth.from(day));
        long k = Math.max(0, Math.floorDiv(gap, rhythm.months()));
        LocalDate charge = firstDue.plusMonths(k * rhythm.months());
        // The charge of round k is in a month up to the month of `day`; if it is earlier in that month, the next round follows.
        while (charge.isBefore(day)) {
            k++;
            charge = firstDue.plusMonths(k * rhythm.months());
        }
        return survives(charge);
    }

    private Optional<LocalDate> survives(LocalDate charge) {
        return endedOn != null && charge.isAfter(endedOn) ? Optional.empty() : Optional.of(charge);
    }
}
