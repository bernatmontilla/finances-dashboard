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
import java.util.HashSet;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Stream;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
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
    /**
     * How many days before a booked transaction's date a pending one may be dated and still be the same
     * purchase: banks book a day or more after the day of the purchase (docs/decisions/10-bank-sync.md).
     */
    public static final int PENDING_TAKEOVER_DAYS = 7;
    static final int MAX_NOTE_LENGTH = 1000;

    private static final String SELECT = """
            select t.id, t.account_id, a.name as account_name, t.booking_date, t.value_date, t.amount_cents, t.currency,
                   t.counterparty_name, t.counterparty_iban, t.description, t.status, t.source, t.reference,
                   t.first_seen_at, t.last_seen_at, t.no_longer_reported_at, n.note
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
     * <p>The batch is read inside the write transaction: pass a file or memory, never a stream that waits
     * on the network (a sync collects its pages first; docs/gotchas.md, SQLite allows one writer).
     *
     * @throws TransactionException {@code NOT_FOUND} for an unknown account, {@code ARCHIVED} for an archived one
     */
    public DeliveryReport deliver(long accountId, Source source, Stream<DeliveredTransaction> batch) {
        Objects.requireNonNull(source);
        try (batch) {
            return inTransaction.execute(status -> store(accountId, source, batch).report());
        }
    }

    /**
     * The delivery of a bank sync after a fetch that went well: stores the batch like {@link #deliver}, and
     * then takes care of the pending transactions of this account that the bank did not mention this time,
     * which would otherwise stay pending and be counted as spending for ever. Of the pending ones dated on or
     * after {@code coveredFrom} (the start of the period the fetch covered; null when it reached back as far
     * as the bank allows) that neither arrived again nor became one of the booked ones just delivered, the
     * ones without a note of the owner are removed and the others are kept and marked as no longer reported
     * (hard rule 10). Call it only after a complete fetch: a failed or partial one removes nothing, so it
     * must not call this. This is the only place that deletes a transaction, and only a pending one of source
     * bank; {@link #deliver} never deletes.
     */
    public BankDeliveryReport deliverFromBank(long accountId, Collection<DeliveredTransaction> batch, LocalDate coveredFrom) {
        return deliverFromBank(accountId, batch, coveredFrom, true);
    }

    /**
     * Like {@link #deliverFromBank(long, Collection, LocalDate)}; with {@code complete} false the batch is stored but no pending
     * row is removed or marked: a fetch from which a transaction was refused is not a complete picture of what the bank reports
     * (the refused one may be the booked version of a pending row).
     */
    public BankDeliveryReport deliverFromBank(long accountId, Collection<DeliveredTransaction> batch, LocalDate coveredFrom,
            boolean complete) {
        Objects.requireNonNull(batch);
        return inTransaction.execute(status -> {
            Tally tally = store(accountId, Source.BANK, batch.stream());
            String now = Instant.now(clock).toString();
            int removed = 0;
            int kept = 0;
            if (!complete) {
                return new BankDeliveryReport(tally.report(), 0, 0);
            }
            List<PendingRow> pending = jdbc.sql("""
                            select t.id, t.no_longer_reported_at, n.transaction_id is not null as has_note
                            from transactions t left join transaction_note n on n.transaction_id = t.id
                            where t.account_id = ? and t.status = 'pending' and t.source = 'bank' and t.booking_date >= ?
                            """)
                    .params(accountId, coveredFrom == null ? "0000-01-01" : coveredFrom.toString())
                    .query((rs, row) -> new PendingRow(rs.getLong("id"), rs.getString("no_longer_reported_at") != null, rs.getInt("has_note") == 1))
                    .list();
            for (PendingRow row : pending) {
                if (tally.touched.contains(row.id())) {
                    continue;
                }
                if (!row.hasNote()) {
                    jdbc.sql("delete from transactions where id = ?").param(row.id()).update();
                    removed++;
                } else {
                    if (!row.flagged()) {
                        jdbc.sql("update transactions set no_longer_reported_at = ? where id = ?").params(now, row.id()).update();
                    }
                    kept++;
                }
            }
            return new BankDeliveryReport(tally.report(), removed, kept);
        });
    }

    private record PendingRow(long id, boolean flagged, boolean hasNote) {
    }

    private Tally store(long accountId, Source source, Stream<DeliveredTransaction> batch) {
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
        // Two passes, so the order of the batch decides nothing: first every item is matched to a stored row (by reference or
        // fingerprint) or inserted; only then does a booked item that is left take over a pending row nobody in this batch claimed.
        List<DeliveredTransaction> deferred = new ArrayList<>();
        batch.forEachOrdered(delivered -> {
            if (!storeOne(accountId, source, delivered, now, identical, tally, false)) {
                deferred.add(delivered);
            }
        });
        deferred.forEach(delivered -> storeOne(accountId, source, delivered, now, identical, tally, true));
        return tally;
    }

    /** Stores one item; false (nothing done) when it is a booked item with an unknown reference and the take-over pass has not come yet. */
    private boolean storeOne(long accountId, Source source, DeliveredTransaction t, String now, Map<String, Integer> identical, Tally tally,
            boolean takeOverPass) {
        if (!Money.EUR.equals(t.currency())) {
            tally.refused++;
            return true;
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
        boolean claimed = false;
        int occurrence = 1;
        if (fields.reference() != null) {
            stored = byReference(accountId, fields.reference());
            if (stored == null && t.status() == TransactionStatus.BOOKED) {
                if (!takeOverPass) {
                    return false;
                }
                stored = pendingToTakeOver(accountId, fields, tally.touched);
                claimed = stored != null;
            }
        } else {
            occurrence = identical.merge(fingerprint, 1, Integer::sum);
            stored = byFingerprint(accountId, fingerprint, occurrence);
        }

        if (stored == null) {
            KeyHolder key = new GeneratedKeyHolder();
            jdbc.sql("""
                            insert into transactions (account_id, booking_date, value_date, amount_cents, counterparty_name,
                                counterparty_iban, description, status, source, reference, fingerprint, occurrence, raw,
                                first_seen_at, last_seen_at, dated_by_fetch)
                            values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                            """)
                    .params(accountId, fields.bookingDate(), fields.valueDate(), fields.amountCents(), fields.counterpartyName(),
                            fields.counterpartyIban(), fields.description(), fields.status(), fields.source(), fields.reference(),
                            fingerprint, occurrence, fields.raw(), now, now, t.datedByFetch() && t.status() == TransactionStatus.PENDING ? 1 : 0)
                    .update(key);
            tally.touched.add(key.getKey().longValue());
            tally.added++;
            return true;
        }
        tally.touched.add(stored.id());
        // Delivered again, so no longer "not reported".
        jdbc.sql("update transactions set no_longer_reported_at = null where id = ? and no_longer_reported_at is not null")
                .param(stored.id())
                .update();
        boolean wasBooked = TransactionStatus.BOOKED.code().equals(stored.fields().status());
        if (wasBooked && t.status() == TransactionStatus.PENDING) {
            // A booked transaction stays as it is, whatever a late or repeated pending delivery says.
            jdbc.sql("update transactions set last_seen_at = ? where id = ?").params(now, stored.id()).update();
            tally.known++;
            return true;
        }
        // A delivery without a reference never erases the reference a row already has.
        Fields wanted = fields.reference() == null ? fields.withReference(stored.fields().reference()) : fields;
        if (wanted.equals(stored.fields())) {
            jdbc.sql("update transactions set last_seen_at = ? where id = ?").params(now, stored.id()).update();
            tally.known++;
            return true;
        }
        jdbc.sql("""
                        update transactions set booking_date = ?, value_date = ?, amount_cents = ?, counterparty_name = ?,
                            counterparty_iban = ?, description = ?, status = ?, source = ?, reference = ?, fingerprint = ?,
                            raw = ?, last_seen_at = ?, claimed_pending = max(claimed_pending, ?),
                            dated_by_fetch = case when ? = 1 then dated_by_fetch else 0 end
                        where id = ?
                        """)
                .params(wanted.bookingDate(), wanted.valueDate(), wanted.amountCents(), wanted.counterpartyName(),
                        wanted.counterpartyIban(), wanted.description(), wanted.status(), wanted.source(), wanted.reference(),
                        fingerprint, wanted.raw(), now, claimed ? 1 : 0, t.datedByFetch() && t.status() == TransactionStatus.PENDING ? 1 : 0, stored.id())
                .update();
        tally.updated++;
        return true;
    }

    private Stored byReference(long accountId, String reference) {
        return jdbc.sql(STORED + " where account_id = ? and reference = ?")
                .params(accountId, reference)
                .query(TransactionService::stored)
                .optional()
                .orElse(null);
    }

    private Stored byFingerprint(long accountId, String fingerprint, int occurrence) {
        // A row that was pending and became booked under a reference keeps its place, so that pending
        // deliveries of the identical ones still left over line up with the rows still pending.
        return jdbc.sql(STORED + " where account_id = ? and (reference is null or claimed_pending = 1) and fingerprint = ? and occurrence = ? limit 1")
                .params(accountId, fingerprint, occurrence)
                .query(TransactionService::stored)
                .optional()
                .orElse(null);
    }

    /**
     * The one rule for a pending transaction that arrives as booked under a new reference: it is the oldest
     * pending transaction of the same account with the same amount, the same counterparty and the same
     * description (normalised), dated on or before the booking date and at most
     * {@link #PENDING_TAKEOVER_DAYS} days before it. A pending row whose date is only the day it was fetched (the bank
     * gave it none: {@code dated_by_fetch}) may also be dated up to that many days <em>after</em> the booking date,
     * because the real date of the purchase can be before the day it was first seen. A row this batch already delivered
     * or matched is never taken over: the bank lists it as a transaction of its own, so an identical second purchase
     * stays a second row. The row keeps its identity, and with it the owner's note.
     */
    private Stored pendingToTakeOver(long accountId, Fields booked, Set<Long> touched) {
        LocalDate date = LocalDate.parse(booked.bookingDate());
        String counterparty = normalise(booked.counterpartyName());
        String description = normalise(booked.description());
        return jdbc.sql(STORED + " where account_id = ? and status = 'pending' and amount_cents = ? and booking_date >= ?"
                        + " and (booking_date <= ? or (dated_by_fetch = 1 and booking_date <= ?)) order by booking_date, occurrence, id")
                .params(accountId, booked.amountCents(), date.minusDays(PENDING_TAKEOVER_DAYS).toString(), date.toString(),
                        date.plusDays(PENDING_TAKEOVER_DAYS).toString())
                .query(TransactionService::stored)
                .list()
                .stream()
                .filter(row -> !touched.contains(row.id()))
                .filter(row -> normalise(row.fields().counterpartyName()).equals(counterparty)
                        && normalise(row.fields().description()).equals(description))
                .findFirst()
                .orElse(null);
    }

    /**
     * The date of the oldest pending bank transaction of the account with this amount, counterparty and description
     * (normalised): used for a pending item the bank gave no date at all, so that it keeps the day it was first seen
     * instead of becoming a new row every day, with the owner's note left behind on the old one.
     */
    public Optional<LocalDate> pendingBankDate(long accountId, long amountCents, String counterparty, String description) {
        String wantedCounterparty = normalise(counterparty);
        String wantedDescription = normalise(description);
        return jdbc.sql("select booking_date, counterparty_name, description from transactions where account_id = ? and status = 'pending'"
                        + " and source = 'bank' and amount_cents = ? order by booking_date, id")
                .params(accountId, amountCents)
                .query((rs, row) -> new String[] {rs.getString("booking_date"), rs.getString("counterparty_name"), rs.getString("description")})
                .list()
                .stream()
                .filter(r -> normalise(r[1]).equals(wantedCounterparty) && normalise(r[2]).equals(wantedDescription))
                .map(r -> LocalDate.parse(r[0]))
                .findFirst();
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

    /**
     * The booked spending (negative amounts) of the accounts of a scope, archived ones included, booked from {@code from} to
     * {@code to} inclusive: newest first, at most {@code limit}. Used to pick the payment a reservation is closed against.
     */
    public List<Transaction> bookedSpending(Scope scope, LocalDate from, LocalDate to, int limit) {
        return jdbc.sql(SELECT + " where a.scope = ? and t.status = 'booked' and t.amount_cents < 0 and t.booking_date >= ?"
                        + " and t.booking_date <= ? order by t.booking_date desc, t.id desc limit ?")
                .params(scope.code(), from.toString(), to.toString(), limit)
                .query((rs, row) -> transaction(rs))
                .list();
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

        Fields withReference(String newReference) {
            return new Fields(bookingDate, valueDate, amountCents, counterpartyName, counterpartyIban, description,
                    status, source, newReference, raw);
        }
    }

    private record Stored(long id, Fields fields) {
    }

    private static final class Tally {
        int added;
        int updated;
        int known;
        int refused;
        /** The rows this batch stored, matched or took over: what "delivered in this batch" means. */
        final Set<Long> touched = new HashSet<>();

        DeliveryReport report() {
            return new DeliveryReport(added, updated, known, refused);
        }
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
                rs.getString("no_longer_reported_at") != null,
                rs.getString("note"));
    }
}
