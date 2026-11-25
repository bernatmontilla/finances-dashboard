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

    /** The owner's time zone, for showing a moment (stored in UTC) as a calendar day. */
    public java.time.ZoneId zone() {
        return clock.getZone();
    }

    /** Today, as the owner's calendar has it: the newest date a balance may be from. */
    public LocalDate today() {
        return LocalDate.now(clock);
    }

    // ----- accounts

    @Transactional
    public Account addManual(String name, Scope scope, Kind kind, String iban) {
        return add(name, scope, kind, iban, Source.MANUAL);
    }

    /** A new account fed by CSV files (issue #9): its balance is typed in or comes from an imported file. */
    @Transactional
    public Account addCsv(String name, Scope scope, Kind kind, String iban) {
        return add(name, scope, kind, iban, Source.CSV);
    }

    /** An account created from a bank link, after the owner confirmed it: its source is {@code bank}. */
    @Transactional
    public Account addFromBank(String name, Scope scope, Kind kind, String iban) {
        return insert(name, scope, kind, iban, Source.BANK);
    }

    /** Only the sources that need no bank connection can be chosen; a bank account comes from the sync. */
    @Transactional
    public Account add(String name, Scope scope, Kind kind, String iban, Source source) {
        if (source != Source.MANUAL && source != Source.CSV) {
            throw new AccountException(AccountException.Reason.INVALID_SOURCE);
        }
        return insert(name, scope, kind, iban, source);
    }

    private Account insert(String name, Scope scope, Kind kind, String iban, Source source) {
        String cleanName = cleanName(name);
        String cleanIban = cleanIban(iban);
        requireIbanFree(cleanIban, -1);
        KeyHolder key = new GeneratedKeyHolder();
        try {
            jdbc.sql("insert into account (name, scope, kind, source, iban, created_at) values (?, ?, ?, ?, ?, ?)")
                    .params(cleanName, scope.code(), kind.code(), source.code(), cleanIban, Instant.now(clock).toString())
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

    /**
     * Makes an existing account one that a bank feeds: its source becomes {@code bank}. Its history, balances
     * and notes stay. Refused for an archived account and for one that already has a bank link.
     */
    @Transactional
    public Account attachToBank(long id) {
        Account account = get(id);
        if (account.archived()) {
            throw new AccountException(AccountException.Reason.ARCHIVED);
        }
        if (account.source() == Source.BANK) {
            throw new AccountException(AccountException.Reason.ALREADY_BANK);
        }
        jdbc.sql("update account set source = ? where id = ?").params(Source.BANK.code(), id).update();
        return get(id);
    }

    /** Unlinking a bank: the account and all its history stay and it becomes a manual account. */
    @Transactional
    public void detachFromBank(long id) {
        jdbc.sql("update account set source = ? where id = ? and source = ?")
                .params(Source.MANUAL.code(), id, Source.BANK.code())
                .update();
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

    /**
     * Stores a balance a bank reported, with its type and how old the bank says it is. Only the sync calls
     * this. A debt is stored as the amount owed, whatever sign the bank gave it. A balance equal to the
     * newest bank balance of the account (same type, amount and bank timestamp) is not stored again.
     * Returns whether a row was added.
     *
     * @param date the day the balance is from: the day of the bank's timestamp or its reference date, else the day of the fetch
     * @param asOf the bank's timestamp, or the moment of the fetch when the bank gave none
     * @param basis how {@code asOf} is known
     */
    @Transactional
    public boolean addBankBalance(long accountId, Money amount, LocalDate date, String type, Instant asOf, AsOfBasis basis) {
        Account account = get(accountId);
        Money stored = account.kind() == Kind.DEBT && amount.isNegative() ? amount.negate() : amount;
        LocalDate day = date.isAfter(today()) ? today() : date;
        if (basis != AsOfBasis.FETCHED) {
            Optional<BankBalanceInfo> newest = latestBankBalanceRow(accountId);
            if (newest.isPresent() && newest.get().type().equals(type) && newest.get().basis() == basis
                    && newest.get().amount().equals(stored) && newest.get().date().equals(day)
                    && java.util.Objects.equals(newest.get().asOf(), basis == AsOfBasis.BANK_TIME ? asOf : null)) {
                return false;
            }
        }
        jdbc.sql("""
                        insert into balance (account_id, amount_cents, currency, balance_date, entered_at, balance_type, as_of, as_of_basis)
                        values (?, ?, ?, ?, ?, ?, ?, ?)
                        """)
                .params(accountId, stored.cents(), stored.currency(), day.toString(), Instant.now(clock).toString(), type,
                        basis == AsOfBasis.BANK_DATE ? null : asOf.toString(), basis.code())
                .update();
        return true;
    }

    /** The newest balance a bank reported for the account, with its type and how its age is known. */
    public Optional<BankBalanceInfo> latestBankBalance(long accountId) {
        return latestBankBalanceRow(accountId);
    }

    /** The newest bank balance of every account that has one, by account id (one query, for the lists). */
    public java.util.Map<Long, BankBalanceInfo> latestBankBalances() {
        java.util.Map<Long, BankBalanceInfo> latest = new java.util.HashMap<>();
        jdbc.sql("""
                        select b.account_id, b.amount_cents, b.currency, b.balance_date, b.balance_type, b.as_of, b.as_of_basis
                        from balance b
                        where b.as_of_basis is not null and b.id = (
                            select id from balance where account_id = b.account_id and as_of_basis is not null
                            order by balance_date desc, id desc limit 1)
                        """)
                .query((rs, row) -> {
                    latest.put(rs.getLong("account_id"), new BankBalanceInfo(
                            Money.of(rs.getLong("amount_cents"), rs.getString("currency")),
                            LocalDate.parse(rs.getString("balance_date")),
                            rs.getString("balance_type"),
                            rs.getString("as_of") == null ? null : Instant.parse(rs.getString("as_of")),
                            AsOfBasis.parse(rs.getString("as_of_basis"))));
                    return null;
                })
                .list();
        return latest;
    }

    private Optional<BankBalanceInfo> latestBankBalanceRow(long accountId) {
        return jdbc.sql("""
                        select amount_cents, currency, balance_date, balance_type, as_of, as_of_basis
                        from balance where account_id = ? and as_of_basis is not null
                        order by balance_date desc, id desc limit 1
                        """)
                .param(accountId)
                .query((rs, row) -> new BankBalanceInfo(
                        Money.of(rs.getLong("amount_cents"), rs.getString("currency")),
                        LocalDate.parse(rs.getString("balance_date")),
                        rs.getString("balance_type"),
                        rs.getString("as_of") == null ? null : Instant.parse(rs.getString("as_of")),
                        AsOfBasis.parse(rs.getString("as_of_basis"))))
                .optional();
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

    /**
     * Records the balance a CSV file ended with (the balance-after column of its newest row), as one more
     * entry in the history: nothing the owner typed is changed or removed. Importing the same file again
     * finds the same date and amount and adds nothing; when the history already has an entry from the same
     * or a later date (typed or corrected by the owner, or imported) the file's one is left out, so an
     * import never pushes a figure the owner entered out of sight.
     */
    @Transactional
    public ImportedBalance recordImportedBalance(long accountId, Money amount, LocalDate date) {
        Account account = get(accountId);
        if (account.source() != Source.CSV) {
            throw new AccountException(AccountException.Reason.NOT_CSV);
        }
        if (account.archived()) {
            throw new AccountException(AccountException.Reason.ARCHIVED);
        }
        if (date.isAfter(today()) || (account.kind() == Kind.DEBT && amount.isNegative())) {
            return ImportedBalance.REFUSED;
        }
        int same = jdbc.sql("select count(*) from balance where account_id = ? and balance_date = ? and amount_cents = ?")
                .params(accountId, date.toString(), amount.cents())
                .query(Integer.class)
                .single();
        if (same > 0) {
            return ImportedBalance.UNCHANGED;
        }
        int newer = jdbc.sql("select count(*) from balance where account_id = ? and balance_date >= ?")
                .params(accountId, date.toString())
                .query(Integer.class)
                .single();
        if (newer > 0) {
            return ImportedBalance.OLDER;
        }
        jdbc.sql("insert into balance (account_id, amount_cents, currency, balance_date, entered_at) values (?, ?, ?, ?, ?)")
                .params(accountId, amount.cents(), amount.currency(), date.toString(), Instant.now(clock).toString())
                .update();
        return ImportedBalance.UPDATED;
    }

    // ----- rules

    /** A balance can be typed in for an account that is not fed by a bank: a manual one or a CSV one. */
    private Account requireEditable(long accountId) {
        Account account = get(accountId);
        if (account.source() == Source.BANK) {
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

    /** Whether an IBAN would be accepted (format only), so that a caller can leave an odd one out instead of failing. */
    public static boolean isValidIban(String iban) {
        return iban != null && IBAN.matcher(iban.replaceAll("\\s+", "").toUpperCase(Locale.ROOT)).matches();
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
