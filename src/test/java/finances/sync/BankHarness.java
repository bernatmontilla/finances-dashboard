package finances.sync;

import finances.accounts.AccountService;
import finances.accounts.TransactionService;
import finances.data.DataFolder;
import finances.outbound.ApplicationCredentials;
import finances.outbound.EnableBankingClient;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import org.flywaydb.core.Flyway;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.sqlite.SQLiteConfig;
import org.sqlite.SQLiteDataSource;

/** The bank link wired by hand on a real, migrated SQLite file and a fake Enable Banking server. Fictional data only. */
final class BankHarness implements AutoCloseable {

    static final String REDIRECT = "http://localhost:8080/settings/bank/callback";

    final MovableClock clock = new MovableClock(Instant.parse("2026-11-26T12:00:00Z"));
    final FakeEnableBanking bank;
    final DataFolder dataFolder;
    final Path folder;
    final JdbcClient jdbc;
    final DataSourceTransactionManager manager;
    final AccountService accounts;
    final TransactionService transactions;
    final BankStore store;
    final SessionFiles sessions;
    final BankSyncService sync;
    final BankLinkService link;
    final BankStatus status;

    BankHarness(Path folder, boolean pkcs1) throws Exception {
        this.folder = folder;
        this.bank = new FakeEnableBanking();
        bank.install(folder, pkcs1);
        this.dataFolder = DataFolder.open(folder.toString());
        SQLiteConfig config = new SQLiteConfig();
        config.enforceForeignKeys(true);
        config.setBusyTimeout(5000);
        SQLiteDataSource dataSource = new SQLiteDataSource(config);
        dataSource.setUrl("jdbc:sqlite:" + dataFolder.databaseFile());
        Flyway.configure().dataSource(dataSource).locations("classpath:db/migration").load().migrate();
        this.jdbc = JdbcClient.create(dataSource);
        this.manager = new DataSourceTransactionManager(dataSource);
        this.accounts = new AccountService(jdbc, clock);
        this.transactions = new TransactionService(jdbc, clock, manager);
        this.store = new BankStore(jdbc);
        this.sessions = new SessionFiles(dataFolder);
        EnableBankingClient client = new EnableBankingClient(new ApplicationCredentials(dataFolder), clock, bank.baseUrl(), 10);
        this.sync = new BankSyncService(client, store, sessions, accounts, transactions, clock, manager, 7);
        this.link = new BankLinkService(client, store, sessions, accounts, sync, clock, manager, REDIRECT);
        this.status = new BankStatus(store, client, REDIRECT);
    }

    /** Link flow up to the callback: returns what coming back from the bank gave. */
    LinkOutcome linkBank(java.util.Map<String, String> userHeaders) {
        link.start("NL", "Example Bank", null);
        return link.complete(lastState(), "fictional-code", null, userHeaders);
    }

    /** The state the last authorisation was started with, as the fake bank saw it. */
    String lastState() {
        var auths = bank.requests("POST", "/auth");
        String body = auths.getLast().body();
        int at = body.indexOf("\"state\":\"") + "\"state\":\"".length();
        return body.substring(at, body.indexOf('"', at));
    }

    int count(String sql, Object... params) {
        return jdbc.sql(sql).params(params).query(Integer.class).single();
    }

    long accountIdByIban(String iban) {
        return jdbc.sql("select id from account where iban = ?").param(iban).query(Long.class).single();
    }

    @Override
    public void close() throws Exception {
        bank.close();
        dataFolder.close();
        try (var files = Files.walk(folder)) {
            files.sorted(java.util.Comparator.reverseOrder()).forEach(p -> p.toFile().delete());
        }
    }

    /** A clock the test can move. */
    static final class MovableClock extends Clock {
        private volatile Instant now;

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
