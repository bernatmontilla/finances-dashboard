package finances.sync;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.stereotype.Component;

/** The SQL of the after-tax source: the values received and the fetches made. No rules in here. */
@Component
class AfterTaxStore {

    /** How many fetch records are kept; the value history is never pruned. */
    static final int KEEP_FETCHES = 100;

    private static final String VALUE = "select id, received_at, as_of, available_cents, gross_balance_cents, mismatch from after_tax_value";

    private final JdbcClient jdbc;

    AfterTaxStore(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    Optional<AfterTaxValue> newest() {
        return jdbc.sql(VALUE + " order by id desc limit 1").query(AfterTaxStore::bare).optional().map(this::withLines);
    }

    /** The values received, oldest first. */
    List<AfterTaxValue> history() {
        return jdbc.sql(VALUE + " order by id").query(AfterTaxStore::bare).list().stream().map(this::withLines).toList();
    }

    long insertValue(AfterTaxValue value) {
        KeyHolder key = new GeneratedKeyHolder();
        jdbc.sql("insert into after_tax_value (received_at, as_of, available_cents, gross_balance_cents, mismatch) values (?, ?, ?, ?, ?)")
                .params(value.receivedAt().toString(), value.asOf().toString(), value.availableCents(), value.grossBalanceCents(),
                        value.mismatch() ? 1 : 0)
                .update(key);
        long id = key.getKey().longValue();
        int position = 0;
        for (AfterTaxValue.Reserved line : value.reserved()) {
            jdbc.sql("insert into after_tax_reserved (value_id, position, label, amount_cents) values (?, ?, ?, ?)")
                    .params(id, position++, line.label(), line.amountCents())
                    .update();
        }
        return id;
    }

    /** Records a fetch and drops the oldest ones beyond {@value #KEEP_FETCHES}. */
    void insertFetch(AfterTaxFetchRow row) {
        jdbc.sql("insert into after_tax_fetch (started_at, finished_at, trigger, outcome, reason, http_status) values (?, ?, ?, ?, ?, ?)")
                .params(row.startedAt().toString(), row.finishedAt().toString(), row.trigger(), row.ok() ? "ok" : "failed", row.reason(),
                        row.httpStatus())
                .update();
        jdbc.sql("delete from after_tax_fetch where id not in (select id from after_tax_fetch order by id desc limit ?)")
                .param(KEEP_FETCHES)
                .update();
    }

    /** The newest fetches, newest first. */
    List<AfterTaxFetchRow> fetches(int limit) {
        return jdbc.sql("select started_at, finished_at, trigger, outcome, reason, http_status from after_tax_fetch order by id desc limit ?")
                .param(limit)
                .query(AfterTaxStore::fetch)
                .list();
    }

    int fetchCount() {
        return jdbc.sql("select count(*) from after_tax_fetch").query(Integer.class).single();
    }

    private static AfterTaxValue bare(ResultSet rs, int row) throws SQLException {
        long gross = rs.getLong("gross_balance_cents");
        Long grossOrNull = rs.wasNull() ? null : gross;
        return new AfterTaxValue(rs.getLong("id"), Instant.parse(rs.getString("received_at")), Instant.parse(rs.getString("as_of")),
                rs.getLong("available_cents"), grossOrNull, rs.getInt("mismatch") == 1, List.of());
    }

    private AfterTaxValue withLines(AfterTaxValue value) {
        List<AfterTaxValue.Reserved> reserved = jdbc
                .sql("select label, amount_cents from after_tax_reserved where value_id = ? order by position")
                .param(value.id())
                .query((r, i) -> new AfterTaxValue.Reserved(r.getString("label"), r.getLong("amount_cents")))
                .list();
        return new AfterTaxValue(value.id(), value.receivedAt(), value.asOf(), value.availableCents(), value.grossBalanceCents(),
                value.mismatch(), reserved);
    }

    private static AfterTaxFetchRow fetch(ResultSet rs, int row) throws SQLException {
        int status = rs.getInt("http_status");
        Integer statusOrNull = rs.wasNull() ? null : status;
        return new AfterTaxFetchRow(Instant.parse(rs.getString("started_at")), Instant.parse(rs.getString("finished_at")),
                rs.getString("trigger"), "ok".equals(rs.getString("outcome")), rs.getString("reason"), statusOrNull);
    }
}
