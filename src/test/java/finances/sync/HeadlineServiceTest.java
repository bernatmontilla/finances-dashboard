package finances.sync;

import static org.assertj.core.api.Assertions.assertThat;

import finances.accounts.Account;
import finances.accounts.AccountSummary;
import finances.accounts.Kind;
import finances.accounts.ReservationService;
import finances.accounts.Scope;
import finances.money.BuildUp;
import finances.money.Freshness;
import finances.money.Money;
import finances.outbound.AfterTaxClient;
import finances.outbound.AfterTaxToken;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Which accounts and which source feed the two headline numbers (issue #19), and that nothing else does. The sums
 * themselves are {@code BuildUpTest}. Fictional data only; the clock is the harness's.
 */
class HeadlineServiceTest {

    private static final LocalDate TODAY = LocalDate.of(2026, 11, 26);

    @TempDir
    Path folder;

    BankHarness h;
    FakeAfterTaxSource source;
    AfterTaxService afterTax;
    HeadlineService headlines;
    ReservationService reservations;

    @BeforeEach
    void open() throws Exception {
        h = new BankHarness(folder.resolve("data"), false);
        source = new FakeAfterTaxSource();
        reservations = new ReservationService(h.jdbc, h.clock, h.accounts);
        afterTax = afterTaxFor(source.url());
        headlines = headlinesFor(afterTax);
    }

    @AfterEach
    void close() throws Exception {
        source.close();
        h.close();
    }

    private AfterTaxService afterTaxFor(String url) {
        AfterTaxClient client = new AfterTaxClient(new AfterTaxToken(h.dataFolder), url, Duration.ofSeconds(5));
        return new AfterTaxService(client, new AfterTaxStore(h.jdbc), Freshness.Limits.defaults(), h.clock, h.manager);
    }

    private HeadlineService headlinesFor(AfterTaxService service) {
        FreshnessService freshness = new FreshnessService(h.accounts, h.status, h.sessions, Freshness.Limits.defaults(), h.clock);
        return new HeadlineService(h.accounts, freshness, service, reservations);
    }

    private Account account(String name, Scope scope, Kind kind, long cents, LocalDate date) {
        Account account = h.accounts.addManual(name, scope, kind, null);
        if (cents != Long.MIN_VALUE) {
            h.accounts.addBalance(account.id(), Money.eur(cents), date);
        }
        return account;
    }

    private Account account(String name, Scope scope, Kind kind, long cents) {
        return account(name, scope, kind, cents, TODAY);
    }

    private static List<String> names(List<AccountSummary> accounts) {
        return accounts.stream().map(s -> s.account().name()).toList();
    }

    // ----- private free to spend

    @Test
    void onlyActivePrivateCurrentAccountsAreCounted() {
        account("Everyday", Scope.PRIVATE, Kind.CURRENT, 123_456);
        account("Second", Scope.PRIVATE, Kind.CURRENT, 10_000);
        account("Rainy day", Scope.PRIVATE, Kind.SAVINGS, 500_000);
        account("Shares", Scope.PRIVATE, Kind.INVESTMENT, 700_000);
        account("Loan", Scope.PRIVATE, Kind.DEBT, 900_000);
        account("Business current", Scope.BUSINESS, Kind.CURRENT, 800_000);

        HeadlineService.PrivateNumber number = headlines.headlines().privateNumber();

        assertThat(number.result().total().format()).isEqualTo("€1,334.56");
        assertThat(names(number.counted())).containsExactly("Everyday", "Second");
        assertThat(number.result().lines()).extracting(BuildUp.Line::label).containsExactly("Everyday", "Second");
    }

    @Test
    void anArchivedPrivateCurrentAccountIsNeitherCountedNorListed() {
        account("Everyday", Scope.PRIVATE, Kind.CURRENT, 123_456);
        Account old = account("Old", Scope.PRIVATE, Kind.CURRENT, 99_900);
        h.accounts.archive(old.id());

        HeadlineService.PrivateNumber number = headlines.headlines().privateNumber();

        assertThat(number.result().total().format()).isEqualTo("€1,234.56");
        assertThat(names(number.counted())).containsExactly("Everyday");
    }

    @Test
    void anAccountWithoutABalanceYetHidesTheNumberAndIsNamed() {
        account("Everyday", Scope.PRIVATE, Kind.CURRENT, 123_456);
        account("New", Scope.PRIVATE, Kind.CURRENT, Long.MIN_VALUE);

        BuildUp.Result result = headlines.headlines().privateNumber().result();

        assertThat(result.hidden()).isEqualTo(BuildUp.Hidden.NO_BALANCE_YET);
        assertThat(result.hiddenLabels()).containsExactly("New");
    }

    @Test
    void withoutAnyPrivateCurrentAccountThereIsNoPrivateNumber() {
        account("Rainy day", Scope.PRIVATE, Kind.SAVINGS, 500_000);

        assertThat(headlines.headlines().privateNumber().result().hidden()).isEqualTo(BuildUp.Hidden.NO_PRIVATE_CURRENT_ACCOUNT);
    }

    @Test
    void anOverdraftIsCountedAsItIs() {
        account("Everyday", Scope.PRIVATE, Kind.CURRENT, -1_234);
        account("Second", Scope.PRIVATE, Kind.CURRENT, 10_000);

        assertThat(headlines.headlines().privateNumber().result().total().format()).isEqualTo("€87.66");
    }

    @Test
    void aStaleCountedAccountMakesThePrivateNumberStaleAndNamesIt() {
        account("Everyday", Scope.PRIVATE, Kind.CURRENT, 123_456);
        Account stale = account("Second", Scope.PRIVATE, Kind.CURRENT, 10_000, TODAY.minusDays(20));

        HeadlineService.Headlines both = headlines.headlines();

        assertThat(both.privateNumber().result().total().format()).as("still counted").isEqualTo("€1,334.56");
        assertThat(both.privateNumber().result().stale()).extracting(BuildUp.Line::label).containsExactly("Second");
        assertThat(both.privateNumber().result().stale().getFirst().stale().reason()).isEqualTo(Freshness.Reason.NOT_UPDATED);
        assertThat(both.stale()).containsKey(stale.id());
        assertThat(both.privateNumber().result().oldest().label()).isEqualTo("Second");
    }

    @Test
    void aFreshPrivateNumberIsNotStale() {
        account("Everyday", Scope.PRIVATE, Kind.CURRENT, 123_456);

        assertThat(headlines.headlines().privateNumber().result().isStale()).isFalse();
    }

    // ----- business available

    @Test
    void businessIsTheAfterTaxAmountAndOnlyThat() {
        account("Business current", Scope.BUSINESS, Kind.CURRENT, 9_999_900);
        afterTax.test();

        HeadlineService.BusinessNumber business = headlines.headlines().business();

        assertThat(business.result().total().format()).isEqualTo("€12,345.00");
        assertThat(business.result().lines()).hasSize(1);
        assertThat(business.afterTax().grossBalanceCents()).isEqualTo(2_000_000L);
        assertThat(business.afterTax().reserved()).hasSize(2);
    }

    @Test
    void aBusinessCurrentAccountIsListedAsNotCountedAndChangesNeitherTheNumberNorItsStaleState() {
        afterTax.test();
        BuildUp.Result before = headlines.headlines().business().result();
        Account stale = account("Business current", Scope.BUSINESS, Kind.CURRENT, 9_999_900, TODAY.minusDays(60));
        account("Business savings", Scope.BUSINESS, Kind.SAVINGS, 1_000_000);

        HeadlineService.Headlines both = headlines.headlines();

        assertThat(names(both.business().notCounted())).as("savings are not even listed").containsExactly("Business current");
        assertThat(both.stale()).as("the account itself is stale").containsKey(stale.id());
        assertThat(both.business().result().total()).isEqualTo(before.total());
        assertThat(both.business().result().isStale()).isFalse();
    }

    @Test
    void withoutAConfiguredSourceThereIsNoBusinessNumberEvenWhenAnOldValueIsStored() {
        afterTax.test();
        assertThat(afterTax.current()).isPresent();
        HeadlineService withoutSource = headlinesFor(afterTaxFor(""));

        HeadlineService.BusinessNumber business = withoutSource.headlines().business();

        assertThat(business.result().hidden()).isEqualTo(BuildUp.Hidden.NO_SOURCE);
        assertThat(business.result().total()).isNull();
        assertThat(business.hasAfterTax()).isFalse();
    }

    @Test
    void aConfiguredSourceThatHasNotAnsweredYetHasNoValue() {
        assertThat(headlines.headlines().business().result().hidden()).isEqualTo(BuildUp.Hidden.NO_VALUE_YET);
    }

    @Test
    void aFailedLastFetchMakesTheBusinessNumberStaleWithTheReasonButStillShowsTheLastValue() {
        afterTax.test();
        source.answer(500, "");
        h.clock.set(h.clock.instant().plusSeconds(60));
        afterTax.test();

        BuildUp.Result result = headlines.headlines().business().result();

        assertThat(result.total().format()).isEqualTo("€12,345.00");
        assertThat(result.isStale()).isTrue();
        Freshness.Stale why = result.stale().getFirst().stale();
        assertThat(why.reason()).isEqualTo(Freshness.Reason.SOURCE_FAILED);
        assertThat(why.detail()).isEqualTo("status");
    }

    @Test
    void anAnswerOlderThanTheCurrentValueDoesNotChangeTheBusinessNumber() {
        afterTax.test();
        source.answer(FakeAfterTaxSource.valid(Instant.parse("2026-11-26T05:00:00Z")).replace("1234500", "999900"));
        afterTax.test();

        BuildUp.Result result = headlines.headlines().business().result();

        assertThat(result.total().format()).isEqualTo("€12,345.00");
        assertThat(result.isStale()).isFalse();
    }

    @Test
    void anOldValueIsStaleBecauseOfItsOwnMoment() {
        afterTax.test();
        h.clock.set(h.clock.instant().plus(Duration.ofHours(60)));

        BuildUp.Result result = headlines.headlines().business().result();

        assertThat(result.stale().getFirst().stale().reason()).isEqualTo(Freshness.Reason.SOURCE_OLD);
    }

    @Test
    void anAfterTaxAmountThatIsNegativeIsShownAsNegative() {
        source.answer(FakeAfterTaxSource.valid(Instant.parse("2026-11-26T06:00:00Z")).replace("1234500", "-10000")
                .replace("2000000", "400000"));
        afterTax.test();

        assertThat(headlines.headlines().business().result().total().format()).isEqualTo("-€100.00");
    }

    @Test
    void thePrivateAndBusinessNumbersAreNeverCombined() {
        account("Everyday", Scope.PRIVATE, Kind.CURRENT, 123_456);
        afterTax.test();

        HeadlineService.Headlines both = headlines.headlines();

        assertThat(both.privateNumber().result().total().format()).isEqualTo("€1,234.56");
        assertThat(both.business().result().total().format()).isEqualTo("€12,345.00");
        assertThat(HeadlineService.Headlines.class.getRecordComponents()).extracting(c -> c.getName())
                .containsExactly("privateNumber", "business", "stale");
    }

    // ----- reservations (issue #20)

    /** The even share, rounded up, worked out here again on its own: {@code ceil(amount * elapsed / span)}. */
    private static long evenShare(long amount, long elapsedDays, long spanDays) {
        return Math.ceilDiv(amount * elapsedDays, spanDays);
    }

    private void moveDays(int days) {
        h.clock.set(Instant.parse("2026-11-26T12:00:00Z").plus(Duration.ofDays(days)));
    }

    @Test
    void reservationsLowerTheNumberOfTheirOwnScopeByExactlyWhatIsReservedSoFarMonthByMonth() {
        account("Everyday", Scope.PRIVATE, Kind.CURRENT, 123_456);
        afterTax.test(); // business available 12,345.00
        LocalDate yearEnd = TODAY.plusDays(365);   // 2027-11-26
        LocalDate winter = TODAY.plusDays(92);     // 2027-02-26
        reservations.create("Example Insurance premium", Scope.PRIVATE, Money.eur(120_000), yearEnd, false, null);
        reservations.create("Example Gym year", Scope.PRIVATE, Money.eur(60_000), winter, true, null);
        reservations.create("Example Telecom bundle", Scope.BUSINESS, Money.eur(30_000), winter, false, null);

        HeadlineService.Headlines start = headlines.headlines();
        assertThat(start.privateNumber().result().total().cents()).isEqualTo(123_456);
        assertThat(start.business().result().total().cents()).isEqualTo(1_234_500);

        for (int day : new int[] {30, 61, 92}) {
            moveDays(day);
            long insurance = evenShare(120_000, day, 365);
            long gym = day == 92 ? 60_000 : evenShare(60_000, day, 92);
            long telecom = day == 92 ? 30_000 : evenShare(30_000, day, 92);

            HeadlineService.Headlines both = headlines.headlines();

            assertThat(both.privateNumber().result().total().cents()).as("private on day " + day).isEqualTo(123_456 - insurance - gym);
            assertThat(both.privateNumber().result().reserved().cents()).isEqualTo(insurance + gym);
            assertThat(both.business().result().total().cents()).as("business on day " + day).isEqualTo(1_234_500 - telecom);
            assertThat(both.privateNumber().result().reservations()).extracting(BuildUp.Reservation::label)
                    .containsExactlyInAnyOrder("Example Insurance premium", "Example Gym year");
            assertThat(both.business().result().reservations()).extracting(BuildUp.Reservation::label)
                    .containsExactly("Example Telecom bundle");
        }
    }

    @Test
    void aClosedReservationNoLongerCountsAndAnOpenOneAtZeroIsALineOfItsOwn() {
        account("Everyday", Scope.PRIVATE, Kind.CURRENT, 123_456);
        var oneOff = reservations.create("Example Insurance premium", Scope.PRIVATE, Money.eur(10_000), TODAY.plusDays(10), false, Money.eur(2_500));
        var yearly = reservations.create("Example Gym year", Scope.PRIVATE, Money.eur(60_000), TODAY.plusDays(10), true, Money.eur(500));
        assertThat(headlines.headlines().privateNumber().result().reserved().cents()).isEqualTo(3_000);

        reservations.close(oneOff.id());
        reservations.close(yearly.id()); // starts next year's round at zero

        BuildUp.Result result = headlines.headlines().privateNumber().result();
        assertThat(result.reservations()).extracting(BuildUp.Reservation::label, r -> r.amount().cents()).containsExactly(
                org.assertj.core.groups.Tuple.tuple("Example Gym year", 0L));
        assertThat(result.total().cents()).isEqualTo(123_456);
    }

    @Test
    void reservationsAboveTheBalanceMakeThePrivateNumberNegativeWithNothingCapped() {
        account("Everyday", Scope.PRIVATE, Kind.CURRENT, 10_000);
        reservations.create("Example Landlord deposit", Scope.PRIVATE, Money.eur(50_000), TODAY.plusDays(30), false, Money.eur(30_000));

        BuildUp.Result result = headlines.headlines().privateNumber().result();

        assertThat(result.total().cents()).isEqualTo(10_000 - 30_000);
        assertThat(result.total().isNegative()).isTrue();
    }

    @Test
    void anOverdueReservationStaysFullyReserved() {
        account("Everyday", Scope.PRIVATE, Kind.CURRENT, 123_456);
        reservations.create("Example Insurance premium", Scope.PRIVATE, Money.eur(10_000), TODAY.plusDays(5), false, null);
        moveDays(40);

        assertThat(headlines.headlines().privateNumber().result().reserved().cents()).isEqualTo(10_000);
    }

    @Test
    void businessReservationsWithoutAnAfterTaxSourceLeaveTheBusinessNumberHidden() {
        reservations.create("Example Telecom bundle", Scope.BUSINESS, Money.eur(30_000), TODAY.plusDays(30), false, null);
        afterTax.test();
        HeadlineService withoutSource = headlinesFor(afterTaxFor(""));

        BuildUp.Result business = withoutSource.headlines().business().result();

        assertThat(business.hidden()).isEqualTo(BuildUp.Hidden.NO_SOURCE);
        assertThat(business.total()).isNull();
        assertThat(withoutSource.businessSourceConfigured()).isFalse();
        assertThat(headlines.businessSourceConfigured()).isTrue();
    }
}
