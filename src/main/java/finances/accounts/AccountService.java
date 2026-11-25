package finances.accounts;

import finances.money.Money;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.regex.Pattern;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Accounts and the balances typed in for manual accounts. Nothing here deletes an account:
 * archiving hides it and keeps its history. Nothing is logged: names, IBANs and amounts are the
 * owner's data (AGENTS.md, hard rule 11).
 */
@Service
public class AccountService {

    static final int MAX_NAME_LENGTH = 100;
    /** Format only, no check digits: the fictional IBANs of the tests are not real ones either. */
    private static final Pattern IBAN = Pattern.compile("[A-Z]{2}[0-9]{2}[A-Z0-9]{10,30}");

    private static final String LATEST_BALANCE_JOIN = """
            left join balance b on b.id = (
                select id from balance where account_id = a.id order by balance_date desc, id desc limit 1)
            """;

    private final JdbcClient jdbc;
    private final Clock clock;

    public AccountService(JdbcClient jdbc, Clock clock) {
        this.jdbc = jdbc;
        this.clock = clock;
    }

    /** Today, as the owner's calendar has it: the newest date a balance may be from. */
    public LocalDate today() {
        return LocalDate.now(clock);
    }

    // ----- accounts

    @Transactional
    public Account addManual(String name, Scope scope, Kind kind, String iban) {
        String cleanName = cleanName(name);
        String cleanIban = cleanIban(iban);
        requireIbanFree(cleanIban, -1);
        KeyHolder key = new GeneratedKeyHolder();
        try {
            jdbc.sql("insert into account (name, scope, kind, source, iban, created_at) values (?, ?, ?, ?, ?, ?)")
                    .params(cleanName, scope.code(), kind.code(), Source.MANUAL.code(), cleanIban, Instant.now(clock).toString())
                    .update(key);
        } catch (DataIntegrityViolationException e) {
            throw new AccountException(AccountException.Reason.IBAN_DUPLICATE);
        }
        return get(key.getKey().longValue());
    }

    @Transactional
    public Account update(long id, String name, Scope scope, Kind kind, String iban) {
        Account current = get(id);
        String cleanName = cleanName(name);
        String cleanIban = cleanIban(iban);
        requireIbanFree(cleanIban, id);
        // A saved balance means "owed" for a debt and "held" for anything else: moving an account across
        // that line would silently turn a debt into an asset (or the reverse), so it is refused.
        if ((kind == Kind.DEBT) != (current.kind() == Kind.DEBT) && hasBalance(id)) {
            throw new AccountException(AccountException.Reason.KIND_CHANGE_HAS_BALANCES);
        }
        try {
            jdbc.sql("update account set name = ?, scope = ?, kind = ?, iban = ? where id = ?")
                    .params(cleanName, scope.code(), kind.code(), cleanIban, id)
                    .update();
        } catch (DataIntegrityViolationException e) {
            throw new AccountException(AccountException.Reason.IBAN_DUPLICATE);
        }
        return get(id);
    }

    /** Hides the account from the overview; its balances (and later its transactions) stay. */
    @Transactional
    public void archive(long id) {
        get(id);
        jdbc.sql("update account set archived = 1 where id = ?").param(id).update();
    }

    @Transactional
    public void unarchive(long id) {
        get(id);
        jdbc.sql("update account set archived = 0 where id = ?").param(id).update();
    }

    public Optional<Account> find(long id) {
        return jdbc.sql("select id, name, scope, kind, source, iban, archived from account where id = ?")
                .param(id)
                .query(AccountService::account)
                .optional();
    }

    public Account get(long id) {
        return find(id).orElseThrow(() -> new AccountException(AccountException.Reason.NOT_FOUND));
    }

    /** Active or archived accounts with their latest balance, by scope, then kind, then name. */
    public List<AccountSummary> list(boolean archived) {
        return jdbc.sql("""
                        select a.id, a.name, a.scope, a.kind, a.source, a.iban, a.archived,
                               b.id as balance_id, b.account_id, b.amount_cents, b.currency, b.balance_date, b.entered_at
                        from account a
                        """ + LATEST_BALANCE_JOIN + """
                        where a.archived = ?
                        """)
                .param(archived ? 1 : 0)
                .query((rs, row) -> new AccountSummary(account(rs, row), rs.getObject("balance_id") == null
                        ? Optional.empty()
                        : Optional.of(balance(rs, "balance_id"))))
                .list()
                .stream()
                .sorted(Comparator.comparing((AccountSummary s) -> s.account().scope())
                        .thenComparing(s -> s.account().kind())
                        .thenComparing(s -> s.account().name().toLowerCase(Locale.ROOT))
                        .thenComparingLong(s -> s.account().id()))
                .toList();
    }

    // ----- balances

    /** Newest date first; among the same date, the last typed first. */
    public List<BalanceEntry> history(long accountId) {
        get(accountId);
        return jdbc.sql("""
                        select id, account_id, amount_cents, currency, balance_date, entered_at
                        from balance where account_id = ? order by balance_date desc, id desc
                        """)
                .param(accountId)
                .query((rs, row) -> balance(rs, "id"))
                .list();
    }

    public BalanceEntry getEntry(long accountId, long entryId) {
        return jdbc.sql("""
                        select id, account_id, amount_cents, currency, balance_date, entered_at
                        from balance where id = ? and account_id = ?
                        """)
                .params(entryId, accountId)
                .query((rs, row) -> balance(rs, "id"))
                .optional()
                .orElseThrow(() -> new AccountException(AccountException.Reason.NOT_FOUND));
    }

    @Transactional
    public BalanceEntry addBalance(long accountId, Money amount, LocalDate date) {
        Account account = requireEditable(accountId);
        checkBalance(account, amount, date);
        KeyHolder key = new GeneratedKeyHolder();
        jdbc.sql("insert into balance (account_id, amount_cents, currency, balance_date, entered_at) values (?, ?, ?, ?, ?)")
                .params(accountId, amount.cents(), amount.currency(), date.toString(), Instant.now(clock).toString())
                .update(key);
        return getEntry(accountId, key.getKey().longValue());
    }

    @Transactional
    public BalanceEntry correctBalance(long accountId, long entryId, Money amount, LocalDate date) {
        Account account = requireEditable(accountId);
        getEntry(accountId, entryId);
        checkBalance(account, amount, date);
        jdbc.sql("update balance set amount_cents = ?, currency = ?, balance_date = ? where id = ? and account_id = ?")
                .params(amount.cents(), amount.currency(), date.toString(), entryId, accountId)
                .update();
        return getEntry(accountId, entryId);
    }

    @Transactional
    public void removeBalance(long accountId, long entryId) {
        requireEditable(accountId);
        getEntry(accountId, entryId);
        jdbc.sql("delete from balance where id = ? and account_id = ?").params(entryId, accountId).update();
    }

    // ----- rules

    private Account requireEditable(long accountId) {
        Account account = get(accountId);
        if (account.source() != Source.MANUAL) {
            throw new AccountException(AccountException.Reason.NOT_MANUAL);
        }
        if (account.archived()) {
            throw new AccountException(AccountException.Reason.ARCHIVED);
        }
        return account;
    }

    private void checkBalance(Account account, Money amount, LocalDate date) {
        if (date == null) {
            throw new AccountException(AccountException.Reason.DATE_REQUIRED);
        }
        if (date.isAfter(today())) {
            throw new AccountException(AccountException.Reason.DATE_IN_FUTURE);
        }
        if (account.kind() == Kind.DEBT && amount.isNegative()) {
            throw new AccountException(AccountException.Reason.DEBT_NEGATIVE);
        }
    }

    private boolean hasBalance(long accountId) {
        return jdbc.sql("select count(*) from balance where account_id = ?")
                .param(accountId)
                .query(Integer.class)
                .single() > 0;
    }

    private void requireIbanFree(String iban, long exceptAccountId) {
        if (iban == null) {
            return;
        }
        int taken = jdbc.sql("select count(*) from account where iban = ? and id <> ?")
                .params(iban, exceptAccountId)
                .query(Integer.class)
                .single();
        if (taken > 0) {
            throw new AccountException(AccountException.Reason.IBAN_DUPLICATE);
        }
    }

    private static String cleanName(String name) {
        String clean = name == null ? "" : name.strip();
        if (clean.isEmpty()) {
            throw new AccountException(AccountException.Reason.NAME_REQUIRED);
        }
        if (clean.length() > MAX_NAME_LENGTH) {
            throw new AccountException(AccountException.Reason.NAME_TOO_LONG);
        }
        return clean;
    }

    /** No IBAN is fine (null). Spaces and case do not matter; what is stored is the compact upper-case form. */
    static String cleanIban(String iban) {
        if (iban == null || iban.isBlank()) {
            return null;
        }
        String clean = iban.replaceAll("\\s+", "").toUpperCase(Locale.ROOT);
        if (!IBAN.matcher(clean).matches()) {
            throw new AccountException(AccountException.Reason.IBAN_INVALID);
        }
        return clean;
    }

    // ----- rows

    private static Account account(ResultSet rs, int row) throws SQLException {
        return new Account(
                rs.getLong("id"),
                rs.getString("name"),
                Scope.parse(rs.getString("scope")),
                Kind.parse(rs.getString("kind")),
                Source.parse(rs.getString("source")),
                rs.getString("iban"),
                rs.getInt("archived") == 1);
    }

    private static BalanceEntry balance(ResultSet rs, String idColumn) throws SQLException {
        return new BalanceEntry(
                rs.getLong(idColumn),
                rs.getLong("account_id"),
                Money.of(rs.getLong("amount_cents"), rs.getString("currency")),
                LocalDate.parse(rs.getString("balance_date")),
                Instant.parse(rs.getString("entered_at")));
    }
}
