package finances.accounts;

import finances.accounts.ReservationException.Reason;
import finances.money.Money;
import finances.money.Reserving;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Reservations (issue #20): virtual pots for an upcoming expense. No money moves. This is the only code that writes the
 * {@code reservation} table, and no sync, import or restore path touches it (hard rule 10); the rule for what is reserved so far
 * is {@link Reserving}, and "today" is the application's own calendar day, as everywhere. Nothing here logs: names and amounts are
 * the owner's data (hard rule 11).
 *
 * <p>What is reserved so far is never stored: a reservation keeps an anchor (a day and the amount reserved on it) and the rest is
 * worked out. Changing the amount or the due date of a running reservation, and setting what is reserved by hand, move the anchor
 * to today: what was reserved stays reserved (at most the new amount) and the rest is spread over the time left.
 */
@Service
public class ReservationService {

    private static final String COLUMNS = """
            id, name, scope, amount_cents, currency, due_date, yearly, anchor_date, anchor_cents, follows_id,
            created_at, changed_at, closed_at, transaction_id""";

    static final int CANDIDATE_DAYS = 90;
    static final int LIKELY_DAYS = 14;

    private final JdbcClient jdbc;
    private final Clock clock;
    private final AccountService accounts;
    private final TransactionService transactions;

    public ReservationService(JdbcClient jdbc, Clock clock, AccountService accounts, TransactionService transactions) {
        this.jdbc = jdbc;
        this.clock = clock;
        this.accounts = accounts;
        this.transactions = transactions;
    }

    // ----- reading

    public Optional<Reservation> find(long id) {
        return jdbc.sql("select " + COLUMNS + " from reservation where id = ?").param(id).query(ReservationService::map).optional();
    }

    public Reservation get(long id) {
        return find(id).orElseThrow(() -> new ReservationException(Reason.NOT_FOUND));
    }

    /** The open reservations of a scope, the earliest due date first. */
    public List<Reservation> open(Scope scope) {
        return jdbc.sql("select " + COLUMNS + " from reservation where scope = ? and closed_at is null order by due_date, id")
                .param(scope.code()).query(ReservationService::map).list();
    }

    /** The closed reservations of a scope, the one closed last first. */
    public List<Reservation> closed(Scope scope) {
        return jdbc.sql("select " + COLUMNS + " from reservation where scope = ? and closed_at is not null order by closed_at desc, id desc")
                .param(scope.code()).query(ReservationService::map).list();
    }

    /** What an open reservation stands at today. */
    public ReservationStanding standing(Reservation reservation) {
        return standing(reservation, accounts.today());
    }

    private static ReservationStanding standing(Reservation r, LocalDate today) {
        Money reserved = Reserving.reservedOn(r.amount(), r.dueDate(), r.anchorDate(), r.anchorAmount(), today);
        return new ReservationStanding(r, reserved, Reserving.perMonth(r.amount(), r.dueDate(), r.anchorDate(), r.anchorAmount(), today),
                Reserving.overdue(r.dueDate(), today));
    }

    /** The open reservations of a scope as they stand today, in the order of {@link #open}. */
    public List<ReservationStanding> openStandings(Scope scope) {
        LocalDate today = accounts.today();
        return open(scope).stream().map(r -> standing(r, today)).toList();
    }

    /** How many are open, the total reserved so far, and the first {@code limit} that come due (overdue ones first). */
    public ReservationSummary summary(Scope scope, int limit) {
        List<ReservationStanding> standings = openStandings(scope);
        List<Money> amounts = new ArrayList<>();
        for (ReservationStanding standing : standings) {
            amounts.add(standing.reserved());
        }
        return new ReservationSummary(scope, standings.size(), Money.sum(amounts), standings.stream().limit(limit).toList());
    }

    /** The payment a closed reservation is linked to, with the difference to what was reserved; empty when it has no link. */
    public Optional<ReservationPayment> payment(Reservation reservation) {
        if (reservation.transactionId() == null) {
            return Optional.empty();
        }
        Transaction transaction = transactions.get(reservation.transactionId());
        Money paid = transaction.amount().negate();
        return Optional.of(new ReservationPayment(transaction, paid, paid.minus(reservation.amount())));
    }

    /** The recent booked payments an open reservation may be closed against, likely matches first. */
    public List<ReservationCandidate> candidates(long id) {
        Reservation reservation = get(id);
        requireOpen(reservation, Reason.ALREADY_CLOSED);
        LocalDate today = accounts.today();
        return rank(reservation, transactions.bookedSpending(reservation.scope(), today.minusDays(CANDIDATE_DAYS), today,
                TransactionService.PAGE_SIZE));
    }

    /**
     * Orders payments for the picker: likely matches (the paid amount within a tenth of the reserved amount and the booking date
     * within {@value #LIKELY_DAYS} days of the due date) first, the closest amount then the closest date first; the rest follow
     * in the order given (newest first). Integer cents only.
     */
    static List<ReservationCandidate> rank(Reservation reservation, List<Transaction> payments) {
        long reserved = reservation.amount().cents();
        List<ReservationCandidate> likely = new ArrayList<>();
        List<ReservationCandidate> rest = new ArrayList<>();
        for (Transaction payment : payments) {
            Money paid = payment.amount().negate();
            long amountGap = Math.abs(paid.cents() - reserved);
            long dayGap = Math.abs(ChronoUnit.DAYS.between(payment.bookingDate(), reservation.dueDate()));
            boolean close = Math.multiplyExact(10L, amountGap) <= reserved && dayGap <= LIKELY_DAYS;
            (close ? likely : rest).add(new ReservationCandidate(payment, paid, close));
        }
        likely.sort(Comparator.<ReservationCandidate>comparingLong(c -> Math.abs(c.paid().cents() - reserved))
                .thenComparingLong(c -> Math.abs(ChronoUnit.DAYS.between(c.transaction().bookingDate(), reservation.dueDate()))));
        likely.addAll(rest);
        return likely;
    }

    // ----- writing

    /** A new reservation: the build-up starts today at {@code alreadyReserved} (null for nothing). */
    @Transactional
    public Reservation create(String name, Scope scope, Money amount, LocalDate dueDate, boolean yearly, Money alreadyReserved) {
        String cleanName = cleanName(name);
        requireScope(scope);
        checkAmount(amount);
        LocalDate today = accounts.today();
        checkDueDate(dueDate, today);
        Money start = alreadyReserved == null ? Money.zero() : alreadyReserved;
        checkReserved(start, amount);
        return insert(cleanName, scope, amount, dueDate, yearly, today, start, null);
    }

    /**
     * Changes a reservation. A new amount or due date re-anchors it today at what is reserved now (at most the new amount);
     * changing only the name, the scope or the yearly flag keeps the anchor. A closed reservation is not edited.
     */
    @Transactional
    public Reservation update(long id, String name, Scope scope, Money amount, LocalDate dueDate, boolean yearly) {
        Reservation current = get(id);
        requireOpen(current);
        String cleanName = cleanName(name);
        requireScope(scope);
        checkAmount(amount);
        if (dueDate == null) {
            throw new ReservationException(Reason.DUE_DATE_REQUIRED);
        }
        LocalDate today = accounts.today();
        boolean moved = !amount.equals(current.amount()) || !dueDate.equals(current.dueDate());
        if (!dueDate.equals(current.dueDate())) {
            checkDueDate(dueDate, today);
        }
        LocalDate anchorDate = current.anchorDate();
        Money anchorAmount = current.anchorAmount();
        if (moved) {
            Money reservedNow = standing(current, today).reserved();
            anchorDate = today;
            anchorAmount = reservedNow.cents() < amount.cents() ? reservedNow : amount;
        }
        jdbc.sql("""
                update reservation set name = ?, scope = ?, amount_cents = ?, due_date = ?, yearly = ?, anchor_date = ?,
                    anchor_cents = ?, changed_at = ? where id = ?""")
                .params(cleanName, scope.code(), amount.cents(), dueDate.toString(), yearly ? 1 : 0, anchorDate.toString(),
                        anchorAmount.cents(), now(), id)
                .update();
        return get(id);
    }

    /** Sets what is reserved so far by hand (money that is already there): the build-up continues from it, from today. */
    @Transactional
    public Reservation setReserved(long id, Money reserved) {
        Reservation current = get(id);
        requireOpen(current);
        if (!accounts.today().isBefore(current.dueDate())) {
            throw new ReservationException(Reason.ALREADY_DUE);
        }
        checkReserved(reserved, current.amount());
        jdbc.sql("update reservation set anchor_date = ?, anchor_cents = ?, changed_at = ? where id = ?")
                .params(accounts.today().toString(), reserved.cents(), now(), id).update();
        return get(id);
    }

    /**
     * Closes a reservation: it reduces nothing any more and stays listed for history. Closing a yearly one starts next year's
     * round in the same transaction: the same name, amount, scope and flag, due a year later, reserved from nothing.
     */
    @Transactional
    public Reservation close(long id) {
        return close(id, null);
    }

    /**
     * Closes a reservation against the payment it was for (issue #51): a booked, spending transaction of an account in the
     * reservation's scope. The link is kept; nothing is adjusted. A null transaction closes it as {@link #close(long)} does.
     */
    @Transactional
    public Reservation close(long id, Long transactionId) {
        Reservation current = get(id);
        requireOpen(current, Reason.ALREADY_CLOSED);
        if (transactionId != null) {
            checkPayment(current, transactionId);
        }
        jdbc.sql("update reservation set closed_at = ?, transaction_id = ? where id = ?").params(now(), transactionId, id).update();
        if (current.yearly()) {
            insert(current.name(), current.scope(), current.amount(), current.dueDate().plusYears(1), true, accounts.today(),
                    Money.zero(), id);
        }
        return get(id);
    }

    /**
     * Opens a closed reservation again. The next round that closing it started is removed if the owner never touched it; if it was
     * edited or closed the reopening is refused and nothing changes: two open rounds would reserve twice.
     */
    @Transactional
    public Reservation reopen(long id) {
        Reservation current = get(id);
        if (current.isOpen()) {
            throw new ReservationException(Reason.NOT_CLOSED);
        }
        List<Reservation> rounds = jdbc.sql("select " + COLUMNS + " from reservation where follows_id = ?").param(id)
                .query(ReservationService::map).list();
        for (Reservation next : rounds) {
            if (!next.isOpen() || !next.isUntouched()) {
                throw new ReservationException(Reason.NEXT_ROUND_CHANGED);
            }
        }
        for (Reservation next : rounds) {
            jdbc.sql("delete from reservation where id = ?").param(next.id()).update();
        }
        jdbc.sql("update reservation set closed_at = null, transaction_id = null where id = ?").param(id).update();
        return get(id);
    }

    /** The owner removes an open reservation (a closed one is reopened first). A round that followed it stays, unlinked. */
    @Transactional
    public void remove(long id) {
        requireOpen(get(id));
        jdbc.sql("delete from reservation where id = ?").param(id).update();
    }

    // ----- helpers

    /** The payment must exist, be booked (a bank sync deletes pending rows), be spending, and belong to the reservation's scope. */
    private void checkPayment(Reservation reservation, long transactionId) {
        Transaction payment;
        try {
            payment = transactions.get(transactionId);
        } catch (TransactionException e) {
            throw new ReservationException(Reason.TRANSACTION_NOT_FOUND);
        }
        if (payment.pending()) {
            throw new ReservationException(Reason.TRANSACTION_NOT_BOOKED);
        }
        if (accounts.get(payment.accountId()).scope() != reservation.scope()) {
            throw new ReservationException(Reason.TRANSACTION_OTHER_SCOPE);
        }
        if (payment.amount().cents() >= 0) {
            throw new ReservationException(Reason.TRANSACTION_NOT_SPENDING);
        }
    }

    private Reservation insert(String name, Scope scope, Money amount, LocalDate dueDate, boolean yearly, LocalDate anchorDate,
            Money anchorAmount, Long followsId) {
        KeyHolder key = new GeneratedKeyHolder();
        jdbc.sql("""
                insert into reservation (name, scope, amount_cents, due_date, yearly, anchor_date, anchor_cents, follows_id, created_at)
                values (?, ?, ?, ?, ?, ?, ?, ?, ?)""")
                .params(name, scope.code(), amount.cents(), dueDate.toString(), yearly ? 1 : 0, anchorDate.toString(),
                        anchorAmount.cents(), followsId, now())
                .update(key);
        return get(key.getKey().longValue());
    }

    private String now() {
        return Instant.now(clock).toString();
    }

    private static void requireOpen(Reservation reservation) {
        requireOpen(reservation, Reason.CLOSED);
    }

    private static void requireOpen(Reservation reservation, Reason reason) {
        if (!reservation.isOpen()) {
            throw new ReservationException(reason);
        }
    }

    private static void requireScope(Scope scope) {
        if (scope == null) {
            throw new ReservationException(Reason.INVALID_SCOPE);
        }
    }

    private static String cleanName(String name) {
        String clean = name == null ? "" : name.strip();
        if (clean.isEmpty()) {
            throw new ReservationException(Reason.NAME_REQUIRED);
        }
        if (clean.length() > AccountService.MAX_NAME_LENGTH) {
            throw new ReservationException(Reason.NAME_TOO_LONG);
        }
        return clean;
    }

    private static void checkAmount(Money amount) {
        if (amount.cents() <= 0) {
            throw new ReservationException(Reason.AMOUNT_NOT_POSITIVE);
        }
        if (amount.cents() > Reserving.MAX_AMOUNT_CENTS) {
            throw new ReservationException(Reason.AMOUNT_TOO_LARGE);
        }
    }

    /** No earlier than today and at most 50 years ahead: with the amount limit, the build-up cannot overflow. */
    private static void checkDueDate(LocalDate dueDate, LocalDate today) {
        if (dueDate == null) {
            throw new ReservationException(Reason.DUE_DATE_REQUIRED);
        }
        if (dueDate.isBefore(today)) {
            throw new ReservationException(Reason.DUE_DATE_IN_PAST);
        }
        if (dueDate.isAfter(today.plusYears(Reserving.MAX_YEARS_AHEAD))) {
            throw new ReservationException(Reason.DUE_DATE_TOO_FAR);
        }
    }

    private static void checkReserved(Money reserved, Money amount) {
        if (reserved.cents() < 0 || reserved.cents() > amount.cents()) {
            throw new ReservationException(Reason.RESERVED_OUT_OF_RANGE);
        }
    }

    private static Reservation map(ResultSet rs, int row) throws SQLException {
        String followsText = rs.getString("follows_id");
        return new Reservation(rs.getLong("id"), rs.getString("name"), Scope.parse(rs.getString("scope")),
                Money.of(rs.getLong("amount_cents"), rs.getString("currency")), LocalDate.parse(rs.getString("due_date")),
                rs.getInt("yearly") == 1, LocalDate.parse(rs.getString("anchor_date")),
                Money.of(rs.getLong("anchor_cents"), rs.getString("currency")), followsText == null ? null : Long.valueOf(followsText),
                Instant.parse(rs.getString("created_at")), instant(rs.getString("changed_at")), instant(rs.getString("closed_at")),
                rs.getObject("transaction_id") == null ? null : rs.getLong("transaction_id"));
    }

    private static Instant instant(String text) {
        return text == null ? null : Instant.parse(text);
    }
}
