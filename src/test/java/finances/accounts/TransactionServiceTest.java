package finances.accounts;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import finances.accounts.TransactionException.Reason;
import finances.money.Money;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.IntStream;
import java.util.stream.Stream;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.sqlite.SQLiteConfig;
import org.sqlite.SQLiteDataSource;

/** The transactions store on a real, migrated SQLite file, without the web layer. Fictional data only. */
class TransactionServiceTest {

    private static final String PRIVATE_IBAN = "NL51XMPL0000000001";
    private static final String SAVINGS_IBAN = "NL24XMPL0000000002";
    private static final String SHOP_IBAN = "NL10SHOP0000000101";

    private static final LocalDate DAY = LocalDate.of(2026, 6, 10);

    private final MovableClock clock = new MovableClock(Instant.parse("2026-06-15T10:00:00Z"));
    private AccountService accounts;
    private TransactionService transactions;
    private JdbcClient jdbc;
    private Account everyday;

    @BeforeEach
    void open(@TempDir Path folder) {
        SQLiteConfig config = new SQLiteConfig();
        config.enforceForeignKeys(true);
        SQLiteDataSource dataSource = new SQLiteDataSource(config);
        dataSource.setUrl("jdbc:sqlite:" + folder.resolve("finances.db"));
        Flyway.configure().dataSource(dataSource).locations("classpath:db/migration").load().migrate();
        jdbc = JdbcClient.create(dataSource);
        accounts = new AccountService(jdbc, clock);
        transactions = new TransactionService(jdbc, clock, new DataSourceTransactionManager(dataSource));
        everyday = accounts.addManual("Everyday", Scope.PRIVATE, Kind.CURRENT, PRIVATE_IBAN);
    }

    // ----- helpers

    private static DeliveredTransaction booked(LocalDate date, long cents, String counterparty, String description, String reference) {
        return new DeliveredTransaction(date, null, cents, "EUR", counterparty, SHOP_IBAN, description,
                TransactionStatus.BOOKED, reference, "raw:" + reference + ":" + description);
    }

    private static DeliveredTransaction pending(LocalDate date, long cents, String counterparty, String description) {
        return new DeliveredTransaction(date, null, cents, "EUR", counterparty, SHOP_IBAN, description,
                TransactionStatus.PENDING, null, "raw:pending:" + description);
    }

    private DeliveryReport deliver(DeliveredTransaction... batch) {
        return transactions.deliver(everyday.id(), Source.CSV, List.of(batch));
    }

    private List<Transaction> stored() {
        return transactions.forAccount(everyday.id(), 0).items();
    }

    private int count() {
        return jdbc.sql("select count(*) from transactions").query(Integer.class).single();
    }

    // ----- the same data twice

    @Test
    void theSameBatchTwiceAddsNothing() {
        DeliveredTransaction[] batch = {
                booked(DAY, -1234, "Corner Grocery", "Groceries", "REF-1"),
                booked(DAY.plusDays(1), -10000, "Example Energy", "Energy bill", "REF-2"),
                booked(DAY.plusDays(2), 123456, "Example Client BV", "Invoice 1", null)
        };

        assertThat(deliver(batch)).isEqualTo(new DeliveryReport(3, 0, 0, 0));
        List<Transaction> first = stored();
        assertThat(deliver(batch)).isEqualTo(new DeliveryReport(0, 0, 3, 0));

        assertThat(count()).isEqualTo(3);
        assertThat(stored()).extracting(Transaction::id).containsExactlyElementsOf(first.stream().map(Transaction::id).toList());
    }

    @Test
    void overlappingBatchesStoreEachTransactionOnce() {
        DeliveredTransaction a = booked(DAY, -100, "Corner Grocery", "Groceries", "REF-1");
        DeliveredTransaction b = booked(DAY.plusDays(1), -200, "Corner Grocery", "Groceries", "REF-2");
        DeliveredTransaction c = booked(DAY.plusDays(2), -300, "Example Telecom", "Phone", null);
        DeliveredTransaction d = booked(DAY.plusDays(3), -400, "Example Gym", "Gym", null);

        assertThat(deliver(a, b, c)).isEqualTo(new DeliveryReport(3, 0, 0, 0));
        assertThat(deliver(b, c, d)).isEqualTo(new DeliveryReport(1, 0, 2, 0));

        assertThat(count()).isEqualTo(4);
    }

    @Test
    void twoIdenticalTransactionsInOneBatchAreBothKeptAndNeverMultiplied() {
        DeliveredTransaction coffee = booked(DAY, -250, "Corner Grocery", "Coffee", null);

        assertThat(deliver(coffee, coffee)).isEqualTo(new DeliveryReport(2, 0, 0, 0));
        assertThat(deliver(coffee, coffee)).isEqualTo(new DeliveryReport(0, 0, 2, 0));
        assertThat(count()).isEqualTo(2);
        // A bigger batch of the same fingerprint adds only the ones beyond what was there.
        assertThat(deliver(coffee, coffee, coffee)).isEqualTo(new DeliveryReport(1, 0, 2, 0));
        assertThat(count()).isEqualTo(3);
    }

    @Test
    void theSameTextTypedADifferentWayIsTheSameTransactionWithoutAReference() {
        deliver(booked(DAY, -250, "Corner Grocery", "Weekly   groceries", null));

        DeliveryReport again = deliver(booked(DAY, -250, "  corner grocery ", "weekly groceries", null));

        assertThat(again.added()).isZero();
        assertThat(count()).isEqualTo(1);
    }

    @Test
    void sameDataOnAnotherAccountIsAnotherTransaction() {
        Account savings = accounts.addManual("Savings", Scope.PRIVATE, Kind.SAVINGS, SAVINGS_IBAN);
        DeliveredTransaction t = booked(DAY, -250, "Corner Grocery", "Coffee", "REF-1");

        deliver(t);
        DeliveryReport other = transactions.deliver(savings.id(), Source.CSV, List.of(t));

        assertThat(other.added()).isEqualTo(1);
        assertThat(count()).isEqualTo(2);
    }

    // ----- pending then booked

    @Test
    void aPendingTransactionThatArrivesAsBookedWithAReferenceIsTheSameTransaction() {
        deliver(pending(DAY, -4500, "Example Insurance", "Premium"));
        long id = stored().getFirst().id();

        DeliveryReport report = deliver(booked(DAY, -4500, "Example Insurance", "Premium", "REF-9"));

        assertThat(report).isEqualTo(new DeliveryReport(0, 1, 0, 0));
        assertThat(stored()).singleElement().satisfies(t -> {
            assertThat(t.id()).isEqualTo(id);
            assertThat(t.status()).isEqualTo(TransactionStatus.BOOKED);
            assertThat(t.reference()).isEqualTo("REF-9");
        });
        assertThat(deliver(booked(DAY, -4500, "Example Insurance", "Premium", "REF-9"))).isEqualTo(new DeliveryReport(0, 0, 1, 0));
        assertThat(count()).isEqualTo(1);
    }

    @Test
    void aPendingTransactionThatArrivesAsBookedWithoutAReferenceIsTheSameTransaction() {
        deliver(pending(DAY, -4500, "Example Insurance", "Premium"));

        DeliveryReport report = deliver(booked(DAY, -4500, "Example Insurance", "Premium", null));

        assertThat(report).isEqualTo(new DeliveryReport(0, 1, 0, 0));
        assertThat(stored()).singleElement().satisfies(t -> assertThat(t.status()).isEqualTo(TransactionStatus.BOOKED));
    }

    @Test
    void twoPendingOnesBecomeTwoBookedOnesNotFour() {
        deliver(pending(DAY, -250, "Corner Grocery", "Coffee"), pending(DAY, -250, "Corner Grocery", "Coffee"));

        deliver(booked(DAY, -250, "Corner Grocery", "Coffee", "REF-1"), booked(DAY, -250, "Corner Grocery", "Coffee", "REF-2"));

        assertThat(count()).isEqualTo(2);
        assertThat(stored()).extracting(Transaction::status).containsOnly(TransactionStatus.BOOKED);
    }

    @Test
    void aPendingOneLeftOverAfterASiblingWasBookedStaysTheSameRowInEitherOrder() {
        for (boolean pendingFirst : new boolean[] {true, false}) {
            Account account = accounts.addManual("Order " + pendingFirst, Scope.PRIVATE, Kind.CURRENT, null);
            DeliveredTransaction p = pending(DAY, -250, "Corner Grocery", "Coffee");
            DeliveredTransaction b = booked(DAY, -250, "Corner Grocery", "Coffee", "REF-2");
            transactions.deliver(account.id(), Source.BANK, List.of(p, p));

            transactions.deliver(account.id(), Source.BANK, pendingFirst ? List.of(p, b) : List.of(b, p));
            transactions.deliver(account.id(), Source.BANK, pendingFirst ? List.of(p, b) : List.of(b, p));

            List<Transaction> rows = transactions.forAccount(account.id(), 0).items();
            assertThat(rows).hasSize(2);
            assertThat(rows).extracting(Transaction::status).containsExactlyInAnyOrder(TransactionStatus.BOOKED, TransactionStatus.PENDING);
        }
    }

    @Test
    void aPendingTransactionWithItsOwnReferenceBecomesTheBookedOneWithAnotherReference() {
        deliver(new DeliveredTransaction(DAY, null, -4500, "EUR", "Example Insurance", null, "Premium",
                TransactionStatus.PENDING, "PEND-1", "raw"));

        DeliveryReport report = deliver(booked(DAY, -4500, "Example Insurance", "Premium", "BOOK-1"));

        assertThat(report).isEqualTo(new DeliveryReport(0, 1, 0, 0));
        assertThat(stored()).singleElement().satisfies(t -> {
            assertThat(t.status()).isEqualTo(TransactionStatus.BOOKED);
            assertThat(t.reference()).isEqualTo("BOOK-1");
        });
    }

    @Test
    void aBookedTransactionNeverGoesBackToPending() {
        deliver(booked(DAY, -4500, "Example Insurance", "Premium", null));

        DeliveryReport late = deliver(pending(DAY, -4500, "Example Insurance", "Premium"));

        assertThat(late.added()).isZero();
        assertThat(stored()).singleElement().satisfies(t -> assertThat(t.status()).isEqualTo(TransactionStatus.BOOKED));
    }

    // ----- updates change only what the source owns

    @Test
    void anUpdateChangesTheSourceFieldsAndKeepsFirstSeen() {
        deliver(booked(DAY, -1234, "Corner Grocery", "Groceries", "REF-1"));
        Transaction before = stored().getFirst();
        clock.set(Instant.parse("2026-06-16T08:00:00Z"));

        DeliveryReport report = deliver(booked(DAY, -1299, "Corner Grocery", "Groceries and bags", "REF-1"));

        assertThat(report).isEqualTo(new DeliveryReport(0, 1, 0, 0));
        Transaction after = stored().getFirst();
        assertThat(after.id()).isEqualTo(before.id());
        assertThat(after.amount()).isEqualTo(Money.eur(-1299));
        assertThat(after.description()).isEqualTo("Groceries and bags");
        assertThat(after.firstSeenAt()).isEqualTo(Instant.parse("2026-06-15T10:00:00Z"));
        assertThat(after.lastSeenAt()).isEqualTo(Instant.parse("2026-06-16T08:00:00Z"));
        assertThat(transactions.raw(after.id())).isEqualTo("raw:REF-1:Groceries and bags");
    }

    @Test
    void theOwnersNoteSurvivesEveryRedeliveryAndUpdate() {
        deliver(booked(DAY, -1234, "Corner Grocery", "Groceries", "REF-1"), booked(DAY, -999, "Example Gym", "Gym", null));
        List<Transaction> rows = stored();
        rows.forEach(t -> transactions.setNote(t.id(), "Paid for Robin Example"));

        deliver(booked(DAY, -1234, "Corner Grocery", "Groceries", "REF-1"), booked(DAY, -999, "Example Gym", "Gym", null));
        deliver(booked(DAY, -1500, "Corner Grocery", "Groceries, corrected", "REF-1"));
        deliver(pending(DAY, -999, "Example Gym", "Gym"));

        assertThat(stored()).hasSize(2).extracting(Transaction::note).containsOnly("Paid for Robin Example");
        assertThat(count()).isEqualTo(2);
        assertThat(stored()).extracting(Transaction::description).contains("Groceries, corrected");
    }

    @Test
    void aNoteIsSetChangedAndRemovedByTheOwnerOnly() {
        deliver(booked(DAY, -1234, "Corner Grocery", "Groceries", "REF-1"));
        long id = stored().getFirst().id();

        transactions.setNote(id, "  first  ");
        assertThat(transactions.get(id).note()).isEqualTo("first");
        transactions.setNote(id, "second");
        assertThat(transactions.get(id).note()).isEqualTo("second");
        transactions.setNote(id, "   ");
        assertThat(transactions.get(id).note()).isNull();

        assertThatThrownBy(() -> transactions.setNote(id, "x".repeat(TransactionService.MAX_NOTE_LENGTH + 1)))
                .isInstanceOfSatisfying(TransactionException.class, e -> assertThat(e.reason()).isEqualTo(Reason.NOTE_TOO_LONG));
        assertThatThrownBy(() -> transactions.setNote(9999, "note"))
                .isInstanceOfSatisfying(TransactionException.class, e -> assertThat(e.reason()).isEqualTo(Reason.NOT_FOUND));
    }

    // ----- edges

    @Test
    void aFailingBatchLeavesNothingBehind() {
        deliver(booked(DAY, -100, "Corner Grocery", "Before", "REF-0"));
        Instant deliveredBefore = transactions.lastDelivered(everyday.id()).orElseThrow();
        clock.set(Instant.parse("2026-06-20T10:00:00Z"));

        Stream<DeliveredTransaction> failing = Stream.concat(
                Stream.of(booked(DAY, -200, "Corner Grocery", "One", "REF-1"),
                        booked(DAY, -300, "Corner Grocery", "Two", "REF-2"),
                        booked(DAY, -100, "Corner Grocery", "Before, changed", "REF-0")),
                Stream.generate(() -> {
                    throw new IllegalStateException("the file broke halfway");
                }));

        assertThatThrownBy(() -> transactions.deliver(everyday.id(), Source.CSV, failing)).isInstanceOf(IllegalStateException.class);

        assertThat(count()).isEqualTo(1);
        assertThat(stored().getFirst().description()).isEqualTo("Before");
        assertThat(transactions.lastDelivered(everyday.id())).contains(deliveredBefore);
    }

    @Test
    void aBatchForAnArchivedAccountIsRefusedAndStoresNothing() {
        accounts.archive(everyday.id());

        assertThatThrownBy(() -> deliver(booked(DAY, -100, "Corner Grocery", "Groceries", "REF-1")))
                .isInstanceOfSatisfying(TransactionException.class, e -> assertThat(e.reason()).isEqualTo(Reason.ARCHIVED));

        assertThat(count()).isZero();
        accounts.unarchive(everyday.id());
        assertThat(deliver(booked(DAY, -100, "Corner Grocery", "Groceries", "REF-1")).added()).isEqualTo(1);
    }

    @Test
    void aBatchForAnUnknownAccountIsRefused() {
        assertThatThrownBy(() -> transactions.deliver(9999, Source.CSV, List.of()))
                .isInstanceOfSatisfying(TransactionException.class, e -> assertThat(e.reason()).isEqualTo(Reason.NOT_FOUND));
    }

    @Test
    void aTransactionInAnotherCurrencyIsRefusedAndCountedAndTheRestIsStored() {
        DeliveredTransaction dollars = new DeliveredTransaction(DAY, null, -500, "USD", "Corner Grocery", null, "Souvenir",
                TransactionStatus.BOOKED, "REF-USD", "raw");
        DeliveredTransaction unknown = new DeliveredTransaction(DAY, null, -500, "XXX", "Corner Grocery", null, "Unknown",
                TransactionStatus.BOOKED, "REF-XXX", "raw");

        DeliveryReport report = deliver(booked(DAY, -100, "Corner Grocery", "Groceries", "REF-1"), dollars, unknown);

        assertThat(report).isEqualTo(new DeliveryReport(1, 0, 0, 2));
        assertThat(report.total()).isEqualTo(3);
        assertThat(count()).isEqualTo(1);
    }

    @Test
    void aTransactionWithoutABookingDateOrStatusCannotBeBuilt() {
        assertThatThrownBy(() -> new DeliveredTransaction(null, null, -1, "EUR", null, null, "", TransactionStatus.BOOKED, null, ""))
                .isInstanceOfSatisfying(TransactionException.class, e -> assertThat(e.reason()).isEqualTo(Reason.INCOMPLETE));
        assertThatThrownBy(() -> new DeliveredTransaction(DAY, null, -1, "EUR", null, null, "", null, null, ""))
                .isInstanceOfSatisfying(TransactionException.class, e -> assertThat(e.reason()).isEqualTo(Reason.INCOMPLETE));
    }

    @Test
    void optionalFieldsMayBeMissing() {
        deliver(new DeliveredTransaction(DAY, DAY.plusDays(1), 5000, "EUR", null, null, null, TransactionStatus.BOOKED, "  ", null));

        assertThat(stored()).singleElement().satisfies(t -> {
            assertThat(t.counterpartyName()).isNull();
            assertThat(t.counterpartyIban()).isNull();
            assertThat(t.description()).isEmpty();
            assertThat(t.reference()).isNull();
            assertThat(t.valueDate()).isEqualTo(DAY.plusDays(1));
            assertThat(t.amount()).isEqualTo(Money.eur(5000));
        });
    }

    @Test
    void aLargeBatchIsReadOneAtATimeAndStoredOnce() {
        int size = 20_000;
        // Generated lazily: nothing here holds the whole batch.
        Stream<DeliveredTransaction> large = IntStream.range(0, size)
                .mapToObj(i -> i % 2 == 0
                        ? booked(DAY.minusDays(i % 300), -(i + 1), "Corner Grocery", "Item " + i, "REF-" + i)
                        : booked(DAY.minusDays(i % 300), -(i + 1), "Example Energy", "Item " + i, null));

        assertThat(transactions.deliver(everyday.id(), Source.CSV, large)).isEqualTo(new DeliveryReport(size, 0, 0, 0));
        Stream<DeliveredTransaction> again = IntStream.range(0, size)
                .mapToObj(i -> i % 2 == 0
                        ? booked(DAY.minusDays(i % 300), -(i + 1), "Corner Grocery", "Item " + i, "REF-" + i)
                        : booked(DAY.minusDays(i % 300), -(i + 1), "Example Energy", "Item " + i, null));
        assertThat(transactions.deliver(everyday.id(), Source.CSV, again)).isEqualTo(new DeliveryReport(0, 0, size, 0));

        assertThat(count()).isEqualTo(size);
    }

    // ----- when it was delivered

    @Test
    void anAccountShowsWhenItsTransactionsWereLastDeliveredEvenWhenNothingNewCame() {
        Account other = accounts.addManual("Other", Scope.PRIVATE, Kind.SAVINGS, SAVINGS_IBAN);
        assertThat(transactions.lastDelivered(everyday.id())).isEmpty();
        assertThat(transactions.lastDelivered()).isEmpty();

        deliver(booked(DAY, -100, "Corner Grocery", "Groceries", "REF-1"));
        assertThat(transactions.lastDelivered(everyday.id())).contains(Instant.parse("2026-06-15T10:00:00Z"));

        clock.set(Instant.parse("2026-06-18T07:30:00Z"));
        deliver(booked(DAY, -100, "Corner Grocery", "Groceries", "REF-1"));
        assertThat(transactions.lastDelivered(everyday.id())).contains(Instant.parse("2026-06-18T07:30:00Z"));

        assertThat(transactions.lastDelivered(other.id())).isEmpty();
        assertThat(transactions.lastDelivered()).containsOnlyKeys(everyday.id());
    }

    // ----- listing

    @Test
    void listingIsNewestFirstPagedAndAcrossAccountsShowsTheAccountName() {
        Account savings = accounts.addManual("Savings", Scope.PRIVATE, Kind.SAVINGS, SAVINGS_IBAN);
        List<DeliveredTransaction> many = new ArrayList<>();
        for (int i = 0; i < TransactionService.PAGE_SIZE + 5; i++) {
            many.add(booked(DAY.minusDays(i), -(i + 1), "Corner Grocery", "Item " + i, "REF-" + i));
        }
        transactions.deliver(everyday.id(), Source.CSV, many);
        transactions.deliver(savings.id(), Source.CSV, List.of(booked(DAY.plusDays(30), 5000, "Example Client BV", "Interest", "REF-S")));

        TransactionPage first = transactions.forAccount(everyday.id(), 0);
        assertThat(first.items()).hasSize(TransactionService.PAGE_SIZE);
        assertThat(first.hasNext()).isTrue();
        assertThat(first.hasPrevious()).isFalse();
        assertThat(first.items().getFirst().bookingDate()).isEqualTo(DAY);
        TransactionPage second = transactions.forAccount(everyday.id(), 1);
        assertThat(second.items()).hasSize(5);
        assertThat(second.hasNext()).isFalse();
        assertThat(second.hasPrevious()).isTrue();
        assertThat(second.items().getFirst().bookingDate()).isEqualTo(DAY.minusDays(TransactionService.PAGE_SIZE));
        assertThat(transactions.forAccount(everyday.id(), -3).page()).isZero();

        TransactionPage all = transactions.all(0);
        assertThat(all.items().getFirst().accountName()).isEqualTo("Savings");
        assertThat(all.items().getFirst().bookingDate()).isEqualTo(DAY.plusDays(30));
        assertThat(all.items().get(1).accountName()).isEqualTo("Everyday");
    }

    // ----- hard rule 11

    @Test
    void noAmountIbanOrCounterpartyIsWrittenToTheLogDuringABatch() {
        Logger root = (Logger) LoggerFactory.getLogger(Logger.ROOT_LOGGER_NAME);
        Level before = root.getLevel();
        ListAppender<ILoggingEvent> log = new ListAppender<>();
        log.start();
        root.addAppender(log);
        root.setLevel(Level.DEBUG);
        try {
            DeliveredTransaction pendingOne = pending(DAY, -123456, "Example Landlord", "Rent for June");
            transactions.deliver(everyday.id(), Source.BANK, List.of(
                    pendingOne,
                    booked(DAY, -123456, "Example Landlord", "Rent for June", "REF-RENT"),
                    booked(DAY, -999, "Example Gym", "Gym", null),
                    new DeliveredTransaction(DAY, null, -777, "USD", "Corner Grocery", SHOP_IBAN, "Souvenir", TransactionStatus.BOOKED, "REF-USD", "raw")));
            transactions.deliver(everyday.id(), Source.BANK, List.of(booked(DAY, -999, "Example Gym", "Gym", null)));
            assertThatThrownBy(() -> transactions.deliver(9999, Source.BANK, List.of(booked(DAY, -999, "Example Gym", "Gym", null))))
                    .isInstanceOf(TransactionException.class);
            accounts.archive(everyday.id());
            assertThatThrownBy(() -> deliver(booked(DAY, -555, "Example Gym", "Gym", null))).isInstanceOf(TransactionException.class);
        } finally {
            root.detachAppender(log);
            root.setLevel(before);
        }

        // The messages, and what is attached to them (a refusal is logged by the transaction manager as it
        // rolls back): the exception text names the reason only.
        String everything = String.join("\n", log.list.stream()
                .map(event -> event.getFormattedMessage() + (event.getThrowableProxy() == null ? "" : " " + event.getThrowableProxy().getMessage()))
                .toList());
        assertThat(everything).doesNotContain("Landlord", "Gym", "Grocery", SHOP_IBAN, PRIVATE_IBAN, "1234", "999", "777", "Rent", "REF-");
        assertThat(log.list).as("the log was captured").isNotEmpty();
    }

    // ----- a pending transaction that is booked under a new reference (issue #10)

    private static DeliveredTransaction pendingWithReference(LocalDate date, long cents, String counterparty, String description, String reference) {
        return new DeliveredTransaction(date, null, cents, "EUR", counterparty, SHOP_IBAN, description,
                TransactionStatus.PENDING, reference, "raw:pending:" + reference);
    }

    private DeliveryReport deliverBank(DeliveredTransaction... batch) {
        return transactions.deliver(everyday.id(), Source.BANK, List.of(batch));
    }

    @Test
    void aPendingRowDeliveredAgainInTheSameBatchIsNotTakenOverByAnIdenticalSecondPurchase() {
        // The store has pending R1; the batch is [pending R1, booked B9], B9 being an identical second purchase.
        deliverBank(pendingWithReference(DAY, -800, "Example Gym", "Day pass", "R1"));

        DeliveryReport report = deliverBank(
                pendingWithReference(DAY, -800, "Example Gym", "Day pass", "R1"),
                booked(DAY, -800, "Example Gym", "Day pass", "B9"));

        assertThat(report).isEqualTo(new DeliveryReport(1, 0, 1, 0));
        assertThat(stored()).hasSize(2).extracting(Transaction::reference).containsExactlyInAnyOrder("R1", "B9");
    }

    @Test
    void theSameTwoPurchasesInTheOtherOrderOfTheBatchAlsoStayTwoRows() {
        deliverBank(pendingWithReference(DAY, -800, "Example Gym", "Day pass", "R1"));

        deliverBank(
                booked(DAY, -800, "Example Gym", "Day pass", "B9"),
                pendingWithReference(DAY, -800, "Example Gym", "Day pass", "R1"));

        assertThat(stored()).hasSize(2);
        assertThat(stored()).extracting(Transaction::status).containsExactlyInAnyOrder(TransactionStatus.BOOKED, TransactionStatus.PENDING);
    }

    @Test
    void aPendingRowIsBookedUnderAnotherDateAndKeepsItsIdentityAndNote() {
        deliverBank(pending(DAY, -800, "Example Gym", "Day pass"));
        long id = stored().getFirst().id();
        transactions.setNote(id, "With Alex Sample");

        DeliveryReport report = deliverBank(booked(DAY.plusDays(2), -800, "Example Gym", "Day pass", "B9"));

        assertThat(report).isEqualTo(new DeliveryReport(0, 1, 0, 0));
        assertThat(stored()).singleElement().satisfies(t -> {
            assertThat(t.id()).isEqualTo(id);
            assertThat(t.status()).isEqualTo(TransactionStatus.BOOKED);
            assertThat(t.bookingDate()).isEqualTo(DAY.plusDays(2));
            assertThat(t.note()).isEqualTo("With Alex Sample");
        });
        // Delivering the booked one again changes nothing.
        assertThat(deliverBank(booked(DAY.plusDays(2), -800, "Example Gym", "Day pass", "B9"))).isEqualTo(new DeliveryReport(0, 0, 1, 0));
        assertThat(count()).isEqualTo(1);
    }

    @Test
    void theTakeoverReachesBackExactlyTheNamedNumberOfDays() {
        assertThat(TransactionService.PENDING_TAKEOVER_DAYS).isEqualTo(7);
        LocalDate booking = DAY.plusDays(TransactionService.PENDING_TAKEOVER_DAYS);

        // Dated exactly the limit earlier: the same purchase.
        deliverBank(pending(DAY, -800, "Example Gym", "Day pass"));
        assertThat(deliverBank(booked(booking, -800, "Example Gym", "Day pass", "B1"))).isEqualTo(new DeliveryReport(0, 1, 0, 0));
        assertThat(count()).isEqualTo(1);

        // One day further back than the limit: another purchase.
        deliverBank(pending(DAY.minusDays(1), -900, "Example Gym", "Day pass"));
        assertThat(deliverBank(booked(booking, -900, "Example Gym", "Day pass", "B2"))).isEqualTo(new DeliveryReport(1, 0, 0, 0));
        assertThat(count()).isEqualTo(3);
    }

    private static DeliveredTransaction pendingDatedByFetch(LocalDate fetchDay, long cents, String counterparty, String description) {
        return new DeliveredTransaction(fetchDay, null, cents, "EUR", counterparty, SHOP_IBAN, description,
                TransactionStatus.PENDING, null, "raw:pending:" + description, true);
    }

    @Test
    void aPendingRowDatedByTheFetchDayIsAlsoTakenOverByABookedDateUpToTheNamedNumberOfDaysEarlier() {
        int days = TransactionService.PENDING_TAKEOVER_DAYS;

        // Booked exactly the limit before the day it was first seen: the same purchase.
        deliverBank(pendingDatedByFetch(DAY, -800, "Example Gym", "Day pass"));
        long id = stored().getFirst().id();
        assertThat(deliverBank(booked(DAY.minusDays(days), -800, "Example Gym", "Day pass", "B1"))).isEqualTo(new DeliveryReport(0, 1, 0, 0));
        assertThat(stored()).singleElement().satisfies(t -> {
            assertThat(t.id()).isEqualTo(id);
            assertThat(t.bookingDate()).isEqualTo(DAY.minusDays(days));
        });

        // One day further back than the limit: another purchase.
        deliverBank(pendingDatedByFetch(DAY, -900, "Example Gym", "Day pass"));
        assertThat(deliverBank(booked(DAY.minusDays(days + 1), -900, "Example Gym", "Day pass", "B2"))).isEqualTo(new DeliveryReport(1, 0, 0, 0));
        assertThat(count()).isEqualTo(3);
    }

    @Test
    void aPendingRowDatedByTheFetchDayStillTakesTheLaterDatesTheOldRuleTook() {
        deliverBank(pendingDatedByFetch(DAY, -800, "Example Gym", "Day pass"));

        assertThat(deliverBank(booked(DAY.plusDays(TransactionService.PENDING_TAKEOVER_DAYS), -800, "Example Gym", "Day pass", "B1")))
                .isEqualTo(new DeliveryReport(0, 1, 0, 0));
        assertThat(count()).isEqualTo(1);
    }

    @Test
    void theWiderWindowNeedsTheSameAmountCounterpartyAndDescriptionAndNeverAppliesToADatedPendingRow() {
        deliverBank(pendingDatedByFetch(DAY, -800, "Example Gym", "Day pass"), pending(DAY, -900, "Example Gym", "Day pass"));

        assertThat(deliverBank(booked(DAY.minusDays(2), -801, "Example Gym", "Day pass", "B1")).added()).isEqualTo(1);
        assertThat(deliverBank(booked(DAY.minusDays(2), -800, "Example Telecom", "Day pass", "B2")).added()).isEqualTo(1);
        assertThat(deliverBank(booked(DAY.minusDays(2), -800, "Example Gym", "Month pass", "B3")).added()).isEqualTo(1);
        // The bank dated this pending row itself: a booked date before it is another purchase, as before.
        assertThat(deliverBank(booked(DAY.minusDays(2), -900, "Example Gym", "Day pass", "B4")).added()).isEqualTo(1);

        assertThat(stored()).filteredOn(Transaction::pending).hasSize(2);
    }

    @Test
    void aLaterDeliveryWithADateFromTheBankClearsTheMarkAndTheWiderWindowGoesWithIt() {
        DeliveredTransaction undated = new DeliveredTransaction(DAY, null, -800, "EUR", "Example Gym", SHOP_IBAN, "Day pass",
                TransactionStatus.PENDING, "R1", "raw:undated", true);
        DeliveredTransaction dated = new DeliveredTransaction(DAY, null, -800, "EUR", "Example Gym", SHOP_IBAN, "Day pass",
                TransactionStatus.PENDING, "R1", "raw:dated", false);
        deliverBank(undated);
        assertThat(jdbc.sql("select dated_by_fetch from transactions").query(Integer.class).single()).isOne();

        deliverBank(dated);

        assertThat(jdbc.sql("select dated_by_fetch from transactions").query(Integer.class).single()).isZero();
        assertThat(deliverBank(booked(DAY.minusDays(2), -800, "Example Gym", "Day pass", "B1")).added()).isEqualTo(1);
        assertThat(count()).isEqualTo(2);
    }

    @Test
    void aBookedRowIsNeverMarkedAsDatedByTheFetch() {
        deliverBank(new DeliveredTransaction(DAY, null, -800, "EUR", "Example Gym", SHOP_IBAN, "Day pass", TransactionStatus.BOOKED, "B1", "raw", true));

        assertThat(jdbc.sql("select dated_by_fetch from transactions").query(Integer.class).single()).isZero();
    }

    @Test
    void aPendingRowDatedAfterTheBookingDateIsAnotherPurchase() {
        deliverBank(pending(DAY.plusDays(1), -800, "Example Gym", "Day pass"));

        assertThat(deliverBank(booked(DAY, -800, "Example Gym", "Day pass", "B1"))).isEqualTo(new DeliveryReport(1, 0, 0, 0));
        assertThat(count()).isEqualTo(2);
    }

    @Test
    void aBookedTransactionTakesOverOnlyAPendingRowWithTheSameAmountCounterpartyAndDescription() {
        deliverBank(pending(DAY, -800, "Example Gym", "Day pass"));

        assertThat(deliverBank(booked(DAY, -801, "Example Gym", "Day pass", "B1")).added()).isEqualTo(1);
        assertThat(deliverBank(booked(DAY, -800, "Example Telecom", "Day pass", "B2")).added()).isEqualTo(1);
        assertThat(deliverBank(booked(DAY, -800, "Example Gym", "Month pass", "B3")).added()).isEqualTo(1);

        assertThat(stored()).filteredOn(Transaction::pending).hasSize(1);
        assertThat(count()).isEqualTo(4);
    }

    @Test
    void theOldestPendingRowIsTakenOverFirst() {
        deliverBank(pending(DAY, -800, "Example Gym", "Day pass"), pending(DAY.plusDays(1), -800, "Example Gym", "Day pass"));

        deliverBank(booked(DAY.plusDays(3), -800, "Example Gym", "Day pass", "B1"));

        assertThat(stored()).filteredOn(Transaction::pending).singleElement()
                .satisfies(t -> assertThat(t.bookingDate()).isEqualTo(DAY.plusDays(1)));
    }

    @Test
    void aPendingItemWithoutAnyDateKeepsTheDayItWasFirstSeen() {
        deliverBank(pending(DAY, -800, "Example Gym", "Day pass"), pending(DAY.plusDays(2), -800, "Example Gym", "Other pass"));

        assertThat(transactions.pendingBankDate(everyday.id(), -800, " example  GYM ", "day pass")).contains(DAY);
        assertThat(transactions.pendingBankDate(everyday.id(), -800, "Example Gym", "Other pass")).contains(DAY.plusDays(2));
        assertThat(transactions.pendingBankDate(everyday.id(), -801, "Example Gym", "Day pass")).isEmpty();
        assertThat(transactions.pendingBankDate(everyday.id(), -800, "Example Gym", "Month pass")).isEmpty();
    }

    // ----- pending rows the bank no longer reports (issue #10)

    private BankDeliveryReport fromBank(LocalDate coveredFrom, DeliveredTransaction... batch) {
        return transactions.deliverFromBank(everyday.id(), List.of(batch), coveredFrom);
    }

    @Test
    void aPendingRowTheBankNoLongerReportsIsRemovedWhenItHasNoNote() {
        deliverBank(pending(DAY, -800, "Example Gym", "Day pass"), booked(DAY, -1234, "Corner Grocery", "Groceries", "REF-1"));

        BankDeliveryReport report = fromBank(DAY.minusDays(5), booked(DAY, -1234, "Corner Grocery", "Groceries", "REF-1"));

        assertThat(report.pendingRemoved()).isEqualTo(1);
        assertThat(report.pendingKept()).isZero();
        assertThat(report.delivered()).isEqualTo(new DeliveryReport(0, 0, 1, 0));
        assertThat(stored()).singleElement().satisfies(t -> assertThat(t.reference()).isEqualTo("REF-1"));
    }

    @Test
    void aPendingRowWithANoteIsKeptAndMarkedAndTheMarkGoesWhenTheBankReportsItAgain() {
        deliverBank(pending(DAY, -800, "Example Gym", "Day pass"));
        transactions.setNote(stored().getFirst().id(), "With Alex Sample");

        BankDeliveryReport report = fromBank(DAY.minusDays(5));

        assertThat(report.pendingRemoved()).isZero();
        assertThat(report.pendingKept()).isEqualTo(1);
        assertThat(stored()).singleElement().satisfies(t -> {
            assertThat(t.noLongerReported()).isTrue();
            assertThat(t.note()).isEqualTo("With Alex Sample");
        });

        fromBank(DAY.minusDays(5), pending(DAY, -800, "Example Gym", "Day pass"));

        assertThat(stored()).singleElement().satisfies(t -> assertThat(t.noLongerReported()).isFalse());
    }

    @Test
    void aPendingRowThatBecameBookedInThisFetchIsNotCountedAsNoLongerReported() {
        deliverBank(pending(DAY, -800, "Example Gym", "Day pass"));
        transactions.setNote(stored().getFirst().id(), "With Alex Sample");

        BankDeliveryReport report = fromBank(DAY.minusDays(5), booked(DAY.plusDays(1), -800, "Example Gym", "Day pass", "B1"));

        assertThat(report.pendingRemoved()).isZero();
        assertThat(report.pendingKept()).isZero();
        assertThat(stored()).singleElement().satisfies(t -> {
            assertThat(t.status()).isEqualTo(TransactionStatus.BOOKED);
            assertThat(t.noLongerReported()).isFalse();
        });
    }

    @Test
    void onlyPendingRowsInsideTheCoveredPeriodAndOfSourceBankAreTouched() {
        deliverBank(pending(DAY.minusDays(30), -100, "Example Gym", "Old"));
        transactions.deliver(everyday.id(), Source.CSV, List.of(pending(DAY, -200, "Example Gym", "From a file")));

        BankDeliveryReport report = fromBank(DAY.minusDays(5));

        assertThat(report.pendingRemoved()).isZero();
        assertThat(count()).isEqualTo(2);

        // A fetch that reached back as far as the bank allows covers every pending row of the bank.
        assertThat(fromBank(null).pendingRemoved()).isEqualTo(1);
        assertThat(stored()).singleElement().satisfies(t -> assertThat(t.source()).isEqualTo(Source.CSV));
    }

    @Test
    void theDeliveryEntryPointNeverDeletesAnything() {
        deliverBank(pending(DAY, -800, "Example Gym", "Day pass"));

        deliverBank(booked(DAY, -1234, "Corner Grocery", "Groceries", "REF-1"));

        assertThat(stored()).hasSize(2);
    }

    /** A clock the test can move: the store stamps what it sees with it. */
    private static final class MovableClock extends Clock {
        private Instant now;

        MovableClock(Instant now) {
            this.now = now;
        }

        void set(Instant instant) {
            this.now = instant;
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
