package finances.money;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * The two headline numbers of docs/product/PRODUCT.md and nothing else: no Spring, no database, no clock. Fictional
 * amounts only. Reservations are plain input here; the issue that adds them plugs into the same calculation.
 */
class BuildUpTest {

    private static final Instant NOW = Instant.parse("2026-11-26T12:00:00Z");

    private static BuildUp.Line line(String label, long cents) {
        return new BuildUp.Line(label, Money.eur(cents), NOW, null);
    }

    private static BuildUp.Line noBalance(String label) {
        return new BuildUp.Line(label, null, null, null);
    }

    private static BuildUp.Line stale(String label, long cents, Instant asOf, Freshness.Reason reason) {
        return new BuildUp.Line(label, Money.eur(cents), asOf, new Freshness.Stale(reason, Duration.ofDays(3), null));
    }

    private static BuildUp.Reservation reserved(String label, long cents) {
        return new BuildUp.Reservation(label, Money.eur(cents));
    }

    // ----- private free to spend

    @Test
    void privateIsTheSumOfThePrivateCurrentBalances() {
        BuildUp.Result result = BuildUp.privateFreeToSpend(List.of(line("Everyday", 123_456), line("Second", 10_000)), List.of());

        assertThat(result.shown()).isTrue();
        assertThat(result.total()).isEqualTo(Money.eur(133_456));
        assertThat(result.total().format()).isEqualTo("€1,334.56");
        assertThat(result.lines()).extracting(BuildUp.Line::label).containsExactly("Everyday", "Second");
        assertThat(result.reservations()).isEmpty();
        assertThat(result.reserved()).isEqualTo(Money.zero());
        assertThat(result.stale()).isEmpty();
    }

    @Test
    void anOverdraftCountsAsItIs() {
        BuildUp.Result result = BuildUp.privateFreeToSpend(List.of(line("Everyday", -1_234), line("Second", 10_000)), List.of());

        assertThat(result.total().format()).isEqualTo("€87.66");
    }

    @Test
    void whenEverythingIsNegativeTheTotalIsNegativeAndShown() {
        BuildUp.Result result = BuildUp.privateFreeToSpend(List.of(line("Everyday", -1_234), line("Second", -10_000)), List.of());

        assertThat(result.shown()).isTrue();
        assertThat(result.total().format()).isEqualTo("-€112.34");
        assertThat(result.total().isNegative()).isTrue();
    }

    @Test
    void aZeroBalanceIsAKnownBalanceAndTheTotalIsZero() {
        BuildUp.Result result = BuildUp.privateFreeToSpend(List.of(line("Everyday", 0)), List.of());

        assertThat(result.shown()).isTrue();
        assertThat(result.total().isZero()).isTrue();
    }

    @Test
    void withoutAPrivateCurrentAccountTheNumberIsNotShown() {
        BuildUp.Result result = BuildUp.privateFreeToSpend(List.of(), List.of());

        assertThat(result.shown()).isFalse();
        assertThat(result.total()).as("never zero for unknown").isNull();
        assertThat(result.hidden()).isEqualTo(BuildUp.Hidden.NO_PRIVATE_CURRENT_ACCOUNT);
    }

    @Test
    void aReservationWithoutAnAccountIsNotANumber() {
        BuildUp.Result result = BuildUp.privateFreeToSpend(List.of(), List.of(reserved("Insurance", 10_000)));

        assertThat(result.hidden()).isEqualTo(BuildUp.Hidden.NO_PRIVATE_CURRENT_ACCOUNT);
    }

    @Test
    void oneCountedAccountWithoutABalanceHidesTheNumberAndNamesTheAccountNeverATotalOfTheRest() {
        BuildUp.Result result = BuildUp.privateFreeToSpend(List.of(line("Everyday", 123_456), noBalance("Second"), noBalance("Third")),
                List.of());

        assertThat(result.shown()).isFalse();
        assertThat(result.total()).isNull();
        assertThat(result.hidden()).isEqualTo(BuildUp.Hidden.NO_BALANCE_YET);
        assertThat(result.hiddenLabels()).containsExactly("Second", "Third");
    }

    @Test
    void aSumThatDoesNotFitIsRefusedNeverWrappedAround() {
        long half = Long.MAX_VALUE / 2 + 1;

        BuildUp.Result result = BuildUp.privateFreeToSpend(List.of(line("Everyday", half), line("Second", half)), List.of());

        assertThat(result.shown()).isFalse();
        assertThat(result.hidden()).isEqualTo(BuildUp.Hidden.TOO_LARGE);
    }

    @Test
    void aTotalThatReachesTheSmallestLongIsRefusedBecauseItCannotBeShown() {
        BuildUp.Result result = BuildUp.privateFreeToSpend(List.of(line("Everyday", -Long.MAX_VALUE)), List.of(reserved("Holiday", 1)));

        assertThat(result.hidden()).isEqualTo(BuildUp.Hidden.TOO_LARGE);
    }

    @Test
    void aReservationThatPushesBelowTheSmallestLongIsRefusedToo() {
        BuildUp.Result result = BuildUp.privateFreeToSpend(List.of(line("Everyday", -Long.MAX_VALUE)), List.of(reserved("Holiday", 5)));

        assertThat(result.hidden()).isEqualTo(BuildUp.Hidden.TOO_LARGE);
    }

    @Test
    void reservationsReduceTheTotalAndAreListedBeforeIt() {
        BuildUp.Result result = BuildUp.privateFreeToSpend(List.of(line("Everyday", 123_456)),
                List.of(reserved("Insurance", 10_000)));

        assertThat(result.total().format()).isEqualTo("€1,134.56");
        assertThat(result.reservations()).extracting(BuildUp.Reservation::label).containsExactly("Insurance");
        assertThat(result.reserved().format()).isEqualTo("€100.00");
    }

    @Test
    void reservedMoreThanThereIsGivesANegativeTotalNothingIsCapped() {
        BuildUp.Result result = BuildUp.privateFreeToSpend(List.of(line("Everyday", 5_000)),
                List.of(reserved("Insurance", 10_000), reserved("Holiday", 2_000)));

        assertThat(result.total().format()).isEqualTo("-€70.00");
        assertThat(result.reserved().format()).isEqualTo("€120.00");
    }

    @Test
    void aReservationCannotBeNegative() {
        assertThatThrownBy(() -> reserved("Insurance", -1)).isInstanceOf(IllegalArgumentException.class);
    }

    // ----- stale parts

    @Test
    void aStaleLineIsStillCountedAndMakesTheResultStaleNamingThatPart() {
        BuildUp.Line old = stale("Second", 10_000, NOW.minus(Duration.ofDays(20)), Freshness.Reason.NOT_UPDATED);

        BuildUp.Result result = BuildUp.privateFreeToSpend(List.of(line("Everyday", 123_456), old), List.of());

        assertThat(result.total().format()).isEqualTo("€1,334.56");
        assertThat(result.isStale()).isTrue();
        assertThat(result.stale()).containsExactly(old);
    }

    @Test
    void twoStaleLinesAreBothListedInBuildUpOrder() {
        BuildUp.Line first = stale("Everyday", 100, NOW.minus(Duration.ofDays(3)), Freshness.Reason.SYNC_OLD);
        BuildUp.Line second = stale("Second", 200, NOW.minus(Duration.ofDays(20)), Freshness.Reason.NOT_UPDATED);

        BuildUp.Result result = BuildUp.privateFreeToSpend(List.of(first, line("Fresh", 300), second), List.of());

        assertThat(result.stale()).containsExactly(first, second);
    }

    @Test
    void noStaleLineMeansNotStale() {
        assertThat(BuildUp.privateFreeToSpend(List.of(line("Everyday", 100)), List.of()).isStale()).isFalse();
    }

    @Test
    void theAgeOfTheResultIsTheAgeOfItsOldestCountedLine() {
        BuildUp.Line old = new BuildUp.Line("Second", Money.eur(200), NOW.minus(Duration.ofDays(5)), null);

        BuildUp.Result result = BuildUp.privateFreeToSpend(List.of(line("Everyday", 100), old, line("Third", 300)), List.of());

        assertThat(result.oldest()).isSameAs(old);
    }

    @Test
    void whenNoLineKnowsItsMomentTheFirstLineStandsForTheAge() {
        BuildUp.Line first = new BuildUp.Line("Everyday", Money.eur(100), null, null);

        assertThat(BuildUp.privateFreeToSpend(List.of(first, new BuildUp.Line("Second", Money.eur(1), null, null)), List.of()).oldest())
                .isSameAs(first);
    }

    // ----- business available

    @Test
    void businessIsTheAfterTaxAmountItselfWhenNothingIsReserved() {
        BuildUp.Result result = BuildUp.businessAvailable(line("After tax", 1_234_500), List.of());

        assertThat(result.total().format()).isEqualTo("€12,345.00");
        assertThat(result.lines()).extracting(BuildUp.Line::label).containsExactly("After tax");
    }

    @Test
    void aNegativeAfterTaxAmountIsShownAsNegative() {
        BuildUp.Result result = BuildUp.businessAvailable(line("After tax", -10_000), List.of());

        assertThat(result.shown()).isTrue();
        assertThat(result.total().format()).isEqualTo("-€100.00");
    }

    @Test
    void withoutAValueTheBusinessNumberIsNotShown() {
        BuildUp.Result result = BuildUp.businessAvailable(null, List.of(reserved("Equipment", 100)));

        assertThat(result.shown()).isFalse();
        assertThat(result.total()).isNull();
        assertThat(result.hidden()).isEqualTo(BuildUp.Hidden.NO_VALUE_YET);
    }

    @Test
    void businessReservationsReduceItToo() {
        BuildUp.Result result = BuildUp.businessAvailable(line("After tax", 1_234_500), List.of(reserved("Equipment", 100_000)));

        assertThat(result.total().format()).isEqualTo("€11,345.00");
    }

    @Test
    void aStaleAfterTaxValueMakesBusinessStale() {
        BuildUp.Line old = stale("After tax", 1_234_500, NOW.minus(Duration.ofDays(5)), Freshness.Reason.SOURCE_OLD);

        assertThat(BuildUp.businessAvailable(old, List.of()).stale()).containsExactly(old);
    }

    @Test
    void whenNoSourceIsConfiguredThereIsNoNumberAndTheReasonSaysSo() {
        BuildUp.Result result = BuildUp.noSource();

        assertThat(result.shown()).isFalse();
        assertThat(result.hidden()).isEqualTo(BuildUp.Hidden.NO_SOURCE);
    }

    @Test
    void theResultOfOneNumberHasNothingToAddToTheOtherOne() {
        // The two definitions never meet: a result offers no way to be combined with another.
        assertThat(BuildUp.Result.class.getMethods()).extracting(java.lang.reflect.Method::getName)
                .noneMatch(name -> name.equals("plus") || name.equals("combine") || name.equals("add") || name.equals("merge"));
    }
}
