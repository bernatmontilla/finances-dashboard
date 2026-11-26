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
import java.util.List;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.sqlite.SQLiteConfig;
import org.sqlite.SQLiteDataSource;

/** The fixed cost rules on a real, migrated SQLite file, without the web layer. Fictional data only. */
class FixedCostServiceTest {

    private TestClock clock;
    private FixedCostService fixedCosts;
    private AccountService accounts;
    private TransactionService transactions;
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
        transactions = new TransactionService(jdbc, clock, new DataSourceTransactionManager(dataSource));
        fixedCosts = new FixedCostService(jdbc, clock, accounts, transactions);
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

    // ----- matching against transactions (issue #63)

    private static final String ENERGY_IBAN = "NL10SHOP0000000101";

    private Account everyday() {
        return accounts.addManual("Everyday", Scope.PRIVATE, Kind.CURRENT, "NL51XMPL0000000001");
    }

    private long pay(Account account, String day, long cents, TransactionStatus status, String name, String iban, String reference) {
        transactions.deliver(account.id(), Source.CSV, List.of(new DeliveredTransaction(date(day), null, cents, "EUR", name, iban,
                "Fictional payment", status, reference, "raw " + reference)));
        return jdbc.sql("select id from transactions where reference = ?").param(reference).query(Long.class).single();
    }

    private long paidEnergy(Account account, String day, long cents, String reference) {
        return pay(account, day, cents, TransactionStatus.BOOKED, "Example Energy", ENERGY_IBAN, reference);
    }

    /** A monthly fixed cost of 100.00 charged on the 15th, matched by IBAN; today is 12 June. */
    private FixedCost energy() {
        clock.set(date("2026-06-12"));
        return fixedCosts.create("Example Energy", Scope.PRIVATE, eur("100.00"), Rhythm.MONTHLY, date("2026-01-15"), "Example Energy",
                ENERGY_IBAN, null);
    }

    private int links() {
        return jdbc.sql("select count(*) from fixed_cost_match").query(Integer.class).single();
    }

    @Test
    void aBookedPaymentOfTheRightCounterpartyAndAmountMarksTheMonthPaidAndIsShown() {
        Account account = everyday();
        FixedCost cost = energy();
        assertThat(status(cost)).isEqualTo(Status.STILL_TO_COME);
        long payment = paidEnergy(account, "2026-06-15", -10000, "REF-1");

        assertThat(fixedCosts.matchPayments()).isEqualTo(1);

        FixedCostMonth month = fixedCosts.month(cost.id());
        assertThat(month.status()).isEqualTo(Status.PAID);
        assertThat(month.byHand()).isFalse();
        assertThat(month.payment()).hasValueSatisfying(t -> assertThat(t.id()).isEqualTo(payment));
        assertThat(fixedCosts.summary(Scope.PRIVATE).hasStillToCome()).isFalse();
    }

    @Test
    void theIbanDecidesWhenThereIsOneAndTheNameIsThenIgnored() {
        Account account = everyday();
        FixedCost cost = energy();
        pay(account, "2026-06-15", -10000, TransactionStatus.BOOKED, "Example Energy", "NL80SHOP0000000102", "REF-OTHER");
        fixedCosts.matchPayments();
        assertThat(status(cost)).as("same name, another IBAN").isEqualTo(Status.STILL_TO_COME);

        pay(account, "2026-06-15", -10000, TransactionStatus.BOOKED, "Another Name", " nl10shop 0000000101 ", "REF-IBAN");
        fixedCosts.matchPayments();
        assertThat(status(cost)).as("same IBAN, another name and spacing").isEqualTo(Status.PAID);
    }

    @Test
    void withoutAnIbanTheNameMustBeEqualAfterNormalising() {
        clock.set(date("2026-06-12"));
        Account account = everyday();
        FixedCost cost = fixedCosts.create("Energy", Scope.PRIVATE, eur("100.00"), Rhythm.MONTHLY, date("2026-01-15"), "Example Energy", null,
                null);
        pay(account, "2026-06-15", -10000, TransactionStatus.BOOKED, "Example Energy BV", null, "REF-BV");
        fixedCosts.matchPayments();
        assertThat(status(cost)).isEqualTo(Status.STILL_TO_COME);

        pay(account, "2026-06-15", -10000, TransactionStatus.BOOKED, "  example   ENERGY ", null, "REF-NAME");
        fixedCosts.matchPayments();
        assertThat(status(cost)).isEqualTo(Status.PAID);
    }

    @ParameterizedTest(name = "{0} cents against 100.00: {1}")
    @CsvSource({"-8999,false", "-9000,true", "-10000,true", "-11000,true", "-11001,false"})
    void theAmountMustBeWithinATenthOfTheFixedCostInCents(long cents, boolean matches) {
        Account account = everyday();
        FixedCost cost = energy();
        paidEnergy(account, "2026-06-15", cents, "REF-1");

        fixedCosts.matchPayments();

        assertThat(status(cost)).isEqualTo(matches ? Status.PAID : Status.STILL_TO_COME);
    }

    @ParameterizedTest(name = "booked {0}, expected 15 June: {1}")
    @CsvSource({"2026-06-07,false", "2026-06-08,true", "2026-06-15,true", "2026-06-22,true", "2026-06-23,false"})
    void theBookingDateMustBeWithinSevenDaysOfTheExpectedDateBothEndsIncluded(String booked, boolean matches) {
        Account account = everyday();
        FixedCost cost = energy();
        paidEnergy(account, booked, -10000, "REF-1");
        clock.set(date("2026-06-23"));

        fixedCosts.matchPayments();

        assertThat(status(cost)).isEqualTo(matches ? Status.PAID : Status.STILL_TO_COME);
    }

    @Test
    void aChargeAtTheEndOfTheMonthBelongsToTheNextMonthsOccurrence() {
        Account account = everyday();
        clock.set(date("2026-11-27"));
        FixedCost rent = fixedCosts.create("Example Landlord rent", Scope.PRIVATE, eur("100.00"), Rhythm.MONTHLY, date("2026-01-01"), null,
                "NL53SHOP0000000103", null);
        pay(account, "2026-11-29", -10000, TransactionStatus.BOOKED, "Example Landlord", "NL53SHOP0000000103", "REF-NOV");

        fixedCosts.matchPayments();

        assertThat(jdbc.sql("select month from fixed_cost_match").query(String.class).list()).containsExactly("2026-12");
        assertThat(status(rent)).as("November's occurrence (1 November) is not marked by it").isEqualTo(Status.STILL_TO_COME);
        clock.set(date("2026-12-01"));
        assertThat(status(rent)).as("shown paid once December is the current month").isEqualTo(Status.PAID);
    }

    @Test
    void pendingIncomingOtherScopeOtherAccountAndWrongCurrencyDoNotMatchButAnArchivedAccountOfTheScopeDoes() {
        Account account = everyday();
        Account business = accounts.addManual("Business", Scope.BUSINESS, Kind.CURRENT, "NL94XMPL0000000003");
        FixedCost cost = energy();
        pay(account, "2026-06-15", -10000, TransactionStatus.PENDING, "Example Energy", ENERGY_IBAN, "REF-PENDING");
        paidEnergy(account, "2026-06-15", 10000, "REF-INCOMING");
        paidEnergy(business, "2026-06-15", -10000, "REF-BUSINESS");
        fixedCosts.matchPayments();
        assertThat(status(cost)).isEqualTo(Status.STILL_TO_COME);

        Account savings = accounts.addManual("Savings", Scope.PRIVATE, Kind.SAVINGS, "NL24XMPL0000000002");
        FixedCost fromEveryday = fixedCosts.create("From everyday", Scope.PRIVATE, eur("100.00"), Rhythm.MONTHLY, date("2026-01-15"), null,
                ENERGY_IBAN, account.id());
        paidEnergy(savings, "2026-06-15", -10000, "REF-SAVINGS");
        fixedCosts.matchPayments();
        assertThat(status(fromEveryday)).as("another account than the one it is tied to").isEqualTo(Status.STILL_TO_COME);
        assertThat(status(cost)).as("the scope-wide one takes the savings payment").isEqualTo(Status.PAID);

        accounts.archive(savings.id());
        FixedCost another = fixedCosts.create("Another", Scope.PRIVATE, eur("100.00"), Rhythm.MONTHLY, date("2026-01-15"), null, ENERGY_IBAN,
                null);
        fixedCosts.remove(cost.id());
        fixedCosts.matchPayments();
        assertThat(status(another)).as("a payment on an archived account of the scope still counts").isEqualTo(Status.PAID);
    }

    @Test
    void aFixedCostWithNoCounterpartyAndAPausedOrEndedOneAreNeverMatched() {
        Account account = everyday();
        clock.set(date("2026-06-12"));
        FixedCost nothing = fixedCosts.create("By hand", Scope.PRIVATE, eur("100.00"), Rhythm.MONTHLY, date("2026-01-15"), null, null, null);
        FixedCost paused = fixedCosts.create("Paused", Scope.PRIVATE, eur("100.00"), Rhythm.MONTHLY, date("2026-01-15"), null, ENERGY_IBAN,
                null);
        fixedCosts.pause(paused.id());
        paidEnergy(account, "2026-06-15", -10000, "REF-1");

        assertThat(fixedCosts.matchPayments()).isZero();
        assertThat(status(nothing)).isEqualTo(Status.STILL_TO_COME);
        assertThat(status(paused)).isEqualTo(Status.PAUSED);
        assertThat(links()).isZero();
    }

    @Test
    void oneTransactionMarksOnlyTheClosestFixedCostAndTheIndexRefusesASecondLink() {
        Account account = everyday();
        clock.set(date("2026-06-12"));
        FixedCost twenty = fixedCosts.create("Example Telecom 20", Scope.PRIVATE, eur("20.00"), Rhythm.MONTHLY, date("2026-01-15"), null,
                "NL80SHOP0000000102", null);
        FixedCost twentyOne = fixedCosts.create("Example Telecom 21", Scope.PRIVATE, eur("21.00"), Rhythm.MONTHLY, date("2026-01-15"), null,
                "NL80SHOP0000000102", null);
        long payment = pay(account, "2026-06-15", -2000, TransactionStatus.BOOKED, "Example Telecom", "NL80SHOP0000000102", "REF-T");

        assertThat(fixedCosts.matchPayments()).isEqualTo(1);

        assertThat(status(twenty)).isEqualTo(Status.PAID);
        assertThat(status(twentyOne)).isEqualTo(Status.STILL_TO_COME);
        assertThatThrownBy(() -> jdbc.sql("insert into fixed_cost_match (fixed_cost_id, month, transaction_id, matched_at) values (?, '2026-06', ?, 'x')")
                .params(twentyOne.id(), payment).update()).hasMessageContaining("UNIQUE constraint failed: fixed_cost_match.transaction_id");
    }

    @Test
    void matchingTwiceStoresNothingTwiceAndALaterCloserPaymentDoesNotMoveALink() {
        Account account = everyday();
        FixedCost cost = energy();
        long first = paidEnergy(account, "2026-06-13", -9500, "REF-FIRST");
        assertThat(fixedCosts.matchPayments()).isEqualTo(1);
        assertThat(fixedCosts.matchPayments()).isZero();

        paidEnergy(account, "2026-06-15", -10000, "REF-CLOSER");
        assertThat(fixedCosts.matchPayments()).isZero();

        assertThat(fixedCosts.month(cost.id()).payment()).hasValueSatisfying(t -> assertThat(t.id()).isEqualTo(first));
        assertThat(links()).isEqualTo(1);
    }

    @Test
    void aTickStaysPaidByHandWithAndWithoutAMatchAndAMatchNeverOverridesIt() {
        Account account = everyday();
        FixedCost cost = energy();
        fixedCosts.tick(cost.id());
        assertThat(fixedCosts.month(cost.id()).byHand()).isTrue();
        assertThat(status(cost)).as("no transaction matches").isEqualTo(Status.PAID);

        paidEnergy(account, "2026-06-15", -10000, "REF-1");
        fixedCosts.matchPayments();

        FixedCostMonth month = fixedCosts.month(cost.id());
        assertThat(month.status()).isEqualTo(Status.PAID);
        assertThat(month.byHand()).isTrue();
        assertThat(month.payment()).as("paid by hand, not by the match").isEmpty();
    }

    @Test
    void anUntickOfAMatchedMonthWinsOverTheMatchUntilTheOwnerTicksAgain() {
        Account account = everyday();
        FixedCost cost = energy();
        paidEnergy(account, "2026-06-15", -10000, "REF-1");
        fixedCosts.matchPayments();
        assertThat(status(cost)).isEqualTo(Status.PAID);

        fixedCosts.untick(cost.id());
        assertThat(status(cost)).isEqualTo(Status.STILL_TO_COME);
        fixedCosts.matchPayments();
        assertThat(status(cost)).as("a later run does not mark it paid again").isEqualTo(Status.STILL_TO_COME);
        assertThat(links()).as("the transaction is not offered to another fixed cost").isEqualTo(1);

        fixedCosts.tick(cost.id());
        assertThat(status(cost)).isEqualTo(Status.PAID);
        assertThat(jdbc.sql("select count(*) from fixed_cost_unpaid").query(Integer.class).single()).isZero();
    }

    @Test
    void anUntickWithNeitherALinkNorATickWritesNothingSoALaterPaymentStillMarksIt() {
        Account account = everyday();
        FixedCost cost = energy();

        fixedCosts.untick(cost.id());
        assertThat(jdbc.sql("select count(*) from fixed_cost_unpaid").query(Integer.class).single()).isZero();

        paidEnergy(account, "2026-06-15", -10000, "REF-1");
        fixedCosts.matchPayments();
        assertThat(status(cost)).isEqualTo(Status.PAID);
    }

    @Test
    void changingTheAmountMatchesAgainButTheOwnersUntickSurvivesTheUpdate() {
        Account account = everyday();
        FixedCost cost = energy();
        paidEnergy(account, "2026-06-15", -10000, "REF-1");
        fixedCosts.matchPayments();
        assertThat(status(cost)).isEqualTo(Status.PAID);

        fixedCosts.update(cost.id(), cost.name(), cost.scope(), eur("200.00"), cost.rhythm(), cost.firstDue(), cost.counterpartyName(),
                cost.counterpartyIban(), null);
        assertThat(status(cost)).as("the link no longer fits").isEqualTo(Status.STILL_TO_COME);
        assertThat(links()).isZero();

        fixedCosts.update(cost.id(), cost.name(), cost.scope(), eur("100.00"), cost.rhythm(), cost.firstDue(), cost.counterpartyName(),
                cost.counterpartyIban(), null);
        assertThat(status(cost)).as("and fits again").isEqualTo(Status.PAID);
        fixedCosts.untick(cost.id());
        fixedCosts.update(cost.id(), cost.name(), cost.scope(), eur("100.00"), cost.rhythm(), cost.firstDue(), cost.counterpartyName(),
                cost.counterpartyIban(), null);
        assertThat(status(cost)).as("the untick survived").isEqualTo(Status.STILL_TO_COME);
    }

    @Test
    void creatingAFixedCostMatchesAPaymentThatIsAlreadyThere() {
        Account account = everyday();
        clock.set(date("2026-06-12"));
        paidEnergy(account, "2026-06-10", -10000, "REF-1");

        FixedCost cost = fixedCosts.create("Example Energy", Scope.PRIVATE, eur("100.00"), Rhythm.MONTHLY, date("2026-01-15"), null,
                ENERGY_IBAN, null);

        assertThat(status(cost)).isEqualTo(Status.PAID);
    }

    @Test
    void removingAFixedCostRemovesItsLinksAndUntickedMonthsAndABankDeliveryStillRemovesPendingRows() {
        Account account = everyday();
        FixedCost cost = energy();
        paidEnergy(account, "2026-06-15", -10000, "REF-1");
        fixedCosts.matchPayments();
        fixedCosts.untick(cost.id());

        fixedCosts.remove(cost.id());

        assertThat(links()).isZero();
        assertThat(jdbc.sql("select count(*) from fixed_cost_unpaid").query(Integer.class).single()).isZero();
        assertThat(jdbc.sql("select count(*) from transactions").query(Integer.class).single()).as("the payment stays").isEqualTo(1);
    }

    @Test
    void theAgeOfStillToComeIsTheOldestLatestDeliveryOfTheAccountsInvolved() {
        Account everyday = everyday();
        Account second = accounts.addManual("Second", Scope.PRIVATE, Kind.CURRENT, "NL24XMPL0000000002");
        FixedCost cost = energy();
        assertThat(fixedCosts.summary(Scope.PRIVATE).basis()).hasValueSatisfying(b -> {
            assertThat(b.accountIds()).containsExactlyInAnyOrder(everyday.id(), second.id());
            assertThat(b.oldestDelivery()).as("nothing delivered yet").isEmpty();
        });

        clock.set(date("2026-06-10"));
        pay(everyday, "2026-06-01", -500, TransactionStatus.BOOKED, "Corner Grocery", "NL80SHOP0000000102", "REF-A");
        clock.set(date("2026-06-12"));
        pay(second, "2026-06-01", -500, TransactionStatus.BOOKED, "Corner Grocery", "NL80SHOP0000000102", "REF-B");

        assertThat(fixedCosts.summary(Scope.PRIVATE).basis().orElseThrow().oldestDelivery())
                .contains(date("2026-06-10").atTime(12, 0).toInstant(ZoneOffset.UTC));

        FixedCost tied = fixedCosts.create("Tied", Scope.PRIVATE, eur("5.00"), Rhythm.MONTHLY, date("2026-01-20"), null, "NL53SHOP0000000103",
                second.id());
        fixedCosts.remove(cost.id());
        assertThat(fixedCosts.summary(Scope.PRIVATE).basis().orElseThrow().accountIds()).containsExactly(second.id());
        assertThat(tied.accountId()).isEqualTo(second.id());
    }

    @Test
    void aScopeWhereNothingCanBeMatchedHasNoBasis() {
        clock.set(date("2026-06-12"));
        everyday();
        fixedCosts.create("By hand", Scope.PRIVATE, eur("100.00"), Rhythm.MONTHLY, date("2026-01-15"), null, null, null);

        assertThat(fixedCosts.summary(Scope.PRIVATE).basis()).isEmpty();
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
