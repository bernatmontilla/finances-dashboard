package finances.accounts;

import finances.accounts.FixedCostException.Reason;
import finances.money.Money;
import finances.money.Reserving;
import finances.money.Rhythm;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Fixed costs (issues #21 and #63): what repeats whether one looks or not. No money moves and nothing is subtracted from a headline
 * number. This is the only code that writes the {@code fixed_cost}, {@code fixed_cost_paid}, {@code fixed_cost_match} and
 * {@code fixed_cost_unpaid} tables, and no sync, import or restore path touches them except through {@link #matchPayments()}, which
 * only adds links (hard rule 10). When a charge falls is {@link finances.money.ChargeSchedule}; "today" is the application's own
 * calendar day, as everywhere. Nothing here logs: names, IBANs and amounts are the owner's data (hard rule 11).
 *
 * <p>This month a fixed cost with a charge is paid when the owner ticked it for the month, or when a booked transaction matched it
 * (see {@link #matchPayments()}) and the owner did not untick the match; it is still to come otherwise (also when its date has
 * passed). A tick is kept per month, so editing the day, the rhythm or the amount never loses it.
 */
@Service
public class FixedCostService {

    private static final String COLUMNS = """
            id, name, scope, amount_cents, currency, rhythm, first_due, counterparty_name, counterparty_iban, account_id, paused_at,
            ended_on, created_at, changed_at""";

    private final JdbcClient jdbc;
    private final Clock clock;
    private final AccountService accounts;
    private final TransactionService transactions;

    public FixedCostService(JdbcClient jdbc, Clock clock, AccountService accounts, TransactionService transactions) {
        this.jdbc = jdbc;
        this.clock = clock;
        this.accounts = accounts;
        this.transactions = transactions;
    }

    // ----- reading

    public Optional<FixedCost> find(long id) {
        return jdbc.sql("select " + COLUMNS + " from fixed_cost where id = ?").param(id).query(FixedCostService::map).optional();
    }

    public FixedCost get(long id) {
        return find(id).orElseThrow(() -> new FixedCostException(Reason.NOT_FOUND));
    }

    /** Every fixed cost of a scope as it stands this month, by name: the running ones, then paused, then ended. */
    public List<FixedCostMonth> months(Scope scope) {
        LocalDate today = accounts.today();
        YearMonth month = YearMonth.from(today);
        Set<Long> ticked = idsOfMonth("fixed_cost_paid", month);
        Set<Long> unticked = idsOfMonth("fixed_cost_unpaid", month);
        Map<Long, Long> links = linksOfMonth(month);
        List<FixedCost> costs = jdbc.sql("select " + COLUMNS + " from fixed_cost where scope = ? order by name collate nocase, id")
                .param(scope.code()).query(FixedCostService::map).list();
        List<FixedCostMonth> result = new ArrayList<>();
        for (FixedCost cost : costs) {
            result.add(standing(cost, today, month, ticked.contains(cost.id()), unticked.contains(cost.id()), links.get(cost.id())));
        }
        return result;
    }

    /** One fixed cost as it stands this month. */
    public FixedCostMonth month(long id) {
        FixedCost cost = get(id);
        LocalDate today = accounts.today();
        YearMonth month = YearMonth.from(today);
        return standing(cost, today, month, rowExists("fixed_cost_paid", id, month), rowExists("fixed_cost_unpaid", id, month),
                linksOfMonth(month).get(id));
    }

    /** The totals of a scope and what is still to come this month. */
    public FixedCostSummary summary(Scope scope) {
        LocalDate today = accounts.today();
        List<FixedCostMonth> all = months(scope);
        long perYear = 0;
        int running = 0;
        for (FixedCostMonth month : all) {
            FixedCost cost = month.cost();
            if (cost.isPaused() || cost.isEnded(today)) {
                continue;
            }
            running++;
            perYear = Math.addExact(perYear, Math.multiplyExact(cost.amount().cents(), (long) cost.rhythm().perYear()));
        }
        List<FixedCostMonth> toCome = all.stream().filter(FixedCostMonth::isStillToCome)
                .sorted(Comparator.comparing((FixedCostMonth m) -> m.expected().orElseThrow()).thenComparing(m -> m.cost().name().toLowerCase(java.util.Locale.ROOT))
                        .thenComparingLong(m -> m.cost().id()))
                .toList();
        // A month is a twelfth of a year, rounded up: a cost is never shown lower than it is (as Reserving does).
        return new FixedCostSummary(scope, running, Money.eur(Math.ceilDiv(perYear, 12L)), Money.eur(perYear), toCome,
                Money.sum(toCome.stream().map(FixedCostMonth::amount).toList()), basis(scope, all));
    }

    /**
     * What "still to come" rests on (issue #63, hard rule 9): present when a fixed cost that is still to come, or paid by a match,
     * can be matched at all. Its accounts are its own account when it has one, otherwise every account in use of the scope; the age
     * is the oldest latest delivery among them, so the figure is never read as more current than its least current source.
     */
    private Optional<FixedCostBasis> basis(Scope scope, List<FixedCostMonth> months) {
        Set<Long> involved = new TreeSet<>();
        boolean scopeWide = false;
        for (FixedCostMonth month : months) {
            if (!(month.isStillToCome() || month.isPaidByMatch()) || !canBeMatched(month.cost())) {
                continue;
            }
            if (month.cost().accountId() == null) {
                scopeWide = true;
            } else {
                involved.add(month.cost().accountId());
            }
        }
        if (scopeWide) {
            accounts.list(false).stream().filter(s -> s.account().scope() == scope).forEach(s -> involved.add(s.account().id()));
        }
        if (involved.isEmpty()) {
            return Optional.empty();
        }
        Map<Long, Instant> delivered = transactions.lastDelivered();
        Instant oldest = null;
        for (long accountId : involved) {
            Instant at = delivered.get(accountId);
            if (at == null) {
                return Optional.of(new FixedCostBasis(involved, Optional.empty()));
            }
            if (oldest == null || at.isBefore(oldest)) {
                oldest = at;
            }
        }
        return Optional.of(new FixedCostBasis(involved, Optional.ofNullable(oldest)));
    }

    private static boolean canBeMatched(FixedCost cost) {
        return cost.counterpartyIban() != null || cost.counterpartyName() != null;
    }

    /**
     * The status of this month's charge, the first rule that applies: a tick of the owner means paid by hand; the owner's untick
     * means still to come; a matched transaction means paid by a match; otherwise still to come.
     */
    private FixedCostMonth standing(FixedCost cost, LocalDate today, YearMonth month, boolean ticked, boolean unticked, Long linkedTransaction) {
        Optional<LocalDate> expected = cost.schedule().chargeIn(month);
        boolean ended = cost.isEnded(today);
        Optional<LocalDate> next = cost.isPaused() || ended ? Optional.empty()
                : cost.schedule().nextOnOrAfter(month.plusMonths(1).atDay(1));
        FixedCostMonth.Status status;
        Optional<Transaction> payment = Optional.empty();
        boolean byHand = false;
        if (ended) {
            status = FixedCostMonth.Status.ENDED;
            expected = Optional.empty();
        } else if (cost.isPaused()) {
            status = FixedCostMonth.Status.PAUSED;
            expected = Optional.empty();
        } else if (expected.isPresent()) {
            if (ticked) {
                status = FixedCostMonth.Status.PAID;
                byHand = true;
            } else if (!unticked && linkedTransaction != null) {
                status = FixedCostMonth.Status.PAID;
                payment = Optional.of(transactions.get(linkedTransaction));
            } else {
                status = FixedCostMonth.Status.STILL_TO_COME;
            }
        } else {
            status = FixedCostMonth.Status.NOT_THIS_MONTH;
        }
        boolean overdue = status == FixedCostMonth.Status.STILL_TO_COME && expected.orElseThrow().isBefore(today);
        return new FixedCostMonth(cost, status, expected, next, overdue, payment, byHand);
    }

    private Set<Long> idsOfMonth(String table, YearMonth month) {
        return new HashSet<>(jdbc.sql("select fixed_cost_id from " + table + " where month = ?").param(month.toString())
                .query(Long.class).list());
    }

    private Map<Long, Long> linksOfMonth(YearMonth month) {
        Map<Long, Long> links = new HashMap<>();
        jdbc.sql("select fixed_cost_id, transaction_id from fixed_cost_match where month = ?").param(month.toString())
                .query((rs, row) -> links.put(rs.getLong(1), rs.getLong(2))).list();
        return links;
    }

    private boolean rowExists(String table, long id, YearMonth month) {
        return jdbc.sql("select count(*) from " + table + " where fixed_cost_id = ? and month = ?").params(id, month.toString())
                .query(Integer.class).single() > 0;
    }

    // ----- writing

    @Transactional
    public FixedCost create(String name, Scope scope, Money amount, Rhythm rhythm, LocalDate firstDue, String counterpartyName,
            String counterpartyIban, Long accountId) {
        String cleanName = cleanName(name);
        requireScope(scope);
        requireRhythm(rhythm);
        checkAmount(amount);
        checkFirstDue(firstDue, accounts.today());
        String cleanCounterparty = cleanCounterparty(counterpartyName);
        String cleanIban = AccountService.cleanIban(counterpartyIban);
        checkAccount(accountId, scope);
        KeyHolder key = new GeneratedKeyHolder();
        jdbc.sql("""
                insert into fixed_cost (name, scope, amount_cents, rhythm, first_due, counterparty_name, counterparty_iban, account_id,
                    created_at)
                values (?, ?, ?, ?, ?, ?, ?, ?, ?)""")
                .params(cleanName, scope.code(), amount.cents(), rhythm.code(), firstDue.toString(), cleanCounterparty, cleanIban,
                        accountId, now())
                .update(key);
        matchPayments();
        return get(key.getKey().longValue());
    }

    /** Changes a fixed cost; the ticks stay. An ended fixed cost is not edited. */
    @Transactional
    public FixedCost update(long id, String name, Scope scope, Money amount, Rhythm rhythm, LocalDate firstDue, String counterpartyName,
            String counterpartyIban, Long accountId) {
        FixedCost current = get(id);
        requireNotEnded(current);
        String cleanName = cleanName(name);
        requireScope(scope);
        requireRhythm(rhythm);
        checkAmount(amount);
        LocalDate today = accounts.today();
        if (firstDue == null) {
            throw new FixedCostException(Reason.FIRST_DUE_REQUIRED);
        }
        if (!firstDue.equals(current.firstDue())) {
            checkFirstDue(firstDue, today);
        }
        if (current.endedOn() != null && firstDue.isAfter(current.endedOn())) {
            throw new FixedCostException(Reason.END_BEFORE_FIRST_DUE);
        }
        String cleanCounterparty = cleanCounterparty(counterpartyName);
        String cleanIban = AccountService.cleanIban(counterpartyIban);
        // An account archived since it was linked stays linked: only a new account or a new scope is checked.
        if (!java.util.Objects.equals(accountId, current.accountId()) || scope != current.scope()) {
            checkAccount(accountId, scope);
        }
        jdbc.sql("""
                update fixed_cost set name = ?, scope = ?, amount_cents = ?, rhythm = ?, first_due = ?, counterparty_name = ?,
                    counterparty_iban = ?, account_id = ?, changed_at = ? where id = ?""")
                .params(cleanName, scope.code(), amount.cents(), rhythm.code(), firstDue.toString(), cleanCounterparty, cleanIban,
                        accountId, now(), id)
                .update();
        // What was matched under the old terms may no longer fit: the links of this month and later go and are worked out again.
        // The owner's ticks and unticks stay, and so do the links of earlier months.
        jdbc.sql("delete from fixed_cost_match where fixed_cost_id = ? and month >= ?").params(id, YearMonth.from(today).toString())
                .update();
        matchPayments();
        return get(id);
    }

    /** A paused fixed cost is listed but not counted and never still to come. */
    @Transactional
    public FixedCost pause(long id) {
        FixedCost current = get(id);
        requireNotEnded(current);
        if (current.isPaused()) {
            throw new FixedCostException(Reason.ALREADY_PAUSED);
        }
        jdbc.sql("update fixed_cost set paused_at = ?, changed_at = ? where id = ?").params(now(), now(), id).update();
        return get(id);
    }

    /** Runs a paused or an ended fixed cost again. */
    @Transactional
    public FixedCost resume(long id) {
        FixedCost current = get(id);
        boolean ended = current.isEnded(accounts.today());
        if (!current.isPaused() && !ended) {
            throw new FixedCostException(Reason.NOT_PAUSED_OR_ENDED);
        }
        // An end date still ahead is the owner's: it is only cleared when the fixed cost has actually ended.
        jdbc.sql("update fixed_cost set paused_at = null, ended_on = case when ? then null else ended_on end, changed_at = ? where id = ?")
                .params(ended ? 1 : 0, now(), id).update();
        matchPayments();
        return get(id);
    }

    /**
     * Ends a fixed cost: nothing is charged after the date (today when null), and it stays listed under Ended. It cannot end before
     * its first charge.
     */
    @Transactional
    public FixedCost end(long id, LocalDate endedOn) {
        FixedCost current = get(id);
        requireNotEnded(current);
        LocalDate date = endedOn == null ? accounts.today() : endedOn;
        if (date.isBefore(current.firstDue())) {
            throw new FixedCostException(Reason.END_BEFORE_FIRST_DUE);
        }
        jdbc.sql("update fixed_cost set ended_on = ?, changed_at = ? where id = ?").params(date.toString(), now(), id).update();
        return get(id);
    }

    /** The owner removes a fixed cost that has not ended; its ticks go with it. */
    @Transactional
    public void remove(long id) {
        requireNotEnded(get(id));
        jdbc.sql("delete from fixed_cost where id = ?").param(id).update();
    }

    /** Says this month's charge was paid. Only for this month, and only while it runs and has a charge this month. */
    @Transactional
    public void tick(long id) {
        YearMonth month = requireTickable(id);
        jdbc.sql("insert or ignore into fixed_cost_paid (fixed_cost_id, month, ticked_at) values (?, ?, ?)")
                .params(id, month.toString(), now()).update();
        // A tick is the owner's last word: an earlier untick no longer holds.
        jdbc.sql("delete from fixed_cost_unpaid where fixed_cost_id = ? and month = ?").params(id, month.toString()).update();
    }

    /**
     * Takes the tick of this month away. When a transaction was matched to it, the owner's untick is kept, so that the match does not
     * mark it paid again; without a match nothing more is kept and a payment that arrives later can still mark it.
     */
    @Transactional
    public void untick(long id) {
        YearMonth month = requireTickable(id);
        jdbc.sql("delete from fixed_cost_paid where fixed_cost_id = ? and month = ?").params(id, month.toString()).update();
        if (rowExists("fixed_cost_match", id, month)) {
            jdbc.sql("insert or ignore into fixed_cost_unpaid (fixed_cost_id, month, unticked_at) values (?, ?, ?)")
                    .params(id, month.toString(), now()).update();
        }
    }

    private YearMonth requireTickable(long id) {
        FixedCost cost = get(id);
        LocalDate today = accounts.today();
        if (cost.isEnded(today)) {
            throw new FixedCostException(Reason.ENDED);
        }
        if (cost.isPaused()) {
            throw new FixedCostException(Reason.PAUSED);
        }
        YearMonth month = YearMonth.from(today);
        if (cost.schedule().chargeIn(month).isEmpty()) {
            throw new FixedCostException(Reason.NO_CHARGE_THIS_MONTH);
        }
        return month;
    }

    // ----- matching against transactions (issue #63)

    /** How many days around its expected date a payment may be booked and still be this occurrence's. */
    static final int MATCH_DAYS = 7;

    private record Pair(FixedCost cost, YearMonth month, long transactionId, long amountGap, long dateGap) {
    }

    /**
     * Marks occurrences paid by a booked transaction that matches them. Called after a bank sync, after a CSV import that stored
     * rows and after a fixed cost is created, changed or resumed; never from a page read.
     *
     * <p>An occurrence is the expected date of a running fixed cost that has a counterparty (an IBAN, else a name), in this month or
     * the next one, once its window has begun. A transaction matches it when it is booked, is spending, is on an account of the
     * scope (archived ones included; the account of the fixed cost when it has one), has the counterparty (the IBAN when the fixed
     * cost has one, otherwise the name, both normalised), its amount is within a tenth of the fixed cost's and it is booked within
     * {@link #MATCH_DAYS} days of the expected date. The link is keyed by the month of the expected date, not of the booking.
     *
     * <p>One transaction marks at most one fixed cost (a unique index): the pairs are taken closest amount first, then closest date.
     * An occurrence that already has a link, or that the owner unticked, is left alone; a link is never moved. Running it twice
     * stores nothing twice. It writes only {@code fixed_cost_match}. Nothing is logged: names, IBANs and amounts are the owner's.
     *
     * @return how many links were added
     */
    @Transactional
    public int matchPayments() {
        // The first statement is a write that changes nothing, so the single SQLite writer is taken (and waited for) up front instead of
        // upgrading a read lock halfway, which fails at once when another writer committed in between (as TransactionService.store).
        jdbc.sql("update fixed_cost_match set matched_at = matched_at where 0").update();
        LocalDate today = accounts.today();
        YearMonth current = YearMonth.from(today);
        Set<String> linked = new HashSet<>(jdbc.sql("select fixed_cost_id || ' ' || month from fixed_cost_match").query(String.class).list());
        Set<String> unticked = new HashSet<>(jdbc.sql("select fixed_cost_id || ' ' || month from fixed_cost_unpaid").query(String.class).list());
        List<Pair> pairs = new ArrayList<>();
        List<FixedCost> costs = jdbc.sql("select " + COLUMNS + " from fixed_cost where paused_at is null and (counterparty_name is not null "
                + "or counterparty_iban is not null)").query(FixedCostService::map).list();
        for (FixedCost cost : costs) {
            if (cost.isEnded(today)) {
                continue;
            }
            for (YearMonth month : List.of(current, current.plusMonths(1))) {
                String key = cost.id() + " " + month;
                Optional<LocalDate> expected = cost.schedule().chargeIn(month);
                if (expected.isEmpty() || linked.contains(key) || unticked.contains(key)
                        || expected.get().minusDays(MATCH_DAYS).isAfter(today)) {
                    continue;
                }
                pairs.addAll(candidates(cost, month, expected.get()));
            }
        }
        pairs.sort(Comparator.comparingLong(Pair::amountGap).thenComparingLong(Pair::dateGap).thenComparingLong(Pair::transactionId)
                .thenComparingLong(p -> p.cost().id()));
        Set<Long> usedTransactions = new HashSet<>(jdbc.sql("select transaction_id from fixed_cost_match").query(Long.class).list());
        int added = 0;
        for (Pair pair : pairs) {
            String key = pair.cost().id() + " " + pair.month();
            if (usedTransactions.contains(pair.transactionId()) || linked.contains(key)) {
                continue;
            }
            jdbc.sql("insert into fixed_cost_match (fixed_cost_id, month, transaction_id, matched_at) values (?, ?, ?, ?)")
                    .params(pair.cost().id(), pair.month().toString(), pair.transactionId(), now()).update();
            usedTransactions.add(pair.transactionId());
            linked.add(key);
            added++;
        }
        return added;
    }

    /** A transaction's IBAN as the stored fixed cost IBAN is written: no white space, upper case. Not validated: the bank's text is used. */
    private static String compactIban(String iban) {
        return iban == null ? null : iban.replaceAll("\\s+", "").toUpperCase(java.util.Locale.ROOT);
    }

    /** The booked, unlinked payments that fit one occurrence of a fixed cost. */
    private List<Pair> candidates(FixedCost cost, YearMonth month, LocalDate expected) {
        String sql = """
                select t.id, t.booking_date, t.amount_cents, t.counterparty_name, t.counterparty_iban
                from transactions t join account a on a.id = t.account_id
                where a.scope = ? and t.status = 'booked' and t.amount_cents < 0 and t.booking_date >= ? and t.booking_date <= ?
                  and t.id not in (select transaction_id from fixed_cost_match)""" + (cost.accountId() == null ? "" : " and t.account_id = ?");
        List<Object> params = new ArrayList<>(List.of(cost.scope().code(), expected.minusDays(MATCH_DAYS).toString(),
                expected.plusDays(MATCH_DAYS).toString()));
        if (cost.accountId() != null) {
            params.add(cost.accountId());
        }
        String wantedIban = cost.counterpartyIban();
        String wantedName = TransactionService.normalise(cost.counterpartyName());
        List<Pair> result = new ArrayList<>();
        jdbc.sql(sql).params(params).query((rs, row) -> {
            boolean counterparty = wantedIban != null ? wantedIban.equals(compactIban(rs.getString("counterparty_iban")))
                    : wantedName.equals(TransactionService.normalise(rs.getString("counterparty_name")));
            long cents = rs.getLong("amount_cents");
            if (counterparty && cents != Long.MIN_VALUE) {
                long gap = ReservationService.amountGap(-cents, cost.amount().cents());
                if (ReservationService.amountClose(gap, cost.amount().cents())) {
                    long dateGap = Math.abs(ChronoUnit.DAYS.between(LocalDate.parse(rs.getString("booking_date")), expected));
                    result.add(new Pair(cost, month, rs.getLong("id"), gap, dateGap));
                }
            }
            return null;
        }).list();
        return result;
    }

    // ----- helpers

    private String now() {
        return Instant.now(clock).toString();
    }

    private void requireNotEnded(FixedCost cost) {
        if (cost.isEnded(accounts.today())) {
            throw new FixedCostException(Reason.ENDED);
        }
    }

    private static void requireScope(Scope scope) {
        if (scope == null) {
            throw new FixedCostException(Reason.INVALID_SCOPE);
        }
    }

    private static void requireRhythm(Rhythm rhythm) {
        if (rhythm == null) {
            throw new FixedCostException(Reason.INVALID_RHYTHM);
        }
    }

    private static String cleanName(String name) {
        String clean = name == null ? "" : name.strip();
        if (clean.isEmpty()) {
            throw new FixedCostException(Reason.NAME_REQUIRED);
        }
        if (clean.length() > AccountService.MAX_NAME_LENGTH) {
            throw new FixedCostException(Reason.NAME_TOO_LONG);
        }
        return clean;
    }

    private static String cleanCounterparty(String name) {
        String clean = name == null ? "" : name.strip();
        if (clean.isEmpty()) {
            return null;
        }
        if (clean.length() > AccountService.MAX_NAME_LENGTH) {
            throw new FixedCostException(Reason.COUNTERPARTY_TOO_LONG);
        }
        return clean;
    }

    private static void checkAmount(Money amount) {
        if (amount.cents() <= 0) {
            throw new FixedCostException(Reason.AMOUNT_NOT_POSITIVE);
        }
        if (amount.cents() > Reserving.MAX_AMOUNT_CENTS) {
            throw new FixedCostException(Reason.AMOUNT_TOO_LARGE);
        }
    }

    /** The first charge may be in the past (an existing cost is entered as it is) and at most 50 years ahead. */
    private static void checkFirstDue(LocalDate firstDue, LocalDate today) {
        if (firstDue == null) {
            throw new FixedCostException(Reason.FIRST_DUE_REQUIRED);
        }
        if (firstDue.isAfter(today.plusYears(Reserving.MAX_YEARS_AHEAD))) {
            throw new FixedCostException(Reason.FIRST_DUE_TOO_FAR);
        }
    }

    /** The account a fixed cost leaves from must exist, be in use and be of the same scope. */
    private void checkAccount(Long accountId, Scope scope) {
        if (accountId == null) {
            return;
        }
        Account account = accounts.find(accountId).orElseThrow(() -> new FixedCostException(Reason.ACCOUNT_NOT_FOUND));
        if (account.archived()) {
            throw new FixedCostException(Reason.ACCOUNT_ARCHIVED);
        }
        if (account.scope() != scope) {
            throw new FixedCostException(Reason.ACCOUNT_OUT_OF_SCOPE);
        }
    }

    private static FixedCost map(ResultSet rs, int row) throws SQLException {
        String accountText = rs.getString("account_id");
        String ended = rs.getString("ended_on");
        return new FixedCost(rs.getLong("id"), rs.getString("name"), Scope.parse(rs.getString("scope")),
                Money.of(rs.getLong("amount_cents"), rs.getString("currency")), Rhythm.parse(rs.getString("rhythm")),
                LocalDate.parse(rs.getString("first_due")), rs.getString("counterparty_name"), rs.getString("counterparty_iban"),
                accountText == null ? null : Long.valueOf(accountText), instant(rs.getString("paused_at")),
                ended == null ? null : LocalDate.parse(ended), Instant.parse(rs.getString("created_at")), instant(rs.getString("changed_at")));
    }

    private static Instant instant(String text) {
        return text == null ? null : Instant.parse(text);
    }
}
