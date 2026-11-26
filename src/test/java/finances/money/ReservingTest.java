package finances.money;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalDate;
import org.junit.jupiter.api.Test;

/** The build-up rule of a reservation (issue #20) on plain values: no clock, no database. Fictional amounts only. */
class ReservingTest {

    private static final LocalDate JAN_1 = LocalDate.of(2026, 1, 1);
    private static final LocalDate DEC_31 = LocalDate.of(2026, 12, 31);
    private static final Money YEAR = Money.eur(120_000);

    private static Money reserved(Money amount, LocalDate due, LocalDate anchor, Money anchorAmount, LocalDate day) {
        return Reserving.reservedOn(amount, due, anchor, anchorAmount, day);
    }

    @Test
    void aOneOffStartsAtZeroAndIsFullFromTheDueDate() {
        assertThat(reserved(YEAR, DEC_31, JAN_1, Money.zero(), JAN_1)).isEqualTo(Money.zero());
        assertThat(reserved(YEAR, DEC_31, JAN_1, Money.zero(), DEC_31)).isEqualTo(YEAR);
        assertThat(reserved(YEAR, DEC_31, JAN_1, Money.zero(), DEC_31.plusDays(40))).isEqualTo(YEAR);
        assertThat(reserved(YEAR, DEC_31, JAN_1, Money.zero(), JAN_1.minusDays(3))).as("before the start").isEqualTo(Money.zero());
    }

    @Test
    void midYearItIsTheEvenShareRoundedUp() {
        LocalDate day = LocalDate.of(2026, 7, 2); // 182 of 364 days
        assertThat(reserved(YEAR, DEC_31, JAN_1, Money.zero(), day)).isEqualTo(Money.eur(Math.ceilDiv(120_000L * 182, 364)));
        assertThat(reserved(YEAR, DEC_31, JAN_1, Money.zero(), day)).isEqualTo(Money.eur(60_000));
    }

    @Test
    void roundingIsUpSoItIsNeverBelowTheEvenShare() {
        Money amount = Money.eur(1_000);
        LocalDate due = JAN_1.plusDays(7);
        for (int d = 1; d < 7; d++) {
            long cents = reserved(amount, due, JAN_1, Money.zero(), JAN_1.plusDays(d)).cents();
            assertThat(cents * 7).as("day " + d).isGreaterThanOrEqualTo(1_000L * d);
            assertThat(cents * 7).as("day " + d).isLessThan(1_000L * d + 7);
        }
        assertThat(reserved(amount, due, JAN_1, Money.zero(), JAN_1.plusDays(1))).isEqualTo(Money.eur(143));
    }

    @Test
    void aHandSetAnchorBuildsOnlyTheRest() {
        Money anchor = Money.eur(60_000);
        assertThat(reserved(YEAR, DEC_31, JAN_1, anchor, JAN_1)).isEqualTo(anchor);
        assertThat(reserved(YEAR, DEC_31, JAN_1, anchor, LocalDate.of(2026, 7, 2))).isEqualTo(Money.eur(90_000));
        assertThat(reserved(YEAR, DEC_31, JAN_1, anchor, DEC_31)).isEqualTo(YEAR);
    }

    @Test
    void perMonthOnTheFirstDayIsATwelfth() {
        assertThat(Reserving.perMonth(YEAR, DEC_31, JAN_1, Money.zero(), JAN_1)).contains(Money.eur(10_000));
    }

    @Test
    void perMonthSpreadsWhatIsLeftOverTheWholeMonthsLeftRoundedUp() {
        // 2026-07-02 to 2026-12-31 is 5 months and 29 days: 6 months; reserved is 60,000, 60,000 is left.
        assertThat(Reserving.perMonth(YEAR, DEC_31, JAN_1, Money.zero(), LocalDate.of(2026, 7, 2))).contains(Money.eur(10_000));
        // Less than a month left still counts as one month.
        assertThat(Reserving.perMonth(Money.eur(10_000), DEC_31, DEC_31.minusDays(10), Money.zero(), DEC_31.minusDays(10)))
                .contains(Money.eur(10_000));
        // Rounded up: 100 cents over 3 months is 34, never 33.
        assertThat(Reserving.perMonth(Money.eur(100), JAN_1.plusMonths(3), JAN_1, Money.zero(), JAN_1)).contains(Money.eur(34));
    }

    @Test
    void perMonthIsNothingToSetAsideWhenAlreadyFull() {
        assertThat(Reserving.perMonth(YEAR, DEC_31, JAN_1, YEAR, JAN_1)).contains(Money.zero());
    }

    @Test
    void onTheDueDateItIsFullAndThereIsNoPerMonthFigure() {
        assertThat(reserved(YEAR, DEC_31, JAN_1, Money.zero(), DEC_31)).isEqualTo(YEAR);
        assertThat(Reserving.perMonth(YEAR, DEC_31, JAN_1, Money.zero(), DEC_31)).isEmpty();
        assertThat(Reserving.perMonth(YEAR, DEC_31, JAN_1, Money.zero(), DEC_31.plusDays(1))).isEmpty();
        // Created with the due date today: fully reserved at once, whatever the anchor says.
        assertThat(reserved(YEAR, JAN_1, JAN_1, Money.zero(), JAN_1)).isEqualTo(YEAR);
    }

    @Test
    void overdueMeansAfterTheDueDateOnly() {
        assertThat(Reserving.overdue(DEC_31, DEC_31)).isFalse();
        assertThat(Reserving.overdue(DEC_31, DEC_31.plusDays(1))).isTrue();
        assertThat(Reserving.overdue(DEC_31, JAN_1)).isFalse();
    }

    @Test
    void theLargestAmountWithTheFurthestDueDateDoesNotOverflow() {
        Money largest = Money.eur(Reserving.MAX_AMOUNT_CENTS);
        LocalDate far = JAN_1.plusYears(Reserving.MAX_YEARS_AHEAD);
        LocalDate day = far.minusDays(1);
        Money reserved = reserved(largest, far, JAN_1, Money.zero(), day);
        assertThat(reserved.cents()).isBetween(0L, largest.cents());
        assertThat(reserved(largest, far, JAN_1, Money.zero(), far)).isEqualTo(largest);
        assertThat(Reserving.perMonth(largest, far, JAN_1, Money.zero(), JAN_1)).isPresent();
    }
}
