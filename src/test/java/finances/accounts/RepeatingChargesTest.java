package finances.accounts;

import static org.assertj.core.api.Assertions.assertThat;

import finances.accounts.RepeatingCharges.Charge;
import finances.accounts.RepeatingCharges.Run;
import finances.money.Rhythm;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

/** The detection rule on plain values, without a database or the web layer. Fictional amounts only. */
class RepeatingChargesTest {

    private static final LocalDate TODAY = LocalDate.of(2026, 12, 20);

    private static long nextId = 1;

    private static Charge charge(String day, long cents) {
        return new Charge(nextId++, LocalDate.parse(day), cents);
    }

    private static Optional<Run> find(Charge... charges) {
        return RepeatingCharges.find(List.of(charges), TODAY);
    }

    @Test
    void threeMonthlyChargesAreAMonthlyRunAndTwoAreNot() {
        assertThat(find(charge("2026-10-03", 2000), charge("2026-11-03", 2000))).isEmpty();

        Run run = find(charge("2026-10-03", 2000), charge("2026-11-03", 2000), charge("2026-12-03", 2000)).orElseThrow();
        assertThat(run.rhythm()).isEqualTo(Rhythm.MONTHLY);
        assertThat(run.charges()).hasSize(3);
        assertThat(run.newest().booked()).isEqualTo(LocalDate.parse("2026-12-03"));
    }

    @Test
    void theRunStartsAtTheNewestChargeAndWalksBackUntilOneDoesNotFit() {
        Run run = find(charge("2026-01-03", 9900), charge("2026-09-03", 2000), charge("2026-10-03", 2000), charge("2026-11-03", 2000),
                charge("2026-12-03", 2000)).orElseThrow();

        assertThat(run.charges()).extracting(Charge::booked).extracting(LocalDate::toString)
                .containsExactly("2026-12-03", "2026-11-03", "2026-10-03", "2026-09-03");
    }

    @ParameterizedTest
    @CsvSource({"TWO_MONTHLY, 2026-04-25, 2026-06-25, 2026-08-25, 2026-10-25", "QUARTERLY, 2026-01-10, 2026-04-10, 2026-07-10, 2026-10-10"})
    void twoMonthlyAndQuarterlyNeedThreeCharges(Rhythm rhythm, String a, String b, String c, String d) {
        assertThat(find(charge(c, 6000), charge(d, 6000))).isEmpty();

        Run run = find(charge(a, 6000), charge(b, 6000), charge(c, 6000), charge(d, 6000)).orElseThrow();
        assertThat(run.rhythm()).isEqualTo(rhythm);
        assertThat(run.charges()).hasSize(4);
    }

    @Test
    void twoQuarterlyChargesGiveNoProposalButTwoHalfYearlyAndTwoYearlyDo() {
        assertThat(find(charge("2026-04-10", 6000), charge("2026-07-10", 6000), charge("2026-10-10", 6000))).isPresent();
        assertThat(find(charge("2026-07-10", 6000), charge("2026-10-10", 6000))).isEmpty();

        assertThat(find(charge("2026-04-10", 6000), charge("2026-10-10", 6000))).hasValueSatisfying(
                run -> assertThat(run.rhythm()).isEqualTo(Rhythm.HALF_YEARLY));
        assertThat(find(charge("2025-12-15", 10000), charge("2026-12-14", 10000))).hasValueSatisfying(
                run -> assertThat(run.rhythm()).isEqualTo(Rhythm.YEARLY));
    }

    @ParameterizedTest
    @CsvSource({"2026-11-10, true", "2026-11-17, true", "2026-11-18, false", "2026-11-03, true", "2026-11-02, false"})
    void aGapOfTheRhythmWithinSevenDaysBothEndsIncludedFits(String older, boolean fits) {
        // The newest charge is on 10 December; one month before is 10 November.
        assertThat(find(charge("2026-10-10", 2000), charge(older, 2000), charge("2026-12-10", 2000)).isPresent()).as(older).isEqualTo(fits);
    }

    @ParameterizedTest
    @CsvSource({"2000, true", "2200, true", "1800, true", "2201, false", "1799, false"})
    void anAmountExactlyATenthFromTheNewestFitsAndOneCentMoreEndsTheRun(long cents, boolean fits) {
        assertThat(find(charge("2026-10-03", cents), charge("2026-11-03", cents), charge("2026-12-03", 2000)).isPresent()).isEqualTo(fits);
    }

    @Test
    void aSecondChargeInsideAGapEndsTheRunSoWeeklyGroceriesAreNeverProposed() {
        List<Charge> groceries = new ArrayList<>();
        LocalDate day = LocalDate.parse("2026-01-03");
        long cents = 1234;
        while (day.isBefore(TODAY)) {
            groceries.add(charge(day.toString(), cents));
            cents = cents == 1234 ? 2345 : 1234;
            day = day.plusWeeks(1);
        }
        assertThat(RepeatingCharges.find(groceries, TODAY)).isEmpty();

        assertThat(find(charge("2026-10-03", 2000), charge("2026-10-20", 2000), charge("2026-11-03", 2000), charge("2026-12-03", 2000)))
                .as("a charge between 3 Nov and 3 Dec's older neighbour breaks the walk at 20 Oct; 3 Nov and 3 Dec remain: two charges")
                .isEmpty();
    }

    @Test
    void aGroupWhoseNextChargeIsMoreThanAWindowInThePastIsNotRunningAnyMore() {
        // Monthly, newest on 12 Nov: the next one was due on 12 Dec, its window ended on 19 Dec; today is 20 Dec.
        assertThat(find(charge("2026-09-12", 2000), charge("2026-10-12", 2000), charge("2026-11-12", 2000))).isEmpty();
        assertThat(find(charge("2026-09-13", 2000), charge("2026-10-13", 2000), charge("2026-11-13", 2000))).isPresent();
    }

    @Test
    void theEndOfAMonthFollowsTheShorterMonth() {
        Run run = find(charge("2026-10-31", 2000), charge("2026-11-30", 2000), charge("2026-12-31", 2000)).orElseThrow();
        assertThat(run.rhythm()).isEqualTo(Rhythm.MONTHLY);
    }

    @Test
    void noChargesGiveNothing() {
        assertThat(RepeatingCharges.find(List.of(), TODAY)).isEmpty();
    }
}
