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
    private JdbcClient jdbc;
    private AccountService accounts;
    private TransactionService transactions;
    private Account everyday;
    private Account business;

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
        everyday = accounts.addManual("Everyday", Scope.PRIVATE, Kind.CURRENT, "NL51XMPL0000000001");
        business = accounts.addManual("Business", Scope.BUSINESS, Kind.CURRENT, "NL94XMPL0000000003");
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
        clock.set(DEC_31);
        assertThat(reserved(changed)).isEqualTo(eur("1200.00"));

        clock.set(DEC_31.minusDays(1)); // from the due date on setting it is refused (#51, ALREADY_DUE)
        assertRefused(Reason.RESERVED_OUT_OF_RANGE, () -> reservations.setReserved(created.id(), eur("1200.01")));
        assertRefused(Reason.RESERVED_OUT_OF_RANGE, () -> reservations.setReserved(created.id(), eur("-0.01")));
        assertThat(reservations.setReserved(created.id(), Money.zero()).anchorAmount()).isEqualTo(Money.zero());
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
    void aClosedReservationCannotBeRemovedItIsReopenedFirst() {
        Reservation created = reservations.create("Example Insurance premium", Scope.PRIVATE, eur("1200.00"), DEC_31, true, null);
        reservations.close(created.id());
        Reservation next = reservations.open(Scope.PRIVATE).getFirst();

        assertRefused(Reason.CLOSED, () -> reservations.remove(created.id()));

        assertThat(reservations.get(created.id()).isOpen()).isFalse();
        assertThat(reservations.get(next.id()).followsId()).isEqualTo(created.id());
        reservations.reopen(created.id());
        reservations.remove(created.id());
        assertThat(reservations.find(created.id())).isEmpty();
        assertRefused(Reason.NOT_FOUND, () -> reservations.remove(created.id()));
    }

    @Test
    void aMissingReservationIsNotFound() {
        assertRefused(Reason.NOT_FOUND, () -> reservations.get(42));
        assertRefused(Reason.NOT_FOUND, () -> reservations.close(42));
    }

    // ----- closing against a transaction (issue #51)

    private long deliver(Account account, LocalDate date, long cents, TransactionStatus status, String reference) {
        transactions.deliver(account.id(), Source.CSV, List.of(new DeliveredTransaction(date, null, cents, "EUR", "Corner Grocery",
                "NL10SHOP0000000101", "Premium " + reference, status, status == TransactionStatus.BOOKED ? reference : null,
                "raw:" + reference)));
        return jdbc.sql("select id from transactions where raw = ?").param("raw:" + reference).query(Long.class).single();
    }

    private long booked(LocalDate date, long cents, String reference) {
        return deliver(everyday, date, cents, TransactionStatus.BOOKED, reference);
    }

    private Long link(Reservation reservation) {
        return reservations.get(reservation.id()).transactionId();
    }

    @Test
    void closingAgainstABookedTransactionKeepsTheLinkAndReducesNothing() {
        Reservation created = premium();
        long paid = booked(LocalDate.of(2026, 12, 29), -123456, "A");

        Reservation closed = reservations.close(created.id(), paid);

        assertThat(closed.isOpen()).isFalse();
        assertThat(closed.transactionId()).isEqualTo(paid);
        assertThat(reservations.summary(Scope.PRIVATE, 3).reserved()).isEqualTo(Money.zero());
        ReservationPayment payment = reservations.payment(closed).orElseThrow();
        assertThat(payment.paid()).isEqualTo(eur("1234.56"));
        assertThat(payment.difference()).isEqualTo(eur("34.56"));
        assertThat(payment.transaction().id()).isEqualTo(paid);
    }

    @Test
    void theDifferenceIsAgainstTheReservedAmountWhateverIsPaid() {
        Reservation exact = premium();
        Reservation less = reservations.create("Example Gym", Scope.PRIVATE, eur("1200.00"), DEC_31, false, null);
        long exactId = booked(LocalDate.of(2026, 12, 30), -120000, "B");
        long lessId = booked(LocalDate.of(2026, 12, 30), -10000, "C");

        assertThat(reservations.payment(reservations.close(exact.id(), exactId)).orElseThrow().difference()).isEqualTo(Money.zero());
        assertThat(reservations.payment(reservations.close(less.id(), lessId)).orElseThrow().difference()).isEqualTo(eur("-1100.00"));
    }

    @Test
    void aPlainCloseStoresNoLinkAndHasNoPayment() {
        Reservation closed = reservations.close(premium().id());
        assertThat(closed.transactionId()).isNull();
        assertThat(reservations.payment(closed)).isEmpty();
    }

    @Test
    void closingAgainstATransactionThatDoesNotQualifyIsRefusedAndChangesNothing() {
        Reservation created = premium();
        long pending = deliver(everyday, LocalDate.of(2026, 12, 29), -120000, TransactionStatus.PENDING, "P");
        long otherScope = deliver(business, LocalDate.of(2026, 12, 29), -120000, TransactionStatus.BOOKED, "O");
        long incoming = booked(LocalDate.of(2026, 12, 29), 120000, "I");

        assertRefused(Reason.TRANSACTION_NOT_FOUND, () -> reservations.close(created.id(), 9999L));
        assertRefused(Reason.TRANSACTION_NOT_BOOKED, () -> reservations.close(created.id(), pending));
        assertRefused(Reason.TRANSACTION_OTHER_SCOPE, () -> reservations.close(created.id(), otherScope));
        assertRefused(Reason.TRANSACTION_NOT_SPENDING, () -> reservations.close(created.id(), incoming));
        assertThat(reservations.get(created.id()).isOpen()).isTrue();
        assertThat(link(created)).isNull();
    }

    @Test
    void closingAClosedReservationAgainstATransactionIsRefused() {
        Reservation created = premium();
        long paid = booked(LocalDate.of(2026, 12, 29), -120000, "A");
        reservations.close(created.id());
        assertRefused(Reason.ALREADY_CLOSED, () -> reservations.close(created.id(), paid));
        assertThat(link(created)).isNull();
    }

    @Test
    void closingAYearlyReservationAgainstATransactionStartsNextYearsRound() {
        Reservation created = reservations.create("Example Insurance premium", Scope.PRIVATE, eur("1200.00"), DEC_31, true, null);
        long paid = booked(LocalDate.of(2026, 12, 29), -120000, "A");

        reservations.close(created.id(), paid);

        Reservation next = reservations.open(Scope.PRIVATE).getFirst();
        assertThat(next.followsId()).isEqualTo(created.id());
        assertThat(next.dueDate()).isEqualTo(DEC_31.plusYears(1));
        assertThat(next.transactionId()).isNull();
    }

    @Test
    void reopeningClearsTheLinkButARefusedReopeningKeepsIt() {
        Reservation created = reservations.create("Example Insurance premium", Scope.PRIVATE, eur("1200.00"), DEC_31, true, null);
        long paid = booked(LocalDate.of(2026, 12, 29), -120000, "A");
        reservations.close(created.id(), paid);
        Reservation next = reservations.open(Scope.PRIVATE).getFirst();
        reservations.update(next.id(), "Edited", Scope.PRIVATE, eur("1300.00"), DEC_31.plusYears(1), true);

        assertRefused(Reason.NEXT_ROUND_CHANGED, () -> reservations.reopen(created.id()));
        assertThat(link(created)).isEqualTo(paid);
        assertThat(reservations.get(created.id()).isOpen()).isFalse();

        reservations.remove(next.id());
        Reservation reopened = reservations.reopen(created.id());
        assertThat(reopened.isOpen()).isTrue();
        assertThat(reopened.transactionId()).isNull();
    }

    @Test
    void aBankDeliveryThatRemovesPendingRowsStillWorksWhileABookedRowIsLinked() {
        Reservation created = premium();
        long paid = booked(LocalDate.of(2026, 12, 29), -120000, "A");
        reservations.close(created.id(), paid);
        transactions.deliver(everyday.id(), Source.BANK, List.of(new DeliveredTransaction(LocalDate.of(2026, 12, 30), null, -500, "EUR",
                "Corner Grocery", "NL10SHOP0000000101", "Pending", TransactionStatus.PENDING, null, "raw:pending")));

        transactions.deliverFromBank(everyday.id(), List.of(), null);

        assertThat(link(created)).isEqualTo(paid);
        assertThat(transactions.get(paid).amount()).isEqualTo(eur("-1200.00"));
        assertThat(jdbc.sql("select count(*) from transactions where status = 'pending'").query(Integer.class).single()).isZero();
    }

    @Test
    void theCandidatesAreBookedSpendingOfTheScopeInTheLast90DaysAtMost50() {
        clock.set(LocalDate.of(2026, 12, 20));
        Reservation created = premium();
        long inside = booked(LocalDate.of(2026, 12, 19), -5000, "IN");
        long oldest = booked(LocalDate.of(2026, 9, 21), -5000, "EDGE");
        booked(LocalDate.of(2026, 9, 20), -5000, "TOO-OLD");
        booked(LocalDate.of(2026, 12, 19), 5000, "INCOMING");
        deliver(everyday, LocalDate.of(2026, 12, 19), -5000, TransactionStatus.PENDING, "PEND");
        deliver(business, LocalDate.of(2026, 12, 19), -5000, TransactionStatus.BOOKED, "BIZ");
        Account old = accounts.addManual("Old", Scope.PRIVATE, Kind.CURRENT, "NL24XMPL0000000002");
        long archived = deliver(old, LocalDate.of(2026, 12, 1), -5000, TransactionStatus.BOOKED, "ARCH");
        accounts.archive(old.id());

        assertThat(reservations.candidates(created.id()).stream().map(c -> c.transaction().id()))
                .containsExactlyInAnyOrder(inside, oldest, archived);
    }

    @Test
    void theCandidatesAreCappedAtFifty() {
        clock.set(LocalDate.of(2026, 12, 20));
        Reservation created = premium();
        for (int i = 0; i < 55; i++) {
            booked(LocalDate.of(2026, 12, 19), -100 - i, "N" + i);
        }
        assertThat(reservations.candidates(created.id())).hasSize(TransactionService.PAGE_SIZE);
    }

    // ----- the ranking (pure, no database)

    private static ReservationCandidate pick(Reservation reservation, long cents, LocalDate date) {
        Transaction t = new Transaction(1, 1, "Everyday", date, null, Money.eur(cents), "Corner Grocery", null, "x", TransactionStatus.BOOKED,
                Source.CSV, null, Instant.EPOCH, Instant.EPOCH, false, null);
        return ReservationService.rank(reservation, List.of(t)).getFirst();
    }

    @Test
    void likelyMeansAmountWithinTenPercentAndDateWithinFourteenDays() {
        Reservation r = premium(); // 1,200.00 due 31 Dec
        assertThat(pick(r, -120000, DEC_31.minusDays(3)).likely()).isTrue();
        assertThat(pick(r, -132000, DEC_31).likely()).isTrue();
        assertThat(pick(r, -132001, DEC_31).likely()).isFalse();
        assertThat(pick(r, -108000, DEC_31).likely()).isTrue();
        assertThat(pick(r, -107999, DEC_31).likely()).isFalse();
        assertThat(pick(r, -120000, DEC_31.minusDays(14)).likely()).isTrue();
        assertThat(pick(r, -120000, DEC_31.minusDays(15)).likely()).isFalse();
        assertThat(pick(r, -120000, DEC_31.plusDays(14)).likely()).isTrue();
        assertThat(pick(r, -120000, DEC_31.plusDays(15)).likely()).isFalse();
    }

    @Test
    void likelyMatchesComeFirstClosestAmountThenClosestDateAndTheRestNewestFirst() {
        Reservation r = premium();
        Instant t0 = Instant.EPOCH;
        List<Transaction> list = List.of(
                tx(1, -50000, LocalDate.of(2026, 12, 30)),
                tx(2, -125000, DEC_31.minusDays(2)),
                tx(3, -120000, DEC_31.minusDays(3)),
                tx(4, -120000, DEC_31.minusDays(1)),
                tx(5, -1000, LocalDate.of(2026, 6, 1)));
        assertThat(ReservationService.rank(r, list).stream().map(c -> c.transaction().id())).containsExactly(4L, 3L, 2L, 1L, 5L);
    }

    private static Transaction tx(long id, long cents, LocalDate date) {
        return new Transaction(id, 1, "Everyday", date, null, Money.eur(cents), "Corner Grocery", null, "x", TransactionStatus.BOOKED,
                Source.CSV, null, Instant.EPOCH, Instant.EPOCH, false, null);
    }

    // ----- the two rules of the review of #20

    @Test
    void settingReservedOnADueOrOverdueReservationIsRefusedTheDayBeforeItIsAccepted() {
        Reservation created = premium();
        clock.set(DEC_31.minusDays(1));
        reservations.setReserved(created.id(), eur("10.00"));

        clock.set(DEC_31);
        assertRefused(Reason.ALREADY_DUE, () -> reservations.setReserved(created.id(), eur("20.00")));
        clock.set(DEC_31.plusDays(5));
        assertRefused(Reason.ALREADY_DUE, () -> reservations.setReserved(created.id(), eur("20.00")));
        assertThat(reservations.get(created.id()).anchorAmount()).isEqualTo(eur("10.00"));
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
