package finances.accounts;

import finances.money.Money;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.stream.Stream;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * The one place transactions are stored (AGENTS.md hard rule 10; docs/decisions/8-transactions-store.md).
 * {@link #deliver} takes what a source delivered and is safe to call with the same data again and
 * again. It writes the fields a source owns and nothing else: the owner's note is set only by
 * {@link #setNote}. Nothing here is logged: no amount, IBAN or counterparty reaches a log or a
 * message (hard rule 11).
 */
@Service
public class TransactionService {

    public static final int PAGE_SIZE = 50;
    static final int MAX_NOTE_LENGTH = 1000;

    private static final String SELECT = """
            select t.id, t.account_id, a.name as account_name, t.booking_date, t.value_date, t.amount_cents, t.currency,
                   t.counterparty_name, t.counterparty_iban, t.description, t.status, t.source, t.reference,
                   t.first_seen_at, t.last_seen_at, n.note
            from transactions t
            join account a on a.id = t.account_id
            left join transaction_note n on n.transaction_id = t.id
            """;

    private final JdbcClient jdbc;
    private final Clock clock;
    private final TransactionTemplate inTransaction;

    public TransactionService(JdbcClient jdbc, Clock clock, PlatformTransactionManager transactions) {
        this.jdbc = jdbc;
        this.clock = clock;
        this.inTransaction = new TransactionTemplate(transactions);
    }

    /** The owner's time zone, for showing a moment (stored in UTC) as a calendar day. */
    public ZoneId zone() {
        return clock.getZone();
    }

    // ----- delivery

    public DeliveryReport deliver(long accountId, Source source, Collection<DeliveredTransaction> batch) {
        return deliver(accountId, source, batch.stream());
    }

    /**
     * Stores one batch for an account in a single database transaction: a failure halfway stores
     * nothing. The batch is read one element at a time and closed afterwards, so a large file never
     * sits in memory twice; only the count of identical fingerprints is kept.
     *
     * <p>Identity is the account plus the source's own reference; without one, the account plus a
     * fingerprint of booking date, amount, counterparty and normalised description, counting the
     * identical ones of a batch apart. A pending transaction that arrives as booked, with the same
     * reference or the same fingerprint, becomes that one booked transaction. A booked transaction
     * never goes back to pending. A transaction in another currency than EUR is counted as refused.
     *
     * @throws TransactionException {@code NOT_FOUND} for an unknown account, {@code ARCHIVED} for an archived one
     */
    public DeliveryReport deliver(long accountId, Source source, Stream<DeliveredTransaction> batch) {
        Objects.requireNonNull(source);
        try (batch) {
            return inTransaction.execute(status -> store(accountId, source, batch));
        }
    }

    private DeliveryReport store(long accountId, Source source, Stream<DeliveredTransaction> batch) {
        String now = Instant.now(clock).toString();
        // The first statement is a write, so the single SQLite writer is taken (and waited for) up front
        // instead of upgrading a read lock halfway, which could fail at once.
        int touched = jdbc.sql("update account set transactions_delivered_at = ? where id = ? and archived = 0")
                .params(now, accountId)
                .update();
        if (touched == 0) {
            boolean exists = jdbc.sql("select count(*) from account where id = ?").param(accountId).query(Integer.class).single() > 0;
            throw new TransactionException(exists ? TransactionException.Reason.ARCHIVED : TransactionException.Reason.NOT_FOUND);
        }
        Tally tally = new Tally();
        Map<String, Integer> identical = new HashMap<>();
        batch.forEachOrdered(delivered -> storeOne(accountId, source, delivered, now, identical, tally));
        return new DeliveryReport(tally.added, tally.updated, tally.known, tally.refused);
    }

    private void storeOne(long accountId, Source source, DeliveredTransaction t, String now, Map<String, Integer> identical, Tally tally) {
        if (!Money.EUR.equals(t.currency())) {
            tally.refused++;
            return;
        }
        Fields fields = new Fields(
                t.bookingDate().toString(),
                t.valueDate() == null ? null : t.valueDate().toString(),
                t.amountCents(),
                blankToNull(t.counterpartyName()),
                compactIban(t.counterpartyIban()),
                t.description() == null ? "" : t.description().strip(),
                t.status().code(),
                source.code(),
                blankToNull(t.reference()),
                t.raw() == null ? "" : t.raw());
        String fingerprint = fingerprint(fields);

        Stored stored;
        int occurrence = 1;
        if (fields.reference() != null) {
            stored = byReference(accountId, fields.reference());
            if (stored == null && t.status() == TransactionStatus.BOOKED) {
                stored = pendingWithFingerprint(accountId, fingerprint);
            }
        } else {
            occurrence = identical.merge(fingerprint, 1, Integer::sum);
            stored = byFingerprint(accountId, fingerprint, occurrence);
        }

        if (stored == null) {
            jdbc.sql("""
                            insert into transactions (account_id, booking_date, value_date, amount_cents, counterparty_name,
                                counterparty_iban, description, status, source, reference, fingerprint, occurrence, raw,
                                first_seen_at, last_seen_at)
                            values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                            """)
                    .params(accountId, fields.bookingDate(), fields.valueDate(), fields.amountCents(), fields.counterpartyName(),
                            fields.counterpartyIban(), fields.description(), fields.status(), fields.source(), fields.reference(),
                            fingerprint, occurrence, fields.raw(), now, now)
                    .update();
            tally.added++;
            return;
        }
        // A booked transaction stays booked, whatever a late or repeated delivery says.
        Fields wanted = TransactionStatus.BOOKED.code().equals(stored.fields().status())
                ? fields.withStatus(TransactionStatus.BOOKED.code())
                : fields;
        if (wanted.equals(stored.fields())) {
            jdbc.sql("update transactions set last_seen_at = ? where id = ?").params(now, stored.id()).update();
            tally.known++;
            return;
        }
        jdbc.sql("""
                        update transactions set booking_date = ?, value_date = ?, amount_cents = ?, counterparty_name = ?,
                            counterparty_iban = ?, description = ?, status = ?, source = ?, reference = ?, fingerprint = ?,
                            raw = ?, last_seen_at = ?
                        where id = ?
                        """)
                .params(wanted.bookingDate(), wanted.valueDate(), wanted.amountCents(), wanted.counterpartyName(),
                        wanted.counterpartyIban(), wanted.description(), wanted.status(), wanted.source(), wanted.reference(),
                        fingerprint, wanted.raw(), now, stored.id())
                .update();
        tally.updated++;
    }

    private Stored byReference(long accountId, String reference) {
        return jdbc.sql(STORED + " where account_id = ? and reference = ?")
                .params(accountId, reference)
                .query(TransactionService::stored)
                .optional()
                .orElse(null);
    }

    private Stored byFingerprint(long accountId, String fingerprint, int occurrence) {
        return jdbc.sql(STORED + " where account_id = ? and reference is null and fingerprint = ? and occurrence = ?")
                .params(accountId, fingerprint, occurrence)
                .query(TransactionService::stored)
                .optional()
                .orElse(null);
    }

    /** The oldest pending transaction without a reference that looks like the one now arriving as booked. */
    private Stored pendingWithFingerprint(long accountId, String fingerprint) {
        return jdbc.sql(STORED + " where account_id = ? and reference is null and status = 'pending' and fingerprint = ? order by occurrence, id limit 1")
                .params(accountId, fingerprint)
                .query(TransactionService::stored)
                .optional()
                .orElse(null);
    }

    // ----- reading

    /** Newest booking date first, then the last stored first. {@code page} counts from 0. */
    public TransactionPage forAccount(long accountId, int page) {
        return page(" where t.account_id = ?", List.of(accountId), page);
    }

    /** The same, across all accounts, archived ones included: their history is kept. */
    public TransactionPage all(int page) {
        return page("", List.of(), page);
    }

    private TransactionPage page(String where, List<Object> filter, int page) {
        int number = Math.max(0, page);
        List<Object> params = new ArrayList<>(filter);
        params.add(PAGE_SIZE + 1);
        params.add((long) number * PAGE_SIZE);
        List<Transaction> rows = jdbc.sql(SELECT + where + " order by t.booking_date desc, t.id desc limit ? offset ?")
                .params(params)
                .query((rs, row) -> transaction(rs))
                .list();
        boolean more = rows.size() > PAGE_SIZE;
        return new TransactionPage(more ? rows.subList(0, PAGE_SIZE) : rows, number, more);
    }

    public Transaction get(long id) {
        return jdbc.sql(SELECT + " where t.id = ?")
                .param(id)
                .query((rs, row) -> transaction(rs))
                .optional()
                .orElseThrow(() -> new TransactionException(TransactionException.Reason.NOT_FOUND));
    }

    /** The record as the source delivered it. */
    public String raw(long id) {
        return jdbc.sql("select raw from transactions where id = ?")
                .param(id)
                .query(String.class)
                .optional()
                .orElseThrow(() -> new TransactionException(TransactionException.Reason.NOT_FOUND));
    }

    /** When transactions were last delivered for the account, or empty when never. */
    public Optional<Instant> lastDelivered(long accountId) {
        return jdbc.sql("select transactions_delivered_at from account where id = ?")
                .param(accountId)
                .query(String.class)
                .optional()
                .map(Instant::parse);
    }

    /** The same for every account that has had a delivery, keyed by account id. */
    public Map<Long, Instant> lastDelivered() {
        Map<Long, Instant> delivered = new HashMap<>();
        jdbc.sql("select id, transactions_delivered_at from account where transactions_delivered_at is not null")
                .query((rs, row) -> {
                    delivered.put(rs.getLong("id"), Instant.parse(rs.getString("transactions_delivered_at")));
                    return null;
                })
                .list();
        return delivered;
    }

    // ----- what the owner enters (never written by a delivery)

    /** Sets the owner's note on a transaction; a blank note removes it. */
    public void setNote(long transactionId, String note) {
        String clean = note == null ? "" : note.strip();
        if (clean.length() > MAX_NOTE_LENGTH) {
            throw new TransactionException(TransactionException.Reason.NOTE_TOO_LONG);
        }
        inTransaction.executeWithoutResult(status -> {
            if (clean.isEmpty()) {
                jdbc.sql("delete from transaction_note where transaction_id = ?").param(transactionId).update();
                return;
            }
            // Upsert first, so the single SQLite writer is taken up front (see store); a missing transaction
            // changes nothing and is reported after.
            int written = jdbc.sql("""
                            insert into transaction_note (transaction_id, note, updated_at)
                            select id, ?, ? from transactions where id = ?
                            on conflict (transaction_id) do update set note = excluded.note, updated_at = excluded.updated_at
                            """)
                    .params(clean, Instant.now(clock).toString(), transactionId)
                    .update();
            if (written == 0) {
                throw new TransactionException(TransactionException.Reason.NOT_FOUND);
            }
        });
    }

    // ----- identity

    private static String blankToNull(String text) {
        if (text == null || text.isBlank()) {
            return null;
        }
        return text.strip();
    }

    private static String compactIban(String iban) {
        if (iban == null || iban.isBlank()) {
            return null;
        }
        return iban.replaceAll("\\s+", "").toUpperCase(Locale.ROOT);
    }

    /** Lower case, white space collapsed and trimmed: the same text typed a little differently is the same. */
    static String normalise(String text) {
        return text == null ? "" : text.strip().replaceAll("\\s+", " ").toLowerCase(Locale.ROOT);
    }

    /** SHA-256 of booking date, amount, counterparty and description, after normalising the texts. */
    private static String fingerprint(Fields fields) {
        String joined = fields.bookingDate() + "\n" + fields.amountCents() + "\n" + normalise(fields.counterpartyName())
                + "\n" + normalise(fields.description());
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(joined.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is part of every Java runtime", e);
        }
    }

    // ----- rows

    /** The source's fields of a transaction, as stored. Equality is what "changed" means. */
    private record Fields(String bookingDate, String valueDate, long amountCents, String counterpartyName,
            String counterpartyIban, String description, String status, String source, String reference, String raw) {

        Fields withStatus(String newStatus) {
            return new Fields(bookingDate, valueDate, amountCents, counterpartyName, counterpartyIban, description,
                    newStatus, source, reference, raw);
        }
    }

    private record Stored(long id, Fields fields) {
    }

    private static final class Tally {
        int added;
        int updated;
        int known;
        int refused;
    }

    private static final String STORED = """
            select id, booking_date, value_date, amount_cents, counterparty_name, counterparty_iban, description,
                   status, source, reference, raw
            from transactions
            """;

    private static Stored stored(ResultSet rs, int row) throws SQLException {
        return new Stored(rs.getLong("id"), new Fields(
                rs.getString("booking_date"),
                rs.getString("value_date"),
                rs.getLong("amount_cents"),
                rs.getString("counterparty_name"),
                rs.getString("counterparty_iban"),
                rs.getString("description"),
                rs.getString("status"),
                rs.getString("source"),
                rs.getString("reference"),
                rs.getString("raw")));
    }

    private static Transaction transaction(ResultSet rs) throws SQLException {
        String valueDate = rs.getString("value_date");
        return new Transaction(
                rs.getLong("id"),
                rs.getLong("account_id"),
                rs.getString("account_name"),
                LocalDate.parse(rs.getString("booking_date")),
                valueDate == null ? null : LocalDate.parse(valueDate),
                Money.of(rs.getLong("amount_cents"), rs.getString("currency")),
                rs.getString("counterparty_name"),
                rs.getString("counterparty_iban"),
                rs.getString("description"),
                TransactionStatus.parse(rs.getString("status")),
                Source.parse(rs.getString("source")),
                rs.getString("reference"),
                Instant.parse(rs.getString("first_seen_at")),
                Instant.parse(rs.getString("last_seen_at")),
                rs.getString("note"));
    }
}
