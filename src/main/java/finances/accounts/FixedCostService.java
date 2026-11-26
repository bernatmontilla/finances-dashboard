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
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Fixed costs (issue #21): what repeats whether one looks or not, kept by hand. No money moves and nothing is subtracted from a
 * headline number. This is the only code that writes the {@code fixed_cost} and {@code fixed_cost_paid} tables, and no sync, import
 * or restore path touches them (hard rule 10). When a charge falls is {@link finances.money.ChargeSchedule}; "today" is the
 * application's own calendar day, as everywhere. Nothing here logs: names and amounts are the owner's data (hard rule 11).
 *
 * <p>This month a fixed cost with a charge is paid when it was ticked for the month, and still to come otherwise (also when its
 * date has passed: nothing here matches transactions yet). A tick is kept per month, so editing the day, the rhythm or the amount
 * never loses it.
 */
@Service
public class FixedCostService {

    private static final String COLUMNS = """
            id, name, scope, amount_cents, currency, rhythm, first_due, counterparty_name, counterparty_iban, account_id, paused_at,
            ended_on, created_at, changed_at""";

    private final JdbcClient jdbc;
    private final Clock clock;
    private final AccountService accounts;

    public FixedCostService(JdbcClient jdbc, Clock clock, AccountService accounts) {
        this.jdbc = jdbc;
        this.clock = clock;
        this.accounts = accounts;
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
        Set<Long> ticked = new HashSet<>(jdbc.sql("select fixed_cost_id from fixed_cost_paid where month = ?").param(month.toString())
                .query(Long.class).list());
        List<FixedCost> costs = jdbc.sql("select " + COLUMNS + " from fixed_cost where scope = ? order by name collate nocase, id")
                .param(scope.code()).query(FixedCostService::map).list();
        List<FixedCostMonth> result = new ArrayList<>();
        for (FixedCost cost : costs) {
            result.add(standing(cost, today, month, ticked.contains(cost.id())));
        }
        return result;
    }

    /** One fixed cost as it stands this month. */
    public FixedCostMonth month(long id) {
        FixedCost cost = get(id);
        LocalDate today = accounts.today();
        YearMonth month = YearMonth.from(today);
        return standing(cost, today, month, isTicked(id, month));
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
                Money.sum(toCome.stream().map(FixedCostMonth::amount).toList()));
    }

    private FixedCostMonth standing(FixedCost cost, LocalDate today, YearMonth month, boolean ticked) {
        Optional<LocalDate> expected = cost.schedule().chargeIn(month);
        boolean ended = cost.isEnded(today);
        Optional<LocalDate> next = cost.isPaused() || ended ? Optional.empty()
                : cost.schedule().nextOnOrAfter(month.plusMonths(1).atDay(1));
        FixedCostMonth.Status status;
        if (ended) {
            status = FixedCostMonth.Status.ENDED;
            expected = Optional.empty();
        } else if (cost.isPaused()) {
            status = FixedCostMonth.Status.PAUSED;
            expected = Optional.empty();
        } else if (expected.isPresent()) {
            status = ticked ? FixedCostMonth.Status.PAID : FixedCostMonth.Status.STILL_TO_COME;
        } else {
            status = FixedCostMonth.Status.NOT_THIS_MONTH;
        }
        boolean overdue = status == FixedCostMonth.Status.STILL_TO_COME && expected.orElseThrow().isBefore(today);
        return new FixedCostMonth(cost, status, expected, next, overdue);
    }

    private boolean isTicked(long id, YearMonth month) {
        return jdbc.sql("select count(*) from fixed_cost_paid where fixed_cost_id = ? and month = ?").params(id, month.toString())
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
    }

    /** Takes the tick of this month away: it is still to come again. */
    @Transactional
    public void untick(long id) {
        YearMonth month = requireTickable(id);
        jdbc.sql("delete from fixed_cost_paid where fixed_cost_id = ? and month = ?").params(id, month.toString()).update();
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
