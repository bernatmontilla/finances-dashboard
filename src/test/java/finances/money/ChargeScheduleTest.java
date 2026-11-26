package finances.money;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalDate;
import java.time.YearMonth;
import org.junit.jupiter.api.Test;

/** When a fixed cost is charged: pure rules on dates, no database. */
class ChargeScheduleTest {

    private static ChargeSchedule schedule(String firstDue, Rhythm rhythm) {
        return new ChargeSchedule(LocalDate.parse(firstDue), rhythm, null);
    }

    private static LocalDate date(String day) {
        return LocalDate.parse(day);
    }

    @Test
    void aMonthlyChargeOnThe31stFallsOnTheLastDayOfAShortMonthAndComesBack() {
        ChargeSchedule monthly = schedule("2028-01-31", Rhythm.MONTHLY);

        assertThat(monthly.chargeIn(YearMonth.of(2028, 1))).contains(date("2028-01-31"));
        assertThat(monthly.chargeIn(YearMonth.of(2028, 2))).contains(date("2028-02-29"));
        assertThat(monthly.chargeIn(YearMonth.of(2028, 3))).contains(date("2028-03-31"));
        assertThat(monthly.chargeIn(YearMonth.of(2028, 4))).contains(date("2028-04-30"));
        assertThat(monthly.chargeIn(YearMonth.of(2029, 2))).contains(date("2029-02-28"));
    }

    @Test
    void aYearlyChargeOnTheLeapDayFallsOnThe28thInOtherYears() {
        ChargeSchedule yearly = schedule("2028-02-29", Rhythm.YEARLY);

        assertThat(yearly.chargeIn(YearMonth.of(2028, 2))).contains(date("2028-02-29"));
        assertThat(yearly.chargeIn(YearMonth.of(2029, 2))).contains(date("2029-02-28"));
        assertThat(yearly.chargeIn(YearMonth.of(2032, 2))).contains(date("2032-02-29"));
        assertThat(yearly.chargeIn(YearMonth.of(2029, 3))).isEmpty();
    }

    @Test
    void aQuarterlyChargeFallsEveryThirdMonthFromTheFirstOne() {
        ChargeSchedule quarterly = schedule("2026-11-15", Rhythm.QUARTERLY);

        assertThat(quarterly.chargeIn(YearMonth.of(2026, 11))).contains(date("2026-11-15"));
        assertThat(quarterly.chargeIn(YearMonth.of(2026, 12))).isEmpty();
        assertThat(quarterly.chargeIn(YearMonth.of(2027, 1))).isEmpty();
        assertThat(quarterly.chargeIn(YearMonth.of(2027, 2))).contains(date("2027-02-15"));
        assertThat(quarterly.chargeIn(YearMonth.of(2027, 5))).contains(date("2027-05-15"));
    }

    @Test
    void aTwoMonthlyChargeFallsEveryOtherMonth() {
        ChargeSchedule twoMonthly = schedule("2026-01-10", Rhythm.TWO_MONTHLY);

        assertThat(twoMonthly.chargeIn(YearMonth.of(2026, 2))).isEmpty();
        assertThat(twoMonthly.chargeIn(YearMonth.of(2026, 3))).contains(date("2026-03-10"));
        assertThat(twoMonthly.chargeIn(YearMonth.of(2027, 1))).contains(date("2027-01-10"));
    }

    @Test
    void aHalfYearlyChargeFallsTwiceAYear() {
        ChargeSchedule halfYearly = schedule("2026-04-01", Rhythm.HALF_YEARLY);

        assertThat(halfYearly.chargeIn(YearMonth.of(2026, 10))).contains(date("2026-10-01"));
        assertThat(halfYearly.chargeIn(YearMonth.of(2027, 4))).contains(date("2027-04-01"));
        assertThat(halfYearly.chargeIn(YearMonth.of(2026, 7))).isEmpty();
    }

    @Test
    void nothingIsChargedBeforeTheMonthOfTheFirstChargeOrAfterTheEndDate() {
        assertThat(schedule("2026-06-20", Rhythm.MONTHLY).chargeIn(YearMonth.of(2026, 5))).isEmpty();
        assertThat(schedule("2026-06-20", Rhythm.MONTHLY).chargeIn(YearMonth.of(2026, 6))).contains(date("2026-06-20"));

        ChargeSchedule ended = new ChargeSchedule(date("2026-01-15"), Rhythm.MONTHLY, date("2026-06-10"));
        assertThat(ended.chargeIn(YearMonth.of(2026, 5))).contains(date("2026-05-15"));
        assertThat(ended.chargeIn(YearMonth.of(2026, 6))).as("the end date is before the 15th").isEmpty();
        assertThat(ended.nextOnOrAfter(date("2026-06-01"))).isEmpty();
        ChargeSchedule endedLater = new ChargeSchedule(date("2026-01-15"), Rhythm.MONTHLY, date("2026-06-20"));
        assertThat(endedLater.chargeIn(YearMonth.of(2026, 6))).contains(date("2026-06-15"));
    }

    @Test
    void theNextChargeIsFoundAcrossAYearEnd() {
        ChargeSchedule monthly = schedule("2026-01-05", Rhythm.MONTHLY);
        assertThat(monthly.nextOnOrAfter(date("2026-12-20"))).contains(date("2027-01-05"));
        assertThat(monthly.nextOnOrAfter(date("2026-12-05"))).contains(date("2026-12-05"));

        ChargeSchedule yearly = schedule("2025-01-10", Rhythm.YEARLY);
        assertThat(yearly.nextOnOrAfter(date("2026-12-31"))).contains(date("2027-01-10"));
        assertThat(yearly.nextOnOrAfter(date("2026-01-11"))).contains(date("2027-01-10"));
        assertThat(yearly.nextOnOrAfter(date("2026-01-10"))).contains(date("2026-01-10"));
    }

    @Test
    void theNextChargeIsTheFirstOneWhenItIsStillAhead() {
        ChargeSchedule quarterly = schedule("2026-09-30", Rhythm.QUARTERLY);

        assertThat(quarterly.nextOnOrAfter(date("2026-01-01"))).contains(date("2026-09-30"));
        assertThat(quarterly.nextOnOrAfter(date("2026-10-01"))).contains(date("2026-12-30"));
    }
}
