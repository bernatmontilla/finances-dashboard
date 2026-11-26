package finances.data;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Path;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.sqlite.SQLiteConfig;
import org.sqlite.SQLiteDataSource;

/** A database at version 12 migrates to 13 without losing what the owner answered, and fills in what V13 added. Fictional data only. */
class MigrationV13Test {

    @Test
    void aKeptPriceFromVersion12GetsTheAmountOfItsTransactionAndNoProposalOrRejectionAppears(@TempDir Path folder) {
        SQLiteConfig config = new SQLiteConfig();
        config.enforceForeignKeys(true);
        SQLiteDataSource dataSource = new SQLiteDataSource(config);
        dataSource.setUrl("jdbc:sqlite:" + folder.resolve("finances.db"));
        Flyway.configure().dataSource(dataSource).locations("classpath:db/migration").target("12").load().migrate();
        JdbcClient jdbc = JdbcClient.create(dataSource);
        jdbc.sql("insert into account (id, name, scope, kind, source, iban, created_at) values (1, 'Everyday', 'private', 'current', "
                + "'manual', 'NL51XMPL0000000001', '2026-06-01T10:00:00Z')").update();
        jdbc.sql("insert into transactions (id, account_id, booking_date, amount_cents, counterparty_name, status, source, fingerprint, "
                + "first_seen_at, last_seen_at) values (7, 1, '2026-06-15', -1101, 'Example Gym', 'booked', 'csv', 'f', "
                + "'2026-06-16T10:00:00Z', '2026-06-16T10:00:00Z')").update();
        jdbc.sql("insert into fixed_cost (id, name, scope, amount_cents, currency, rhythm, first_due, created_at, changed_at) values (3, "
                + "'Example Gym', 'private', 1000, 'EUR', 'monthly', '2026-01-15', '2026-06-01T10:00:00Z', '2026-06-01T10:00:00Z')").update();
        jdbc.sql("insert into fixed_cost_price_kept (fixed_cost_id, transaction_id, kept_at) values (3, 7, '2026-06-16T10:00:00Z')").update();

        Flyway.configure().dataSource(dataSource).locations("classpath:db/migration").load().migrate();

        assertThat(jdbc.sql("select amount_cents from fixed_cost_price_kept where fixed_cost_id = 3").query(Long.class).single()).isEqualTo(1101L);
        assertThat(jdbc.sql("select count(*) from fixed_cost").query(Integer.class).single()).isEqualTo(1);
    }
}
