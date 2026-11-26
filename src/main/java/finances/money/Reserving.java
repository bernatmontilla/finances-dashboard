package finances.money;

import java.time.LocalDate;
import java.time.Period;
import java.time.temporal.ChronoUnit;
import java.util.Optional;

/**
 * The build-up rule of a reservation (issue #20), the only place where "reserved so far" and "to set aside per month" are
 * worked out. Pure rules on plain values: the day is an argument, nothing reads a clock or a database, nothing logs.
 *
 * <p>A reservation has an amount {@code A}, a due date {@code D}, an anchor date {@code S} and an anchor amount {@code R0}
 * ({@code 0 <= R0 <= A}). Reserved so far on day {@code d} is {@code A} from the due date on, {@code R0} up to the anchor date,
 * and between them {@code R0 + ceil((A - R0) * days(S, d) / days(S, D))}: whole cents, rounded up, so the build-up is never below
 * the even share and never shows reserved money as spendable. Amounts are integer cents; the limits below keep the product
 * from overflowing, and it is still multiplied exactly.
 */
public final class Reserving {

    /** €100,000,000.00: the largest amount of one reservation. */
    public static final long MAX_AMOUNT_CENTS = 10_000_000_000L;

    /** A due date is at most this many years from the day it is set. */
    public static final int MAX_YEARS_AHEAD = 50;

    private Reserving() {
    }

    /** What is reserved so far on {@code day}. */
    public static Money reservedOn(Money amount, LocalDate due, LocalDate anchor, Money anchorAmount, LocalDate day) {
        if (!day.isBefore(due)) {
            return amount;
        }
        if (!day.isAfter(anchor)) {
            return anchorAmount;
        }
        // anchor < day < due, so the span is positive.
        long span = ChronoUnit.DAYS.between(anchor, due);
        long elapsed = ChronoUnit.DAYS.between(anchor, day);
        long rest = Math.subtractExact(amount.cents(), anchorAmount.cents());
        long share = Math.ceilDiv(Math.multiplyExact(rest, elapsed), span);
        return Money.eur(Math.addExact(anchorAmount.cents(), share));
    }

    /**
     * What to set aside each month from {@code today} until the due date: what is still missing, over the whole months left
     * (rounded up, at least one). Empty on and after the due date: the reservation is due, not being built up.
     */
    public static Optional<Money> perMonth(Money amount, LocalDate due, LocalDate anchor, Money anchorAmount, LocalDate today) {
        if (!today.isBefore(due)) {
            return Optional.empty();
        }
        long missing = amount.minus(reservedOn(amount, due, anchor, anchorAmount, today)).cents();
        return Optional.of(Money.eur(Math.ceilDiv(missing, monthsLeft(today, due))));
    }

    /** Open and past its due date: it stays fully reserved and is flagged. */
    public static boolean overdue(LocalDate due, LocalDate today) {
        return today.isAfter(due);
    }

    private static long monthsLeft(LocalDate today, LocalDate due) {
        Period left = Period.between(today, due);
        return Math.max(1, left.toTotalMonths() + (left.getDays() > 0 ? 1 : 0));
    }
}
