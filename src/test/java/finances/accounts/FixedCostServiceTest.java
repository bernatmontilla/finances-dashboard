package finances.accounts;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import finances.accounts.FixedCostException.Reason;
import finances.accounts.FixedCostMonth.Status;
import finances.money.Money;
import finances.money.Rhythm;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZoneOffset;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.sqlite.SQLiteConfig;
import org.sqlite.SQLiteDataSource;

/** The fixed cost rules on a real, migrated SQLite file, without the web layer. Fictional data only. */
class FixedCostServiceTest {

    private TestClock clock;
    private FixedCostService fixedCosts;
    private AccountService accounts;
    private JdbcClient jdbc;

    @BeforeEach
    void open(@TempDir Path folder) {
        SQLiteConfig config = new SQLiteConfig();
        config.enforceForeignKeys(true);
        SQLiteDataSource dataSource = new SQLiteDataSource(config);
        dataSource.setUrl("jdbc:sqlite:" + folder.resolve("finances.db"));
        Flyway.configure().dataSource(dataSource).locations("classpath:db/migration").load().migrate();
        jdbc = JdbcClient.create(dataSource);
        clock = new TestClock(LocalDate.of(2026, 6, 1));
        accounts = new AccountService(jdbc, clock);
        fixedCosts = new FixedCostService(jdbc, clock, accounts);
    }

    private static Money eur(String typed) {
        return Money.parse(typed);
    }

    private static LocalDate date(String day) {
        return LocalDate.parse(day);
    }

    private FixedCost add(String name, Scope scope, String amount, Rhythm rhythm, String firstDue) {
        return fixedCosts.create(name, scope, eur(amount), rhythm, date(firstDue), null, null, null);
    }

    private FixedCost rent() {
        return add("Example Landlord rent", Scope.PRIVATE, "1234.56", Rhythm.MONTHLY, "2026-01-15");
    }

    private Status status(FixedCost cost) {
        return fixedCosts.month(cost.id()).status();
    }

    private void refuse(Reason reason, org.assertj.core.api.ThrowableAssert.ThrowingCallable call) {
        assertThatThrownBy(call).isInstanceOfSatisfying(FixedCostException.class, e -> assertThat(e.reason()).isEqualTo(reason));
    }

    // ----- create and totals

    @Test
    void createsAFixedCostTrimsTheNameAndKeepsTheOptionalPartsEmpty() {
        FixedCost created = fixedCosts.create("  Example Telecom  ", Scope.PRIVATE, eur("12,34"), Rhythm.MONTHLY, date("2026-06-03"), " ", "", null);

        assertThat(created.name()).isEqualTo("Example Telecom");
        assertThat(created.amount()).isEqualTo(eur("12.34"));
        assertThat(created.counterpartyName()).isNull();
        assertThat(created.counterpartyIban()).isNull();
        assertThat(created.accountId()).isNull();
        assertThat(created.isPaused()).isFalse();
        assertThat(created.endedOn()).isNull();
    }

    @Test
    void theTotalsSpreadLongerRhythmsEvenlyAndRoundTheMonthUp() {
        add("Example Landlord rent", Scope.PRIVATE, "100.00", Rhythm.MONTHLY, "2026-01-15");
        add("Example Insurance", Scope.PRIVATE, "100.00", Rhythm.QUARTERLY, "2026-03-01");
        add("Example Gym", Scope.PRIVATE, "1234.56", Rhythm.YEARLY, "2026-09-01");

        FixedCostSummary summary = fixedCosts.summary(Scope.PRIVATE);

        assertThat(summary.perYear()).isEqualTo(eur("2834.56"));
        assertThat(summary.perMonth()).as("236.213... rounded up").isEqualTo(eur("236.22"));
        assertThat(summary.running()).isEqualTo(3);
    }

    @Test
    void theTotalsOfTheTwoScopesNeverMix() {
        add("Example Landlord rent", Scope.PRIVATE, "100.00", Rhythm.MONTHLY, "2026-01-15");
        add("Example Client BV software", Scope.BUSINESS, "50.00", Rhythm.MONTHLY, "2026-01-15");

        assertThat(fixedCosts.summary(Scope.PRIVATE).perYear()).isEqualTo(eur("1200.00"));
        assertThat(fixedCosts.summary(Scope.BUSINESS).perYear()).isEqualTo(eur("600.00"));
        assertThat(fixedCosts.summary(Scope.BUSINESS).perMonth()).isEqualTo(eur("50.00"));
    }

    @Test
    void anEmptyScopeHasZeroTotalsAndNothingToCome() {
        FixedCostSummary summary = fixedCosts.summary(Scope.BUSINESS);

        assertThat(summary.perMonth()).isEqualTo(Money.zero());
        assertThat(summary.perYear()).isEqualTo(Money.zero());
        assertThat(summary.hasStillToCome()).isFalse();
        assertThat(summary.stillToComeTotal()).isEqualTo(Money.zero());
    }

    @Test
    void pausedAndEndedFixedCostsAreListedButNotCounted() {
        FixedCost paused = add("Example Gym", Scope.PRIVATE, "10.00", Rhythm.MONTHLY, "2026-01-15");
        FixedCost ended = add("Example Telecom", Scope.PRIVATE, "20.00", Rhythm.MONTHLY, "2026-01-15");
        add("Example Landlord rent", Scope.PRIVATE, "100.00", Rhythm.MONTHLY, "2026-01-15");
        fixedCosts.pause(paused.id());
        fixedCosts.end(ended.id(), date("2026-05-31"));

        FixedCostSummary summary = fixedCosts.summary(Scope.PRIVATE);

        assertThat(summary.perYear()).isEqualTo(eur("1200.00"));
        assertThat(summary.running()).isEqualTo(1);
        assertThat(fixedCosts.months(Scope.PRIVATE)).extracting(FixedCostMonth::status)
                .containsExactlyInAnyOrder(Status.STILL_TO_COME, Status.PAUSED, Status.ENDED);
    }

    // ----- this month, moving the clock

    @Test
    void aMonthlyCostIsStillToComeThroughTheMonthUntilTickedAndStartsAgainNextMonth() {
        FixedCost rent = rent(); // charged on the 15th
        for (String day : new String[] {"2026-06-01", "2026-06-14", "2026-06-15", "2026-06-16", "2026-06-30"}) {
            clock.set(date(day));
            FixedCostMonth month = fixedCosts.month(rent.id());
            assertThat(month.status()).as(day).isEqualTo(Status.STILL_TO_COME);
            assertThat(month.expected()).contains(date("2026-06-15"));
            assertThat(month.nextDue()).contains(date("2026-07-15"));
        }

        clock.set(date("2026-06-20"));
        fixedCosts.tick(rent.id());
        assertThat(status(rent)).isEqualTo(Status.PAID);
        assertThat(fixedCosts.summary(Scope.PRIVATE).hasStillToCome()).isFalse();

        fixedCosts.untick(rent.id());
        assertThat(status(rent)).isEqualTo(Status.STILL_TO_COME);
        assertThat(fixedCosts.summary(Scope.PRIVATE).stillToCome()).hasSize(1);
        assertThat(fixedCosts.summary(Scope.PRIVATE).stillToComeTotal()).isEqualTo(eur("1234.56"));

        fixedCosts.tick(rent.id());
        clock.set(date("2026-07-01"));
        assertThat(status(rent)).as("a new month has no tick").isEqualTo(Status.STILL_TO_COME);
    }

    @Test
    void theLastDayOfAShortMonthIsWhereAChargeOnThe31stFalls() {
        FixedCost cost = add("Example Insurance", Scope.PRIVATE, "10.00", Rhythm.MONTHLY, "2026-01-31");
        clock.set(date("2026-02-10"));

        assertThat(fixedCosts.month(cost.id()).expected()).contains(date("2026-02-28"));
        assertThat(fixedCosts.month(cost.id()).nextDue()).contains(date("2026-03-31"));
    }

    @Test
    void aYearlyCostDueInJanuaryIsNotToComeInDecemberAndIsOnTheFirstOfJanuary() {
        FixedCost yearly = add("Example Gym", Scope.PRIVATE, "120.00", Rhythm.YEARLY, "2025-01-10");

        clock.set(date("2026-12-31"));
        FixedCostMonth december = fixedCosts.month(yearly.id());
        assertThat(december.status()).isEqualTo(Status.NOT_THIS_MONTH);
        assertThat(december.expected()).isEmpty();
        assertThat(december.nextDue()).contains(date("2027-01-10"));
        assertThat(fixedCosts.summary(Scope.PRIVATE).hasStillToCome()).isFalse();

        clock.set(date("2027-01-01"));
        FixedCostMonth january = fixedCosts.month(yearly.id());
        assertThat(january.status()).isEqualTo(Status.STILL_TO_COME);
        assertThat(january.expected()).contains(date("2027-01-10"));
        assertThat(january.nextDue()).contains(date("2028-01-10"));
    }

    @Test
    void aQuarterlyCostIsToComeOnlyInItsMonths() {
        FixedCost quarterly = add("Example Insurance", Scope.BUSINESS, "100.00", Rhythm.QUARTERLY, "2026-03-15");

        clock.set(date("2026-06-01"));
        assertThat(status(quarterly)).isEqualTo(Status.STILL_TO_COME);
        clock.set(date("2026-07-01"));
        assertThat(status(quarterly)).isEqualTo(Status.NOT_THIS_MONTH);
        clock.set(date("2026-09-01"));
        assertThat(status(quarterly)).isEqualTo(Status.STILL_TO_COME);
    }

    @Test
    void aCostThatStartsLaterThisMonthIsToComeAndOneThatStartsNextMonthIsNot() {
        FixedCost thisMonth = add("Example Telecom", Scope.PRIVATE, "10.00", Rhythm.MONTHLY, "2026-06-20");
        FixedCost nextMonth = add("Example Gym", Scope.PRIVATE, "10.00", Rhythm.MONTHLY, "2026-07-02");

        assertThat(status(thisMonth)).isEqualTo(Status.STILL_TO_COME);
        FixedCostMonth later = fixedCosts.month(nextMonth.id());
        assertThat(later.status()).isEqualTo(Status.NOT_THIS_MONTH);
        assertThat(later.nextDue()).contains(date("2026-07-02"));
    }

    @Test
    void aCostEndedBeforeItsChargeIsNotToComeAndOneEndedAfterItIs() {
        FixedCost early = add("Example Telecom", Scope.PRIVATE, "10.00", Rhythm.MONTHLY, "2026-01-15");
        FixedCost late = add("Example Gym", Scope.PRIVATE, "10.00", Rhythm.MONTHLY, "2026-01-15");
        clock.set(date("2026-06-05"));

        fixedCosts.end(early.id(), date("2026-06-10"));
        fixedCosts.end(late.id(), date("2026-06-20"));

        assertThat(status(early)).as("the end date is still ahead, but the charge falls after it").isEqualTo(Status.NOT_THIS_MONTH);
        assertThat(fixedCosts.month(early.id()).nextDue()).as("nothing is charged after the end date").isEmpty();
        clock.set(date("2026-06-11"));
        assertThat(status(early)).isEqualTo(Status.ENDED);
        assertThat(status(late)).isEqualTo(Status.STILL_TO_COME);
        assertThat(fixedCosts.summary(Scope.PRIVATE).stillToCome()).extracting(m -> m.cost().name()).containsExactly("Example Gym");
    }

    @Test
    void aCostEndedTodayStillHasTodaysChargeToCome() {
        FixedCost rent = rent(); // charged on the 15th
        clock.set(date("2026-06-15"));

        fixedCosts.end(rent.id(), null);

        assertThat(status(rent)).as("the end date is the last day it may be charged").isEqualTo(Status.STILL_TO_COME);
        fixedCosts.tick(rent.id());
        assertThat(status(rent)).isEqualTo(Status.PAID);
        clock.set(date("2026-06-16"));
        assertThat(status(rent)).isEqualTo(Status.ENDED);
        refuse(Reason.ENDED, () -> fixedCosts.tick(rent.id()));
    }

    @Test
    void aChargeWhoseDateHasPassedWithoutATickIsFlaggedOverdueUntilTicked() {
        FixedCost rent = rent(); // charged on the 15th
        clock.set(date("2026-06-15"));
        assertThat(fixedCosts.month(rent.id()).overdue()).as("due today is not late").isFalse();
        clock.set(date("2026-06-16"));
        assertThat(fixedCosts.month(rent.id()).overdue()).isTrue();
        assertThat(fixedCosts.month(rent.id()).status()).isEqualTo(Status.STILL_TO_COME);

        fixedCosts.tick(rent.id());

        assertThat(fixedCosts.month(rent.id()).overdue()).isFalse();
    }

    @Test
    void resumingAPausedCostKeepsAnEndDateStillAheadAndResumingARunningOneIsRefused() {
        FixedCost rent = rent();
        fixedCosts.end(rent.id(), date("2026-12-31"));
        refuse(Reason.NOT_PAUSED_OR_ENDED, () -> fixedCosts.resume(rent.id()));
        fixedCosts.pause(rent.id());

        fixedCosts.resume(rent.id());

        assertThat(fixedCosts.get(rent.id()).endedOn()).isEqualTo(date("2026-12-31"));
        assertThat(fixedCosts.get(rent.id()).isPaused()).isFalse();
    }

    @Test
    void theOnesStillToComeAreOrderedByExpectedDate() {
        add("Example Telecom", Scope.PRIVATE, "10.00", Rhythm.MONTHLY, "2026-01-25");
        add("Example Gym", Scope.PRIVATE, "10.00", Rhythm.MONTHLY, "2026-01-05");
        add("Example Energy", Scope.PRIVATE, "10.00", Rhythm.MONTHLY, "2026-01-15");

        assertThat(fixedCosts.summary(Scope.PRIVATE).stillToCome()).extracting(m -> m.cost().name())
                .containsExactly("Example Gym", "Example Energy", "Example Telecom");
    }

    // ----- pause, resume, end, edit

    @Test
    void aPausedCostIsNotToComeAndNotCountedAndResumingBringsBothBack() {
        FixedCost rent = rent();
        fixedCosts.pause(rent.id());

        assertThat(status(rent)).isEqualTo(Status.PAUSED);
        assertThat(fixedCosts.summary(Scope.PRIVATE).hasStillToCome()).isFalse();
        assertThat(fixedCosts.summary(Scope.PRIVATE).perYear()).isEqualTo(Money.zero());

        fixedCosts.resume(rent.id());
        assertThat(status(rent)).isEqualTo(Status.STILL_TO_COME);
        assertThat(fixedCosts.summary(Scope.PRIVATE).perYear()).isEqualTo(eur("14814.72"));
    }

    @Test
    void anEndedCostCanBeResumedAndIsThenRunningAgain() {
        FixedCost rent = rent();
        fixedCosts.end(rent.id(), null);
        assertThat(fixedCosts.get(rent.id()).endedOn()).isEqualTo(date("2026-06-01"));
        clock.set(date("2026-06-02"));
        assertThat(status(rent)).isEqualTo(Status.ENDED);

        fixedCosts.resume(rent.id());

        assertThat(fixedCosts.get(rent.id()).endedOn()).isNull();
        assertThat(status(rent)).isEqualTo(Status.STILL_TO_COME);
    }

    @Test
    void ticksSurvivePauseResumeAndEditsOfDayRhythmAndAmount() {
        FixedCost rent = rent();
        fixedCosts.tick(rent.id());

        fixedCosts.pause(rent.id());
        fixedCosts.resume(rent.id());
        assertThat(status(rent)).isEqualTo(Status.PAID);

        fixedCosts.update(rent.id(), "Example Landlord rent", Scope.PRIVATE, eur("1300.00"), Rhythm.MONTHLY, date("2026-01-28"), null, null, null);
        assertThat(status(rent)).isEqualTo(Status.PAID);

        fixedCosts.update(rent.id(), "Example Landlord rent", Scope.PRIVATE, eur("1300.00"), Rhythm.QUARTERLY, date("2026-03-28"), null, null, null);
        assertThat(status(rent)).as("June is a quarterly month from March").isEqualTo(Status.PAID);
    }

    @Test
    void ticksGoWhenTheFixedCostIsRemoved() {
        FixedCost rent = rent();
        fixedCosts.tick(rent.id());

        fixedCosts.remove(rent.id());

        assertThat(fixedCosts.find(rent.id())).isEmpty();
        assertThat(jdbc.sql("select count(*) from fixed_cost_paid").query(Integer.class).single()).isZero();
    }

    @Test
    void updateChangesTheFieldsAndKeepsAnAccountThatWasArchivedSince() {
        Account everyday = accounts.addManual("Everyday", Scope.PRIVATE, Kind.CURRENT, "NL51XMPL0000000001");
        FixedCost rent = fixedCosts.create("Example Landlord rent", Scope.PRIVATE, eur("1234.56"), Rhythm.MONTHLY, date("2026-01-15"),
                "Example Landlord", "nl10 shop 0000 000101", everyday.id());
        assertThat(rent.counterpartyIban()).isEqualTo("NL10SHOP0000000101");
        accounts.archive(everyday.id());

        FixedCost changed = fixedCosts.update(rent.id(), "Rent", Scope.PRIVATE, eur("1300.00"), Rhythm.MONTHLY, date("2026-01-15"),
                "Example Landlord", "NL10SHOP0000000101", everyday.id());

        assertThat(changed.name()).isEqualTo("Rent");
        assertThat(changed.amount()).isEqualTo(eur("1300.00"));
        assertThat(changed.accountId()).isEqualTo(everyday.id());
        assertThat(changed.changedAt()).isNotNull();
    }

    // ----- refusals

    @Test
    void tickingIsRefusedWithoutAChargeThisMonthOrWhenPausedOrEnded() {
        FixedCost yearly = add("Example Gym", Scope.PRIVATE, "120.00", Rhythm.YEARLY, "2026-03-10");
        refuse(Reason.NO_CHARGE_THIS_MONTH, () -> fixedCosts.tick(yearly.id()));
        refuse(Reason.NO_CHARGE_THIS_MONTH, () -> fixedCosts.untick(yearly.id()));

        FixedCost paused = rent();
        fixedCosts.pause(paused.id());
        refuse(Reason.PAUSED, () -> fixedCosts.tick(paused.id()));

        FixedCost ended = add("Example Telecom", Scope.PRIVATE, "10.00", Rhythm.MONTHLY, "2026-01-15");
        fixedCosts.end(ended.id(), date("2026-05-31"));
        refuse(Reason.ENDED, () -> fixedCosts.tick(ended.id()));

        assertThat(jdbc.sql("select count(*) from fixed_cost_paid").query(Integer.class).single()).isZero();
    }

    @Test
    void anAccountOfTheOtherScopeOrAnArchivedOneOrAMissingOneIsRefusedAtCreation() {
        Account business = accounts.addManual("Example Client BV account", Scope.BUSINESS, Kind.CURRENT, "NL94XMPL0000000003");
        Account archived = accounts.addManual("Everyday", Scope.PRIVATE, Kind.CURRENT, "NL51XMPL0000000001");
        accounts.archive(archived.id());

        refuse(Reason.ACCOUNT_OUT_OF_SCOPE, () -> fixedCosts.create("Rent", Scope.PRIVATE, eur("1.00"), Rhythm.MONTHLY, date("2026-06-01"), null, null, business.id()));
        refuse(Reason.ACCOUNT_ARCHIVED, () -> fixedCosts.create("Rent", Scope.PRIVATE, eur("1.00"), Rhythm.MONTHLY, date("2026-06-01"), null, null, archived.id()));
        refuse(Reason.ACCOUNT_NOT_FOUND, () -> fixedCosts.create("Rent", Scope.PRIVATE, eur("1.00"), Rhythm.MONTHLY, date("2026-06-01"), null, null, 9999L));

        assertThat(jdbc.sql("select count(*) from fixed_cost").query(Integer.class).single()).isZero();
    }

    @Test
    void invalidInputIsRefusedWithItsOwnReasonAndStoresNothing() {
        LocalDate day = date("2026-06-01");
        refuse(Reason.NAME_REQUIRED, () -> fixedCosts.create("  ", Scope.PRIVATE, eur("1.00"), Rhythm.MONTHLY, day, null, null, null));
        refuse(Reason.NAME_TOO_LONG, () -> fixedCosts.create("x".repeat(101), Scope.PRIVATE, eur("1.00"), Rhythm.MONTHLY, day, null, null, null));
        refuse(Reason.AMOUNT_NOT_POSITIVE, () -> fixedCosts.create("Rent", Scope.PRIVATE, Money.zero(), Rhythm.MONTHLY, day, null, null, null));
        refuse(Reason.AMOUNT_NOT_POSITIVE, () -> fixedCosts.create("Rent", Scope.PRIVATE, eur("-1.00"), Rhythm.MONTHLY, day, null, null, null));
        refuse(Reason.AMOUNT_TOO_LARGE, () -> fixedCosts.create("Rent", Scope.PRIVATE, eur("100000000.01"), Rhythm.MONTHLY, day, null, null, null));
        refuse(Reason.INVALID_RHYTHM, () -> fixedCosts.create("Rent", Scope.PRIVATE, eur("1.00"), null, day, null, null, null));
        refuse(Reason.INVALID_SCOPE, () -> fixedCosts.create("Rent", null, eur("1.00"), Rhythm.MONTHLY, day, null, null, null));
        refuse(Reason.FIRST_DUE_REQUIRED, () -> fixedCosts.create("Rent", Scope.PRIVATE, eur("1.00"), Rhythm.MONTHLY, null, null, null, null));
        refuse(Reason.FIRST_DUE_TOO_FAR, () -> fixedCosts.create("Rent", Scope.PRIVATE, eur("1.00"), Rhythm.MONTHLY, day.plusYears(51), null, null, null));
        refuse(Reason.COUNTERPARTY_TOO_LONG, () -> fixedCosts.create("Rent", Scope.PRIVATE, eur("1.00"), Rhythm.MONTHLY, day, "x".repeat(101), null, null));
        assertThatThrownBy(() -> fixedCosts.create("Rent", Scope.PRIVATE, eur("1.00"), Rhythm.MONTHLY, day, null, "not an iban", null))
                .isInstanceOfSatisfying(AccountException.class, e -> assertThat(e.reason()).isEqualTo(AccountException.Reason.IBAN_INVALID));

        assertThat(jdbc.sql("select count(*) from fixed_cost").query(Integer.class).single()).isZero();
    }

    @Test
    void theRhythmAndScopeCodesOfAnUnknownValueAreRefused() {
        assertThatThrownBy(() -> Rhythm.parse("weekly")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> Scope.parse("family")).isInstanceOf(AccountException.class);
    }

    @Test
    void aFirstChargeInThePastIsFine() {
        FixedCost old = add("Example Landlord rent", Scope.PRIVATE, "1.00", Rhythm.MONTHLY, "2020-01-01");

        assertThat(old.firstDue()).isEqualTo(date("2020-01-01"));
        assertThat(status(old)).isEqualTo(Status.STILL_TO_COME);
    }

    @Test
    void endingBeforeTheFirstChargeIsRefusedAndAnEndedCostIsNotEdited() {
        FixedCost rent = rent();
        refuse(Reason.END_BEFORE_FIRST_DUE, () -> fixedCosts.end(rent.id(), date("2025-12-31")));
        assertThat(fixedCosts.get(rent.id()).endedOn()).isNull();

        fixedCosts.end(rent.id(), date("2026-05-31"));
        refuse(Reason.ENDED, () -> fixedCosts.update(rent.id(), "Rent", Scope.PRIVATE, eur("1.00"), Rhythm.MONTHLY, date("2026-01-15"), null, null, null));
        refuse(Reason.ENDED, () -> fixedCosts.pause(rent.id()));
        refuse(Reason.ENDED, () -> fixedCosts.remove(rent.id()));
        assertThat(fixedCosts.get(rent.id()).name()).isEqualTo("Example Landlord rent");
    }

    @Test
    void pausingTwiceResumingARunningOneAndAnUnknownIdAreRefused() {
        FixedCost rent = rent();
        refuse(Reason.NOT_PAUSED_OR_ENDED, () -> fixedCosts.resume(rent.id()));
        fixedCosts.pause(rent.id());
        refuse(Reason.ALREADY_PAUSED, () -> fixedCosts.pause(rent.id()));
        refuse(Reason.NOT_FOUND, () -> fixedCosts.get(4711));
    }

    @Test
    void nothingHereTouchesTransactionsOrBalances() {
        rent();

        assertThat(jdbc.sql("select count(*) from transactions").query(Integer.class).single()).isZero();
        assertThat(jdbc.sql("select count(*) from balance").query(Integer.class).single()).isZero();
    }

    /** A clock the test can move, one day at a time. */
    private static final class TestClock extends Clock {
        private volatile Instant now;

        TestClock(LocalDate day) {
            set(day);
        }

        void set(LocalDate day) {
            now = day.atTime(12, 0).toInstant(ZoneOffset.UTC);
        }

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return now;
        }
    }
}
