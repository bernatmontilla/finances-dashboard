package finances.accounts;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import finances.accounts.FixedCostException.Reason;
import finances.money.Money;
import finances.money.Rhythm;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.sqlite.SQLiteConfig;
import org.sqlite.SQLiteDataSource;

/** Proposing fixed costs from repeating transactions on a real, migrated SQLite file, without the web layer. Fictional data only. */
class FixedCostProposalServiceTest {

    private static final String TELECOM_IBAN = "NL80SHOP0000000102";
    private static final String GYM_IBAN = "NL10SHOP0000000101";
    private static final String INSURANCE_IBAN = "NL53SHOP0000000103";
    private static final String SAVINGS_IBAN = "NL24XMPL0000000002";

    private Clock clock;
    private FixedCostProposalService proposals;
    private FixedCostService fixedCosts;
    private AccountService accounts;
    private TransactionService transactions;
    private JdbcClient jdbc;
    private Account everyday;
    private int references;

    @BeforeEach
    void open(@TempDir Path folder) {
        SQLiteConfig config = new SQLiteConfig();
        config.enforceForeignKeys(true);
        SQLiteDataSource dataSource = new SQLiteDataSource(config);
        dataSource.setUrl("jdbc:sqlite:" + folder.resolve("finances.db"));
        Flyway.configure().dataSource(dataSource).locations("classpath:db/migration").load().migrate();
        jdbc = JdbcClient.create(dataSource);
        clock = new FixedDay(LocalDate.of(2026, 12, 20));
        accounts = new AccountService(jdbc, clock);
        DataSourceTransactionManager manager = new DataSourceTransactionManager(dataSource);
        transactions = new TransactionService(jdbc, clock, manager);
        fixedCosts = new FixedCostService(jdbc, clock, accounts, transactions);
        proposals = new FixedCostProposalService(jdbc, clock, accounts, manager);
        everyday = accounts.addManual("Everyday", Scope.PRIVATE, Kind.CURRENT, "NL51XMPL0000000001");
    }

    private long pay(Account account, String day, long cents, TransactionStatus status, String name, String iban) {
        String reference = "REF-" + references++;
        transactions.deliver(account.id(), Source.CSV, List.of(new DeliveredTransaction(LocalDate.parse(day), null, cents, "EUR", name, iban,
                "Fictional payment", status, reference, "raw " + reference)));
        return jdbc.sql("select id from transactions where reference = ?").param(reference).query(Long.class).single();
    }

    private void charge(String day, long cents, String name, String iban) {
        pay(everyday, day, cents, TransactionStatus.BOOKED, name, iban);
    }

    private void monthly(int fromMonth, int toMonth, int day, long cents, String name, String iban) {
        for (int month = fromMonth; month <= toMonth; month++) {
            charge(String.format("2026-%02d-%02d", month, day), cents, name, iban);
        }
    }

    /** The fictional year: monthly, quarterly and yearly charges, weekly groceries and a transfer to the owner's own savings account. */
    private void theFictionalYear() {
        accounts.addManual("Savings", Scope.PRIVATE, Kind.SAVINGS, SAVINGS_IBAN);
        monthly(1, 12, 3, -2000, "Example Telecom", TELECOM_IBAN);
        monthly(1, 5, 5, -3000, "Example Gym", GYM_IBAN);
        charge("2026-06-05", -3100, "Example Gym", GYM_IBAN);
        monthly(7, 12, 5, -3000, "Example Gym", GYM_IBAN);
        for (String day : new String[] {"2026-01-10", "2026-04-10", "2026-07-10", "2026-10-10"}) {
            charge(day, -6000, "Example Insurance", INSURANCE_IBAN);
        }
        charge("2025-12-15", -10000, "Example Energy", null);
        charge("2026-12-14", -10000, "Example Energy", null);
        LocalDate saturday = LocalDate.of(2026, 1, 3);
        long cents = 1234;
        while (saturday.isBefore(LocalDate.of(2026, 12, 20))) {
            charge(saturday.toString(), -cents, "Corner Grocery", null);
            cents = cents == 1234 ? 2345 : 1234;
            saturday = saturday.plusWeeks(1);
        }
        monthly(1, 12, 25, -10000, "Sam Voorbeeld savings", SAVINGS_IBAN);
    }

    private static Money eur(String typed) {
        return Money.parse(typed);
    }

    private List<String> summary() {
        return proposals.proposals().stream().map(p -> p.name() + ":" + p.rhythm().code() + ":" + p.amount().format()).toList();
    }

    private void refuse(Reason reason, org.assertj.core.api.ThrowableAssert.ThrowingCallable call) {
        assertThatThrownBy(call).isInstanceOfSatisfying(FixedCostException.class, e -> assertThat(e.reason()).isEqualTo(reason));
    }

    private Map<String, Integer> ownerTables() {
        return List.of("fixed_cost", "fixed_cost_paid", "fixed_cost_unpaid", "fixed_cost_match", "fixed_cost_skipped", "fixed_cost_price_kept",
                "fixed_cost_rejection", "transactions").stream().collect(Collectors.toMap(t -> t,
                        t -> jdbc.sql("select count(*) from " + t).query(Integer.class).single()));
    }

    // ----- the fictional year

    @Test
    void theFictionalYearGivesTheFourProposalsAndNeitherTheGroceriesNorTheTransferToOwnSavings() {
        theFictionalYear();

        assertThat(proposals.detect()).isEqualTo(4);

        assertThat(summary()).containsExactlyInAnyOrder("Example Telecom:monthly:€20.00", "Example Gym:monthly:€30.00",
                "Example Insurance:quarterly:€60.00", "Example Energy:yearly:€100.00");
        FixedCostProposal telecom = proposals.proposals().stream().filter(p -> p.name().equals("Example Telecom")).findFirst().orElseThrow();
        assertThat(telecom.charges()).isEqualTo(12);
        assertThat(telecom.latestBooked()).isEqualTo(LocalDate.of(2026, 12, 3));
        assertThat(telecom.firstDue()).isEqualTo(LocalDate.of(2026, 12, 3));
        assertThat(telecom.counterpartyIban()).isEqualTo(TELECOM_IBAN);
        assertThat(telecom.scope()).isEqualTo(Scope.PRIVATE);
        FixedCostProposal energy = proposals.proposals().stream().filter(p -> p.name().equals("Example Energy")).findFirst().orElseThrow();
        assertThat(energy.counterpartyIban()).isNull();
        assertThat(energy.counterpartyName()).isEqualTo("Example Energy");
    }

    @Test
    void aMonthAtAnotherPriceWithinATenthDoesNotBreakTheRunAndTheNewestAmountIsProposed() {
        monthly(8, 10, 5, -3000, "Example Gym", GYM_IBAN);
        charge("2026-11-05", -3100, "Example Gym", GYM_IBAN);
        charge("2026-12-05", -3000, "Example Gym", GYM_IBAN);
        proposals.detect();
        assertThat(summary()).containsExactly("Example Gym:monthly:€30.00");
    }

    @Test
    void detectingTwiceStoresTheSameRowsWithTheSameIdsAndWritesNothingOfTheOwner() {
        theFictionalYear();
        proposals.detect();
        Map<String, Integer> before = ownerTables();
        List<Long> ids = proposals.proposals().stream().map(FixedCostProposal::id).sorted().toList();
        int charges = jdbc.sql("select count(*) from fixed_cost_proposal_charge").query(Integer.class).single();

        proposals.detect();

        assertThat(proposals.proposals().stream().map(FixedCostProposal::id).sorted().toList()).isEqualTo(ids);
        assertThat(jdbc.sql("select count(*) from fixed_cost_proposal_charge").query(Integer.class).single()).isEqualTo(charges);
        assertThat(jdbc.sql("select count(*) from fixed_cost_proposal").query(Integer.class).single()).isEqualTo(4);
        assertThat(ownerTables()).isEqualTo(before);
    }

    @Test
    void aProposalThatIsNoLongerFoundIsRemovedWithItsCharges() {
        monthly(9, 12, 3, -2000, "Example Telecom", TELECOM_IBAN);
        proposals.detect();
        assertThat(proposals.proposals()).hasSize(1);

        clockAt("2027-03-01");
        proposals.detect();

        assertThat(proposals.proposals()).isEmpty();
        assertThat(jdbc.sql("select count(*) from fixed_cost_proposal_charge").query(Integer.class).single()).isZero();
    }

    // ----- what detection reads

    @Test
    void pendingIncomeOldAndPaymentsToOwnAccountsAreIgnoredAndArchivedAccountsCount() {
        for (String day : new String[] {"2026-10-03", "2026-11-03", "2026-12-03"}) {
            pay(everyday, day, -2000, TransactionStatus.PENDING, "Example Telecom", TELECOM_IBAN);
            pay(everyday, day, 2000, TransactionStatus.BOOKED, "Example Client BV", "NL67XMPL0000000004");
        }
        for (String day : new String[] {"2024-10-03", "2024-11-03", "2024-12-03"}) {
            charge(day, -2000, "Example Gym", GYM_IBAN);
        }
        Account savings = accounts.addManual("Savings", Scope.PRIVATE, Kind.SAVINGS, SAVINGS_IBAN);
        accounts.archive(savings.id());
        for (String day : new String[] {"2026-10-03", "2026-11-03", "2026-12-03"}) {
            charge(day, -5000, "Sam Voorbeeld", SAVINGS_IBAN.toLowerCase());
        }
        proposals.detect();
        assertThat(summary()).isEmpty();

        Account old = accounts.addManual("Old everyday", Scope.PRIVATE, Kind.CURRENT, null);
        for (String day : new String[] {"2026-10-05", "2026-11-05", "2026-12-05"}) {
            pay(old, day, -1500, TransactionStatus.BOOKED, "Example Insurance", INSURANCE_IBAN);
        }
        accounts.archive(old.id());
        proposals.detect();
        assertThat(summary()).containsExactly("Example Insurance:monthly:€15.00");
    }

    @Test
    void theSameCounterpartyOnAPrivateAndABusinessAccountGivesTwoProposals() {
        Account business = accounts.addManual("Business", Scope.BUSINESS, Kind.CURRENT, "NL94XMPL0000000003");
        for (String day : new String[] {"2026-10-03", "2026-11-03", "2026-12-03"}) {
            charge(day, -2000, "Example Telecom", TELECOM_IBAN);
            pay(business, day, -2000, TransactionStatus.BOOKED, "Example Telecom", TELECOM_IBAN);
        }
        proposals.detect();

        assertThat(proposals.proposals()).extracting(FixedCostProposal::scope).containsExactly(Scope.PRIVATE, Scope.BUSINESS);
    }

    @Test
    void aChargeWithNeitherAnIbanNorANameHasNoCounterpartyAndIsLeftOut() {
        for (String day : new String[] {"2026-10-03", "2026-11-03", "2026-12-03"}) {
            charge(day, -2000, null, null);
        }
        proposals.detect();
        assertThat(proposals.proposals()).isEmpty();
    }

    // ----- what is never proposed

    @Test
    void aCounterpartyThatHasAFixedCostOfTheScopeIsNeverProposedWhateverItsStateAndCreatingOneHidesTheProposalAtOnce() {
        theFictionalYear();
        proposals.detect();
        assertThat(proposals.proposals()).hasSize(4);

        fixedCosts.create("Telecom", Scope.PRIVATE, eur("20.00"), Rhythm.MONTHLY, LocalDate.of(2026, 12, 3), null, TELECOM_IBAN, null);
        assertThat(summary()).as("hidden at once, without a new detect()").hasSize(3).noneMatch(s -> s.contains("Telecom"));

        FixedCost gym = fixedCosts.create("Gym", Scope.PRIVATE, eur("30.00"), Rhythm.MONTHLY, LocalDate.of(2026, 12, 5), "example  GYM", null, null);
        fixedCosts.pause(gym.id());
        FixedCost insurance = fixedCosts.create("Insurance", Scope.PRIVATE, eur("60.00"), Rhythm.QUARTERLY, LocalDate.of(2026, 10, 10), null,
                INSURANCE_IBAN.toLowerCase(), null);
        fixedCosts.end(insurance.id(), LocalDate.of(2026, 12, 1));
        proposals.detect();

        assertThat(summary()).containsExactly("Example Energy:yearly:€100.00");
        assertThat(jdbc.sql("select count(*) from fixed_cost_proposal").query(Integer.class).single()).isEqualTo(1);
    }

    @Test
    void aFixedCostOfTheOtherScopeDoesNotHideAProposal() {
        monthly(8, 12, 3, -2000, "Example Telecom", TELECOM_IBAN);
        proposals.detect();
        fixedCosts.create("Telecom", Scope.BUSINESS, eur("20.00"), Rhythm.MONTHLY, LocalDate.of(2026, 12, 3), null, TELECOM_IBAN, null);

        assertThat(proposals.proposals()).hasSize(1);
    }

    // ----- rejections

    @Test
    void aRejectedProposalIsNotProposedAgainAfterDetectionButAPriceChangeBeyondTheToleranceProposesItAgain() {
        monthly(8, 12, 3, -2000, "Example Telecom", TELECOM_IBAN);
        proposals.detect();
        long id = proposals.proposals().getFirst().id();

        proposals.reject(id);

        assertThat(proposals.proposals()).isEmpty();
        proposals.detect();
        proposals.detect();
        assertThat(proposals.proposals()).isEmpty();
        assertThat(proposals.rejections()).singleElement().satisfies(r -> {
            assertThat(r.name()).isEqualTo("Example Telecom");
            assertThat(r.amount()).isEqualTo(eur("20.00"));
            assertThat(r.counterpartyIban()).isEqualTo(TELECOM_IBAN);
        });

        for (String day : new String[] {"2027-01-03", "2027-02-03", "2027-03-03"}) {
            clockAt(day);
            charge(day, -2200, "Example Telecom", TELECOM_IBAN);
        }
        proposals.detect();
        assertThat(proposals.proposals()).as("2.00 more is exactly a tenth: still rejected").isEmpty();

        clockAt("2027-04-03");
        charge("2027-04-03", -2201, "Example Telecom", TELECOM_IBAN);
        proposals.detect();
        assertThat(proposals.proposals()).as("one cent more than a tenth of the rejected amount").hasSize(1);
        assertThat(proposals.rejections()).hasSize(1);
    }

    @Test
    void undoingARejectionBringsTheProposalBackAndAnUnknownRejectionIsRefused() {
        monthly(8, 12, 3, -2000, "Example Telecom", TELECOM_IBAN);
        proposals.detect();
        proposals.reject(proposals.proposals().getFirst().id());
        long rejection = proposals.rejections().getFirst().id();

        proposals.undoRejection(rejection);

        assertThat(proposals.rejections()).isEmpty();
        assertThat(summary()).containsExactly("Example Telecom:monthly:€20.00");
        refuse(Reason.REJECTION_NOT_FOUND, () -> proposals.undoRejection(rejection));
        refuse(Reason.REJECTION_NOT_FOUND, () -> proposals.undoRejection(4711));
    }

    @Test
    void aProposalThatIsNotOpenCannotBeRejectedAndIsNotFound() {
        monthly(8, 12, 3, -2000, "Example Telecom", TELECOM_IBAN);
        proposals.detect();
        long id = proposals.proposals().getFirst().id();
        fixedCosts.create("Telecom", Scope.PRIVATE, eur("20.00"), Rhythm.MONTHLY, LocalDate.of(2026, 12, 3), null, TELECOM_IBAN, null);

        assertThat(proposals.open(id)).isEmpty();
        refuse(Reason.PROPOSAL_NOT_OPEN, () -> proposals.reject(id));
        refuse(Reason.PROPOSAL_NOT_OPEN, () -> proposals.reject(4711));
        assertThat(proposals.rejections()).isEmpty();
    }

    @Test
    void aRejectionByNameIsNotLostWhenTheChargesChangeTheirCase() {
        for (String day : new String[] {"2026-10-03", "2026-11-03", "2026-12-03"}) {
            charge(day, -2000, "Example  Energy", null);
        }
        proposals.detect();
        proposals.reject(proposals.proposals().getFirst().id());
        charge("2027-01-03", -2000, "EXAMPLE ENERGY", null);
        clockAt("2027-01-04");

        proposals.detect();

        assertThat(proposals.proposals()).isEmpty();
    }

    // ----- the clock

    private void clockAt(String day) {
        ((FixedDay) clock).set(LocalDate.parse(day));
    }

    /** A clock the test can move. */
    private static final class FixedDay extends Clock {
        private volatile Instant now;

        FixedDay(LocalDate day) {
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
