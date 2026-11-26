package finances.accounts;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import finances.accounts.ReservationException.Reason;
import finances.money.Money;
import finances.money.Reserving;
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
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.sqlite.SQLiteConfig;
import org.sqlite.SQLiteDataSource;

/** The reservation rules on a real, migrated SQLite file, without the web layer. Fictional data only. */
class ReservationServiceTest {

    private static final LocalDate JAN_1 = LocalDate.of(2026, 1, 1);
    private static final LocalDate DEC_31 = LocalDate.of(2026, 12, 31);

    private TestClock clock;
    private ReservationService reservations;
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
        clock = new TestClock(JAN_1);
        accounts = new AccountService(jdbc, clock);
        transactions = new TransactionService(jdbc, clock, new DataSourceTransactionManager(dataSource));
        reservations = new ReservationService(jdbc, clock, accounts, transactions);
    }

    private static Money eur(String typed) {
        return Money.parse(typed);
    }

    private Reservation premium() {
        return reservations.create("Example Insurance premium", Scope.PRIVATE, eur("1200.00"), DEC_31, false, null);
    }

    private Money reserved(Reservation reservation) {
        return reservations.standing(reservations.get(reservation.id())).reserved();
    }

    // ----- create

    @Test
    void createsAReservationThatStartsAtZeroAndTrimsTheName() {
        Reservation created = reservations.create("  Example Insurance premium  ", Scope.PRIVATE, eur("1.200,00"), DEC_31, false, null);

        assertThat(created.name()).isEqualTo("Example Insurance premium");
        assertThat(created.amount()).isEqualTo(eur("1200.00"));
        assertThat(created.anchorDate()).isEqualTo(JAN_1);
        assertThat(created.anchorAmount()).isEqualTo(Money.zero());
        assertThat(created.isOpen()).isTrue();
        assertThat(created.isUntouched()).isTrue();
        ReservationStanding standing = reservations.standing(created);
        assertThat(standing.reserved()).isEqualTo(Money.zero());
        assertThat(standing.perMonth()).contains(eur("100.00"));
        assertThat(standing.overdue()).isFalse();
    }

    @Test
    void anAlreadyReservedAmountIsTheStart() {
        Reservation created = reservations.create("Holiday", Scope.PRIVATE, eur("1200.00"), DEC_31, false, eur("600.00"));
        assertThat(created.anchorAmount()).isEqualTo(eur("600.00"));
        assertThat(reserved(created)).isEqualTo(eur("600.00"));
    }

    @Test
    void createRefusesEachBadInputWithItsOwnReason() {
        assertRefused(Reason.NAME_REQUIRED, () -> reservations.create("   ", Scope.PRIVATE, eur("1.00"), DEC_31, false, null));
        assertRefused(Reason.NAME_REQUIRED, () -> reservations.create(null, Scope.PRIVATE, eur("1.00"), DEC_31, false, null));
        assertRefused(Reason.NAME_TOO_LONG, () -> reservations.create("x".repeat(101), Scope.PRIVATE, eur("1.00"), DEC_31, false, null));
        assertRefused(Reason.AMOUNT_NOT_POSITIVE, () -> reservations.create("A", Scope.PRIVATE, Money.zero(), DEC_31, false, null));
        assertRefused(Reason.AMOUNT_NOT_POSITIVE, () -> reservations.create("A", Scope.PRIVATE, eur("-5.00"), DEC_31, false, null));
        assertRefused(Reason.AMOUNT_TOO_LARGE, () -> reservations.create("A", Scope.PRIVATE, eur("100000000.01"), DEC_31, false, null));
        assertRefused(Reason.DUE_DATE_REQUIRED, () -> reservations.create("A", Scope.PRIVATE, eur("1.00"), null, false, null));
        assertRefused(Reason.DUE_DATE_IN_PAST, () -> reservations.create("A", Scope.PRIVATE, eur("1.00"), JAN_1.minusDays(1), false, null));
        assertRefused(Reason.DUE_DATE_TOO_FAR, () -> reservations.create("A", Scope.PRIVATE, eur("1.00"), JAN_1.plusYears(50).plusDays(1), false, null));
        assertRefused(Reason.INVALID_SCOPE, () -> reservations.create("A", null, eur("1.00"), DEC_31, false, null));
        assertRefused(Reason.RESERVED_OUT_OF_RANGE, () -> reservations.create("A", Scope.PRIVATE, eur("10.00"), DEC_31, false, eur("10.01")));
        assertRefused(Reason.RESERVED_OUT_OF_RANGE, () -> reservations.create("A", Scope.PRIVATE, eur("10.00"), DEC_31, false, eur("-1.00")));
        assertThat(reservations.open(Scope.PRIVATE)).isEmpty();
    }

    @Test
    void theLimitsThemselvesAreAccepted() {
        reservations.create("x".repeat(100), Scope.PRIVATE, eur("0.01"), JAN_1, false, null);
        reservations.create("Largest", Scope.BUSINESS, eur("100000000.00"), JAN_1.plusYears(50), false, null);
        assertThat(reservations.open(Scope.PRIVATE)).hasSize(1);
        assertThat(reservations.open(Scope.BUSINESS)).hasSize(1);
    }

    private static void assertRefused(Reason reason, Runnable action) {
        assertThatThrownBy(action::run).isInstanceOfSatisfying(ReservationException.class, e -> assertThat(e.reason()).isEqualTo(reason));
    }

    // ----- the build-up over time

    @Test
    void reservedSoFarGrowsEvenlyAndIsFullOnTheDueDate() {
        Reservation created = premium();
        clock.set(LocalDate.of(2026, 7, 2));
        assertThat(reserved(created)).isEqualTo(eur("600.00"));
        clock.set(DEC_31);
        assertThat(reserved(created)).isEqualTo(eur("1200.00"));
        assertThat(reservations.standing(created).perMonth()).isEmpty();
    }

    @Test
    void anOpenReservationPastItsDueDateStaysFullyReservedAndIsOverdue() {
        Reservation created = premium();
        clock.set(DEC_31.plusDays(1));
        ReservationStanding standing = reservations.standing(created);
        assertThat(standing.reserved()).isEqualTo(eur("1200.00"));
        assertThat(standing.overdue()).isTrue();
        assertThat(reservations.open(Scope.PRIVATE)).extracting(Reservation::id).containsExactly(created.id());
        clock.set(DEC_31.plusYears(3));
        assertThat(reservations.standing(created).reserved()).isEqualTo(eur("1200.00"));
        assertThat(reservations.standing(created).overdue()).isTrue();
    }

    // ----- change

    @Test
    void changingTheAmountHalfwayKeepsWhatWasReservedAndSpreadsTheRest() {
        Reservation created = premium();
        clock.set(LocalDate.of(2026, 7, 2)); // 600.00 reserved
        Reservation changed = reservations.update(created.id(), "Example Insurance premium", Scope.PRIVATE, eur("2400.00"), DEC_31, false);

        assertThat(changed.anchorDate()).isEqualTo(LocalDate.of(2026, 7, 2));
        assertThat(changed.anchorAmount()).isEqualTo(eur("600.00"));
        assertThat(changed.changedAt()).isNotNull();
        assertThat(reserved(changed)).isEqualTo(eur("600.00"));
        // 1,800.00 is left to build over the 182 days left: halfway through that, 600 + 900.
        clock.set(LocalDate.of(2026, 10, 1)); // 91 of 182 days
        assertThat(reserved(changed)).isEqualTo(eur("1500.00"));
        assertThat(reservations.standing(changed).perMonth()).isPresent();
    }

    @Test
    void loweringTheAmountBelowWhatIsReservedCapsItAtTheNewAmount() {
        Reservation created = premium();
        clock.set(LocalDate.of(2026, 7, 2)); // 600.00 reserved
        Reservation changed = reservations.update(created.id(), "Example Insurance premium", Scope.PRIVATE, eur("400.00"), DEC_31, false);
        assertThat(changed.anchorAmount()).isEqualTo(eur("400.00"));
        assertThat(reserved(changed)).isEqualTo(eur("400.00"));
        clock.set(DEC_31);
        assertThat(reserved(changed)).isEqualTo(eur("400.00"));
    }

    @Test
    void movingTheDueDateLaterLowersThePerMonthFigureAndLeavesWhatIsReservedAlone() {
        Reservation created = premium();
        clock.set(LocalDate.of(2026, 7, 2));
        Money before = reservations.standing(reservations.get(created.id())).perMonth().orElseThrow();
        Reservation changed = reservations.update(created.id(), "Example Insurance premium", Scope.PRIVATE, eur("1200.00"), DEC_31.plusYears(1), false);

        assertThat(reserved(changed)).isEqualTo(eur("600.00"));
        Money after = reservations.standing(changed).perMonth().orElseThrow();
        assertThat(after.cents()).isLessThan(before.cents());
    }

    @Test
    void changingOnlyTheNameScopeOrYearlyFlagKeepsTheAnchor() {
        Reservation created = premium();
        clock.set(LocalDate.of(2026, 7, 2));
        Reservation changed = reservations.update(created.id(), "Renamed", Scope.BUSINESS, eur("1200.00"), DEC_31, true);
        assertThat(changed.anchorDate()).isEqualTo(JAN_1);
        assertThat(changed.anchorAmount()).isEqualTo(Money.zero());
        assertThat(changed.scope()).isEqualTo(Scope.BUSINESS);
        assertThat(changed.yearly()).isTrue();
        assertThat(changed.changedAt()).isNotNull();
        assertThat(reserved(changed)).isEqualTo(eur("600.00"));
    }

    @Test
    void anOverdueReservationCanBeRenamedWithoutMovingItsDueDate() {
        Reservation created = premium();
        clock.set(DEC_31.plusDays(10));
        Reservation changed = reservations.update(created.id(), "Renamed", Scope.PRIVATE, eur("1200.00"), DEC_31, false);
        assertThat(changed.name()).isEqualTo("Renamed");
        assertRefused(Reason.DUE_DATE_IN_PAST, () -> reservations.update(created.id(), "Renamed", Scope.PRIVATE, eur("1200.00"), DEC_31.minusDays(1), false));
    }

    @Test
    void setReservedByHandReAnchorsAndRefusesWhatIsOutOfRange() {
        Reservation created = premium();
        clock.set(LocalDate.of(2026, 7, 2));
        Reservation changed = reservations.setReserved(created.id(), eur("1000.00"));
        assertThat(changed.anchorDate()).isEqualTo(LocalDate.of(2026, 7, 2));
        assertThat(changed.anchorAmount()).isEqualTo(eur("1000.00"));
        assertThat(changed.changedAt()).isNotNull();
        assertThat(reserved(changed)).isEqualTo(eur("1000.00"));

        assertRefused(Reason.RESERVED_OUT_OF_RANGE, () -> reservations.setReserved(created.id(), eur("1200.01")));
        assertRefused(Reason.RESERVED_OUT_OF_RANGE, () -> reservations.setReserved(created.id(), eur("-0.01")));
        assertThat(reservations.setReserved(created.id(), Money.zero()).anchorAmount()).isEqualTo(Money.zero());
        // From the due date on setting it is refused (issue #51), so the full amount is checked on the day itself, after the rest.
        clock.set(DEC_31);
        assertThat(reserved(created)).isEqualTo(eur("1200.00"));
    }

    @Test
    void aClosedReservationCannotBeEdited() {
        Reservation created = premium();
        reservations.close(created.id());
        assertRefused(Reason.CLOSED, () -> reservations.update(created.id(), "A", Scope.PRIVATE, eur("1.00"), DEC_31, false));
        assertRefused(Reason.CLOSED, () -> reservations.setReserved(created.id(), eur("1.00")));
    }

    // ----- close, reopen, remove

    @Test
    void closingAYearlyReservationStartsNextYearsRound() {
        Reservation created = reservations.create("Example Insurance premium", Scope.BUSINESS, eur("1200.00"), DEC_31, true, null);
        clock.set(LocalDate.of(2026, 12, 20));
        reservations.close(created.id());

        assertThat(reservations.get(created.id()).isOpen()).isFalse();
        List<Reservation> open = reservations.open(Scope.BUSINESS);
        assertThat(open).singleElement().satisfies(next -> {
            assertThat(next.name()).isEqualTo("Example Insurance premium");
            assertThat(next.amount()).isEqualTo(eur("1200.00"));
            assertThat(next.scope()).isEqualTo(Scope.BUSINESS);
            assertThat(next.yearly()).isTrue();
            assertThat(next.dueDate()).isEqualTo(DEC_31.plusYears(1));
            assertThat(next.anchorDate()).isEqualTo(LocalDate.of(2026, 12, 20));
            assertThat(next.anchorAmount()).isEqualTo(Money.zero());
            assertThat(next.followsId()).isEqualTo(created.id());
            assertThat(next.changedAt()).isNull();
            assertThat(reserved(next)).isEqualTo(Money.zero());
        });
        assertThat(reservations.closed(Scope.BUSINESS)).extracting(Reservation::id).containsExactly(created.id());
    }

    @Test
    void aYearlyReservationDueOnTheTwentyNinthOfFebruaryContinuesOnTheTwentyEighth() {
        Reservation created = reservations.create("Leap", Scope.PRIVATE, eur("10.00"), LocalDate.of(2028, 2, 29), true, null);
        reservations.close(created.id());
        assertThat(reservations.open(Scope.PRIVATE)).singleElement().satisfies(next -> assertThat(next.dueDate()).isEqualTo(LocalDate.of(2029, 2, 28)));
    }

    @Test
    void closingAOneOffStartsNothing() {
        Reservation created = premium();
        reservations.close(created.id());
        assertThat(reservations.open(Scope.PRIVATE)).isEmpty();
        assertThat(reservations.closed(Scope.PRIVATE)).hasSize(1);
        assertRefused(Reason.ALREADY_CLOSED, () -> reservations.close(created.id()));
    }

    @Test
    void reopeningRemovesAnUntouchedNextRound() {
        Reservation created = reservations.create("Example Insurance premium", Scope.PRIVATE, eur("1200.00"), DEC_31, true, null);
        reservations.close(created.id());
        Reservation reopened = reservations.reopen(created.id());

        assertThat(reopened.isOpen()).isTrue();
        assertThat(reservations.open(Scope.PRIVATE)).extracting(Reservation::id).containsExactly(created.id());
        assertThat(count("select count(*) from reservation")).isEqualTo(1);
        // And closing again starts a fresh round.
        reservations.close(created.id());
        assertThat(reservations.open(Scope.PRIVATE)).hasSize(1);
    }

    @Test
    void reopeningIsRefusedAndChangesNothingWhenTheNextRoundWasEditedOrClosed() {
        Reservation created = reservations.create("Example Insurance premium", Scope.PRIVATE, eur("1200.00"), DEC_31, true, null);
        reservations.close(created.id());
        Reservation next = reservations.open(Scope.PRIVATE).getFirst();

        reservations.setReserved(next.id(), eur("10.00"));
        assertRefused(Reason.NEXT_ROUND_CHANGED, () -> reservations.reopen(created.id()));
        assertThat(reservations.get(created.id()).isOpen()).isFalse();
        assertThat(reservations.get(next.id()).anchorAmount()).isEqualTo(eur("10.00"));

        Reservation other = reservations.create("Second", Scope.PRIVATE, eur("100.00"), DEC_31, true, null);
        reservations.close(other.id());
        Reservation otherNext = reservations.open(Scope.PRIVATE).stream().filter(r -> r.followsId() != null && r.followsId() == other.id()).findFirst().orElseThrow();
        reservations.close(otherNext.id()); // a closed next round, which itself starts a round
        assertRefused(Reason.NEXT_ROUND_CHANGED, () -> reservations.reopen(other.id()));
        assertThat(reservations.get(other.id()).isOpen()).isFalse();
    }

    @Test
    void reopeningAnOpenReservationIsRefused() {
        Reservation created = premium();
        assertRefused(Reason.NOT_CLOSED, () -> reservations.reopen(created.id()));
    }

    @Test
    void removingAClosedReservationIsRefusedSoItsRoundsAndHistoryStay() {
        Reservation created = reservations.create("Example Insurance premium", Scope.PRIVATE, eur("1200.00"), DEC_31, true, null);
        reservations.close(created.id());
        Reservation next = reservations.open(Scope.PRIVATE).getFirst();

        assertRefused(Reason.CLOSED, () -> reservations.remove(created.id()));

        assertThat(reservations.find(created.id())).isPresent();
        assertThat(reservations.get(next.id()).followsId()).isEqualTo(created.id());
    }

    @Test
    void anOpenReservationCanBeRemoved() {
        Reservation created = premium();

        reservations.remove(created.id());

        assertThat(reservations.find(created.id())).isEmpty();
        assertRefused(Reason.NOT_FOUND, () -> reservations.remove(created.id()));
    }

    @Test
    void aMissingReservationIsNotFound() {
        assertRefused(Reason.NOT_FOUND, () -> reservations.get(42));
        assertRefused(Reason.NOT_FOUND, () -> reservations.close(42));
    }

    // ----- setting what is reserved by hand

    @Test
    void settingWhatIsReservedIsRefusedOnTheDueDayAndWhenOverdueAndStillWorksTheDayBefore() {
        Reservation created = reservations.create("Holiday", Scope.PRIVATE, eur("1200.00"), JAN_1.plusDays(30), false, null);

        clock.set(JAN_1.plusDays(29));
        reservations.setReserved(created.id(), eur("300.00"));
        assertThat(reservations.get(created.id()).anchorAmount()).isEqualTo(eur("300.00"));

        clock.set(JAN_1.plusDays(30));
        assertRefused(Reason.DUE_NOT_SETTABLE, () -> reservations.setReserved(created.id(), eur("400.00")));
        clock.set(JAN_1.plusDays(45));
        assertRefused(Reason.DUE_NOT_SETTABLE, () -> reservations.setReserved(created.id(), eur("400.00")));

        assertThat(reservations.get(created.id()).anchorAmount()).isEqualTo(eur("300.00"));
        assertThat(reservations.get(created.id()).anchorDate()).isEqualTo(JAN_1.plusDays(29));
    }

    // ----- closing against a payment (issue #51)

    private Account everydayAccount;
    private Account businessAccount;

    /** The private current account, created on first use. */
    private Account everyday() {
        if (everydayAccount == null) {
            everydayAccount = accounts.addManual("Everyday", Scope.PRIVATE, Kind.CURRENT, "NL51XMPL0000000001");
        }
        return everydayAccount;
    }

    private Account business() {
        if (businessAccount == null) {
            businessAccount = accounts.addManual("Business", Scope.BUSINESS, Kind.CURRENT, "NL94XMPL0000000003");
        }
        return businessAccount;
    }

    private long deliver(Account account, LocalDate date, long cents, TransactionStatus status, String reference) {
        DeliveredTransaction delivered = new DeliveredTransaction(date, null, cents, "EUR", "Example Insurance", "NL10SHOP0000000101",
                "Premium", status, status == TransactionStatus.BOOKED ? reference : null, "raw:" + reference);
        transactions.deliver(account.id(), Source.CSV, List.of(delivered));
        return jdbc.sql("select max(id) from transactions where account_id = ?").param(account.id()).query(Long.class).single();
    }

    private long spend(Account account, LocalDate date, long cents, String reference) {
        return deliver(account, date, -cents, TransactionStatus.BOOKED, reference);
    }

    private Long link(Reservation reservation) {
        return reservations.get(reservation.id()).transactionId();
    }

    @Test
    void aReservationClosedAgainstABookedPaymentKeepsTheLink() {
        Reservation created = premium();
        long payment = spend(everyday(), JAN_1.minusDays(2), 123456, "REF-1");

        Reservation closed = reservations.close(created.id(), payment);

        assertThat(closed.isOpen()).isFalse();
        assertThat(closed.transactionId()).isEqualTo(payment);
        assertThat(link(created)).isEqualTo(payment);
    }

    @Test
    void closingWithoutATransactionLeavesTheLinkEmpty() {
        Reservation first = premium();
        Reservation second = reservations.create("Second", Scope.PRIVATE, eur("10.00"), DEC_31, false, null);

        assertThat(reservations.close(first.id()).transactionId()).isNull();
        assertThat(reservations.close(second.id(), null).transactionId()).isNull();
    }

    @Test
    void aPaymentThatCannotSettleItIsRefusedAndNothingChanges() {
        Reservation created = premium();
        Account everyday = everyday();
        long pending = deliver(everyday, JAN_1, -5000, TransactionStatus.PENDING, "REF-P");
        long income = deliver(everyday, JAN_1, 5000, TransactionStatus.BOOKED, "REF-I");
        long otherScope = spend(business(), JAN_1, 5000, "REF-B");

        assertRefused(Reason.TRANSACTION_NOT_BOOKED, () -> reservations.close(created.id(), pending));
        assertRefused(Reason.TRANSACTION_NOT_SPENDING, () -> reservations.close(created.id(), income));
        assertRefused(Reason.TRANSACTION_OUT_OF_SCOPE, () -> reservations.close(created.id(), otherScope));
        assertRefused(Reason.TRANSACTION_NOT_FOUND, () -> reservations.close(created.id(), 9999L));

        assertThat(reservations.get(created.id()).isOpen()).isTrue();
        assertThat(link(created)).isNull();
    }

    @Test
    void closingAClosedReservationAgainIsRefusedAndKeepsTheFirstLink() {
        Reservation created = premium();
        Account everyday = everyday();
        long first = spend(everyday, JAN_1, 100, "REF-1");
        long second = spend(everyday, JAN_1, 200, "REF-2");
        reservations.close(created.id(), first);

        assertRefused(Reason.ALREADY_CLOSED, () -> reservations.close(created.id(), second));

        assertThat(link(created)).isEqualTo(first);
        assertRefused(Reason.NOT_FOUND, () -> reservations.close(42, first));
    }

    @Test
    void aPaymentFromAnArchivedAccountOfTheScopeIsAccepted() {
        Reservation created = premium();
        Account everyday = everyday();
        long payment = spend(everyday, JAN_1, 100, "REF-1");
        accounts.archive(everyday.id());

        assertThat(reservations.close(created.id(), payment).transactionId()).isEqualTo(payment);
    }

    @Test
    void oneTransactionCanSettleTwoReservations() {
        Reservation one = premium();
        Reservation two = reservations.create("Second", Scope.PRIVATE, eur("10.00"), DEC_31, false, null);
        long payment = spend(everyday(), JAN_1, 1210, "REF-1");

        reservations.close(one.id(), payment);
        reservations.close(two.id(), payment);

        assertThat(link(one)).isEqualTo(payment);
        assertThat(link(two)).isEqualTo(payment);
    }

    @Test
    void closingAYearlyReservationAgainstAPaymentStartsAnUnlinkedNextRound() {
        Reservation created = reservations.create("Example Insurance premium", Scope.PRIVATE, eur("1200.00"), DEC_31, true, null);
        long payment = spend(everyday(), JAN_1, 120000, "REF-1");

        reservations.close(created.id(), payment);

        Reservation next = reservations.open(Scope.PRIVATE).getFirst();
        assertThat(next.followsId()).isEqualTo(created.id());
        assertThat(next.transactionId()).isNull();
        assertThat(link(created)).isEqualTo(payment);
    }

    @Test
    void reopeningClearsTheLinkAndClosingAgainWithoutAPaymentLeavesItEmpty() {
        Reservation created = premium();
        long payment = spend(everyday(), JAN_1, 120000, "REF-1");
        reservations.close(created.id(), payment);

        Reservation reopened = reservations.reopen(created.id());
        assertThat(reopened.isOpen()).isTrue();
        assertThat(reopened.transactionId()).isNull();

        assertThat(reservations.close(created.id()).transactionId()).isNull();
    }

    @Test
    void aRefusedReopeningKeepsTheLink() {
        Reservation created = reservations.create("Yearly", Scope.PRIVATE, eur("100.00"), DEC_31, true, null);
        long payment = spend(everyday(), JAN_1, 10000, "REF-1");
        reservations.close(created.id(), payment);
        Reservation next = reservations.open(Scope.PRIVATE).getFirst();
        reservations.update(next.id(), "Renamed", Scope.PRIVATE, eur("100.00"), next.dueDate(), true);

        assertRefused(Reason.NEXT_ROUND_CHANGED, () -> reservations.reopen(created.id()));

        assertThat(link(created)).isEqualTo(payment);
    }

    @Test
    void aBankSyncThatRemovesPendingRowsLeavesALinkedBookedRowAlone() {
        Account bank = accounts.addFromBank("Everyday bank", Scope.PRIVATE, Kind.CURRENT, "NL51XMPL0000000001");
        DeliveredTransaction booked = new DeliveredTransaction(JAN_1, null, -120000, "EUR", "Example Insurance", "NL10SHOP0000000101",
                "Premium", TransactionStatus.BOOKED, "REF-1", "raw:1");
        DeliveredTransaction pending = new DeliveredTransaction(JAN_1, null, -500, "EUR", "Corner Grocery", "NL80SHOP0000000102",
                "Groceries", TransactionStatus.PENDING, null, "raw:2");
        transactions.deliverFromBank(bank.id(), List.of(booked, pending), null);
        long payment = transactions.forAccount(bank.id(), 0).items().stream().filter(t -> !t.pending()).findFirst().orElseThrow().id();
        Reservation created = premium();
        reservations.close(created.id(), payment);

        var report = transactions.deliverFromBank(bank.id(), List.of(booked), null);

        assertThat(report.pendingRemoved()).isEqualTo(1);
        assertThat(transactions.get(payment).pending()).isFalse();
        assertThat(link(created)).isEqualTo(payment);
    }

    // ----- the payments to pick from

    private static final LocalDate DUE = LocalDate.of(2026, 3, 1);

    /** A reservation of 100.00 due on {@link #DUE}, seen {@code daysAfterDue} days after it fell due. */
    private Reservation rentSeen(int daysAfterDue) {
        Reservation created = reservations.create("Rent", Scope.PRIVATE, eur("100.00"), DUE, false, null);
        clock.set(DUE.plusDays(daysAfterDue));
        return created;
    }

    private List<Long> candidateIds(Reservation reservation) {
        return reservations.paymentCandidates(reservation.id()).stream().map(c -> c.transaction().id()).toList();
    }

    @Test
    void theCandidatesAreTheBookedSpendingOfTheScopeInsideTheWindow() {
        Reservation rent = rentSeen(5);
        Account everyday = everyday();
        long first = spend(everyday, DUE.minusDays(60), 5000, "REF-1");
        spend(everyday, DUE.minusDays(61), 5000, "REF-2"); // the day before the window
        long today = spend(everyday, DUE.plusDays(5), 5000, "REF-3");
        spend(everyday, DUE.plusDays(6), 5000, "REF-4"); // after today
        deliver(everyday, DUE, 5000, TransactionStatus.BOOKED, "REF-5"); // income
        deliver(everyday, DUE, -5000, TransactionStatus.PENDING, "REF-6"); // pending
        spend(business(), DUE, 5000, "REF-7"); // other scope

        assertThat(candidateIds(rent)).containsExactlyInAnyOrder(first, today);
    }

    @Test
    void theWindowStartsSixtyDaysBeforeTheDueDateOrBeforeTodayWhicheverIsEarlier() {
        Reservation rent = rentSeen(0);
        clock.set(DUE.minusDays(20)); // not due yet: the window starts before today
        long early = spend(everyday(), DUE.minusDays(80), 5000, "REF-1");
        spend(everyday(), DUE.minusDays(81), 5000, "REF-2");

        assertThat(candidateIds(rent)).containsExactly(early);
    }

    @Test
    void atMostOneHundredCandidatesAreOffered() {
        Reservation rent = rentSeen(0);
        Account everyday = everyday();
        for (int i = 0; i < 101; i++) {
            spend(everyday, DUE.minusDays(i % 30), 5000 + i, "REF-" + i);
        }

        assertThat(reservations.paymentCandidates(rent.id())).hasSize(100);
    }

    @Test
    void aCandidateIsLikelyWithinTenPercentAndFourteenDaysOfTheDueDate() {
        Reservation rent = rentSeen(20);
        Account everyday = everyday();
        long exact = spend(everyday, DUE.plusDays(14), 11000, "REF-1"); // 10 % more, 14 days after: still likely
        long cheaper = spend(everyday, DUE.minusDays(14), 9000, "REF-2"); // 10 % less, 14 days before
        long tooMuch = spend(everyday, DUE, 11001, "REF-3"); // one cent over
        long tooLess = spend(everyday, DUE, 8999, "REF-4");
        long tooLate = spend(everyday, DUE.plusDays(15), 10000, "REF-5");
        long tooEarly = spend(everyday, DUE.minusDays(15), 10000, "REF-6");

        List<PaymentCandidate> candidates = reservations.paymentCandidates(rent.id());

        assertThat(candidates).filteredOn(PaymentCandidate::likely).extracting(c -> c.transaction().id())
                .containsExactlyInAnyOrder(exact, cheaper);
        assertThat(candidates).filteredOn(c -> !c.likely()).extracting(c -> c.transaction().id())
                .containsExactlyInAnyOrder(tooMuch, tooLess, tooLate, tooEarly);
    }

    @Test
    void likelyCandidatesComeFirstClosestAmountFirstThenClosestDateThenOldestId() {
        Reservation rent = rentSeen(20);
        Account everyday = everyday();
        long unlikelyNewest = spend(everyday, DUE.plusDays(20), 10000, "REF-1");
        long off = spend(everyday, DUE, 10500, "REF-2");
        long exactLater = spend(everyday, DUE.plusDays(3), 10000, "REF-3");
        long exactOnTheDay = spend(everyday, DUE, 10000, "REF-4");
        long exactOnTheDayToo = spend(everyday, DUE, 10000, "REF-5");

        assertThat(candidateIds(rent)).containsExactly(exactOnTheDay, exactOnTheDayToo, exactLater, off, unlikelyNewest);
        assertThat(reservations.paymentCandidates(rent.id())).extracting(PaymentCandidate::likely)
                .containsExactly(true, true, true, true, false);
    }

    @Test
    void thePaymentOfAClosedReservationIsListedWithItsDifference() {
        Reservation premium = premium();
        long payment = spend(everyday(), JAN_1, 123456, "REF-1");
        reservations.close(premium.id(), payment);
        Reservation plain = reservations.create("Plain", Scope.PRIVATE, eur("10.00"), DEC_31, false, null);
        reservations.close(plain.id());

        List<ClosedReservation> closed = reservations.closedWithPayments(Scope.PRIVATE);

        ClosedReservation linked = closed.stream().filter(c -> c.reservation().id() == premium.id()).findFirst().orElseThrow();
        assertThat(linked.payment().id()).isEqualTo(payment);
        assertThat(linked.paid()).isEqualTo(eur("1234.56"));
        assertThat(linked.difference()).isEqualTo(eur("34.56"));
        ClosedReservation unlinked = closed.stream().filter(c -> c.reservation().id() == plain.id()).findFirst().orElseThrow();
        assertThat(unlinked.payment()).isNull();
        assertThat(unlinked.paid()).isNull();
        assertThat(unlinked.difference()).isNull();
    }

    @Test
    void aPaymentOfLessThanReservedGivesANegativeDifference() {
        Reservation premium = premium();
        reservations.close(premium.id(), spend(everyday(), JAN_1, 100000, "REF-1"));

        assertThat(reservations.closedWithPayments(Scope.PRIVATE).getFirst().difference()).isEqualTo(eur("-200.00"));
    }

    @Test
    void aLinkedRowTheBankLaterTurnedIntoMoneyReceivedShowsNoDifference() {
        Reservation premium = premium();
        long payment = spend(everyday(), JAN_1, 120000, "REF-1");
        reservations.close(premium.id(), payment);
        jdbc.sql("update transactions set amount_cents = 120000 where id = ?").param(payment).update();

        ClosedReservation closed = reservations.closedWithPayments(Scope.PRIVATE).getFirst();

        assertThat(closed.hasPayment()).isFalse();
        assertThat(closed.difference()).isNull();
    }

    @Test
    void anAmountTooLargeToCompareIsNeverLikelyAndDoesNotBreakThePicker() {
        Reservation rent = rentSeen(0);
        long huge = spend(everyday(), DUE, Long.MAX_VALUE / 2, "REF-1");

        assertThat(reservations.paymentCandidates(rent.id())).singleElement().satisfies(c -> {
            assertThat(c.transaction().id()).isEqualTo(huge);
            assertThat(c.likely()).isFalse();
        });
    }

    // ----- the summary

    @Test
    void theSummaryOfAScopeHoldsTheTotalAndTheNextOnesDue() {
        reservations.create("Late", Scope.PRIVATE, eur("100.00"), DEC_31, false, eur("10.00"));
        reservations.create("Soon", Scope.PRIVATE, eur("50.00"), JAN_1.plusDays(30), false, eur("5.00"));
        reservations.create("Business one", Scope.BUSINESS, eur("70.00"), DEC_31, false, eur("7.00"));

        ReservationSummary summary = reservations.summary(Scope.PRIVATE, 1);
        assertThat(summary.reserved()).isEqualTo(eur("15.00"));
        assertThat(summary.open()).isEqualTo(2);
        assertThat(summary.next()).extracting(s -> s.reservation().name()).containsExactly("Soon");
        assertThat(reservations.summary(Scope.BUSINESS, 3).reserved()).isEqualTo(eur("7.00"));
        assertThat(reservations.summary(Scope.BUSINESS, 3).hasOpen()).isTrue();
    }

    @Test
    void theLargestAmountDoesNotOverflowAnywhere() {
        Reservation created = reservations.create("Largest", Scope.PRIVATE, Money.eur(Reserving.MAX_AMOUNT_CENTS), JAN_1.plusYears(50), false, null);
        clock.set(JAN_1.plusYears(50).minusDays(1));
        assertThat(reservations.standing(created).reserved().cents()).isBetween(0L, Reserving.MAX_AMOUNT_CENTS);
        assertThat(reservations.summary(Scope.PRIVATE, 3).reserved().cents()).isPositive();
    }

    private int count(String sql) {
        return jdbc.sql(sql).query(Integer.class).single();
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
