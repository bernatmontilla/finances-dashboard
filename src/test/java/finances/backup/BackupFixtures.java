package finances.backup;

import finances.accounts.AccountService;
import finances.accounts.DeliveredTransaction;
import finances.accounts.Kind;
import finances.accounts.FixedCostService;
import finances.accounts.ReservationService;
import finances.accounts.Scope;
import finances.accounts.Source;
import finances.accounts.TransactionService;
import finances.accounts.TransactionStatus;
import finances.money.Money;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import org.flywaydb.core.Flyway;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.sqlite.SQLiteConfig;
import org.sqlite.SQLiteDataSource;

/** A real migrated SQLite database in a data folder, filled with the fictional set of AGENTS.md. */
final class BackupFixtures {

    static final Clock CLOCK = Clock.fixed(Instant.parse("2026-06-15T10:00:00Z"), ZoneOffset.UTC);
    static final String NOTE = "Paid by Robin Example, to be split";

    final Path folder;
    final SQLiteDataSource dataSource;
    final JdbcClient jdbc;
    final AccountService accounts;
    final TransactionService transactions;
    final ReservationService reservations;
    final FixedCostService fixedCosts;

    private BackupFixtures(Path folder, String target) {
        this.folder = folder;
        SQLiteConfig config = new SQLiteConfig();
        config.setJournalMode(SQLiteConfig.JournalMode.WAL);
        config.setBusyTimeout(5000);
        config.enforceForeignKeys(true);
        dataSource = new SQLiteDataSource(config);
        dataSource.setUrl("jdbc:sqlite:" + folder.resolve("finances.db"));
        var flyway = Flyway.configure().dataSource(dataSource).locations("classpath:db/migration");
        if (target != null) {
            flyway.target(target);
        }
        flyway.load().migrate();
        jdbc = JdbcClient.create(dataSource);
        accounts = new AccountService(jdbc, CLOCK);
        transactions = new TransactionService(jdbc, CLOCK, new DataSourceTransactionManager(dataSource));
        reservations = new ReservationService(jdbc, CLOCK, accounts, transactions);
        fixedCosts = new FixedCostService(jdbc, CLOCK, accounts);
    }

    static BackupFixtures open(Path folder) {
        return new BackupFixtures(folder, null);
    }

    /** A database as an older version of the application left it (migrated only up to {@code version}). */
    static BackupFixtures openOlder(Path folder, String version) {
        return new BackupFixtures(folder, version);
    }

    /** Accounts, balances, transactions and a note: everything the owner could have entered so far. */
    BackupFixtures fill() {
        long everyday = accounts.addManual("Everyday", Scope.PRIVATE, Kind.CURRENT, "NL51XMPL0000000001").id();
        long savings = accounts.addManual("Rainy day", Scope.PRIVATE, Kind.SAVINGS, "NL24XMPL0000000002").id();
        long business = accounts.addManual("Example Client BV account", Scope.BUSINESS, Kind.CURRENT, "NL94XMPL0000000003").id();
        long loan = accounts.addManual("Example Loan", Scope.PRIVATE, Kind.DEBT, "NL67XMPL0000000004").id();
        accounts.addBalance(everyday, Money.eur(123456), LocalDate.of(2026, 6, 1));
        accounts.addBalance(everyday, Money.eur(100000), LocalDate.of(2026, 6, 10));
        accounts.addBalance(savings, Money.eur(500000), LocalDate.of(2026, 6, 10));
        accounts.addBalance(business, Money.eur(1234), LocalDate.of(2026, 6, 12));
        accounts.addBalance(loan, Money.eur(2500000), LocalDate.of(2026, 6, 12));
        transactions.deliver(everyday, Source.CSV, List.of(
                booked(LocalDate.of(2026, 6, 3), -1234, "Corner Grocery", "Groceries", "REF-1"),
                booked(LocalDate.of(2026, 6, 4), -10000, "Example Energy", "Energy bill", "REF-2"),
                booked(LocalDate.of(2026, 6, 5), 123456, "Example Client BV", "Invoice 1", null),
                booked(LocalDate.of(2026, 6, 6), -250, "Corner Grocery", "Coffee", null),
                booked(LocalDate.of(2026, 6, 6), -250, "Corner Grocery", "Coffee", null)));
        long noted = transactions.all(0).items().stream().filter(t -> "REF-2".equals(t.reference())).findFirst().orElseThrow().id();
        transactions.setNote(noted, NOTE);
        return this;
    }

    static DeliveredTransaction booked(LocalDate date, long cents, String counterparty, String description, String reference) {
        return new DeliveredTransaction(date, null, cents, "EUR", counterparty, "NL10SHOP0000000101", description,
                TransactionStatus.BOOKED, reference, "raw:" + description);
    }

    /** Every row the owner could see, as text, so two databases can be compared exactly. */
    String content() {
        return Stream.of("account", "balance", "transactions", "transaction_note")
                .map(table -> table + ": " + jdbc.sql("select * from " + table + " order by 1").query().listOfRows().stream()
                        .map(Object::toString).collect(Collectors.joining(" | ")))
                .collect(Collectors.joining("\n"));
    }

    /** Every reservation row, as text, so two databases can be compared exactly. */
    String reservationRows() {
        return jdbc.sql("select * from reservation order by id").query().listOfRows().stream().map(Object::toString)
                .collect(Collectors.joining("\n"));
    }

    /** Every fixed cost row and every tick, as text, so two databases can be compared exactly. */
    String fixedCostRows() {
        return jdbc.sql("select * from fixed_cost order by id").query().listOfRows().stream().map(Object::toString)
                .collect(Collectors.joining("\n")) + "\n"
                + jdbc.sql("select * from fixed_cost_paid order by fixed_cost_id, month").query().listOfRows().stream()
                        .map(Object::toString).collect(Collectors.joining("\n"));
    }

    long count(String table) {
        return jdbc.sql("select count(*) from " + table).query(Long.class).single();
    }

    /** What a restore must make of the data folder: no database and no sidecar file at all. */
    static void wipe(Path folder) throws java.io.IOException {
        try (Stream<Path> files = Files.list(folder)) {
            for (Path file : files.toList()) {
                if (Files.isRegularFile(file)) {
                    Files.delete(file);
                }
            }
        }
    }
}
