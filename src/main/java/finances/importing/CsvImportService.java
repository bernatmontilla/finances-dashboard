package finances.importing;

import finances.accounts.Account;
import finances.accounts.AccountException;
import finances.accounts.AccountService;
import finances.accounts.DeliveredTransaction;
import finances.accounts.DeliveryReport;
import finances.accounts.ImportedBalance;
import finances.accounts.Source;
import finances.accounts.TransactionService;
import finances.money.Money;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.function.Predicate;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;

/**
 * Imports the CSV file of a bank export into an account whose source is {@code csv}. Everything is
 * stored through {@link TransactionService#deliver}, so the same file twice adds nothing (docs/decisions/9-csv-import.md).
 * The file is never stored; only the mapping (settings and column numbers) is kept per account.
 * Nothing is logged: not a row, an amount, a counterparty or an IBAN (hard rule 11).
 */
@Service
public class CsvImportService {

    private final AccountService accounts;
    private final TransactionService transactions;
    private final JdbcClient jdbc;
    private final Clock clock;

    public CsvImportService(AccountService accounts, TransactionService transactions, JdbcClient jdbc, Clock clock) {
        this.accounts = accounts;
        this.transactions = transactions;
        this.jdbc = jdbc;
        this.clock = clock;
    }

    /** The account that a file can be imported into: it exists, is fed by CSV files and is not archived. */
    public Account importable(long accountId) {
        Account account = accounts.get(accountId);
        if (account.source() != Source.CSV) {
            throw new CsvImportException(CsvImportException.Reason.NOT_CSV_ACCOUNT);
        }
        if (account.archived()) {
            throw new AccountException(AccountException.Reason.ARCHIVED);
        }
        return account;
    }

    // ----- the mapping

    /** The mapping saved for the account at its last import, if it can still be read. */
    public Optional<CsvMapping> savedMapping(long accountId) {
        return jdbc.sql("select settings from csv_mapping where account_id = ?")
                .param(accountId)
                .query(String.class)
                .optional()
                .flatMap(text -> {
                    try {
                        return Optional.of(CsvMapping.fromText(text));
                    } catch (CsvImportException e) {
                        return Optional.empty();
                    }
                });
    }

    /**
     * The mapping to offer for a file: the one saved for the account when it fits the file (its columns
     * exist and its date format reads the first rows), otherwise a proposal from the file itself.
     */
    public CsvMapping propose(long accountId, byte[] file) {
        Optional<CsvMapping> saved = savedMapping(accountId);
        if (saved.isPresent() && fits(saved.get(), file)) {
            return saved.get();
        }
        return CsvDetector.detect(file);
    }

    private boolean fits(CsvMapping mapping, byte[] file) {
        if (!mapping.isComplete()) {
            return false;
        }
        String text = CsvFile.decode(file, mapping.encoding());
        List<List<String>> head = CsvFile.head(text, mapping.delimiter());
        int columns = head.stream().mapToInt(List::size).max().orElse(0);
        if (mapping.highestColumn() >= columns) {
            return false;
        }
        ParsedFile parsed = CsvFile.parse(file, mapping, accounts.today());
        return parsed.rows() > 0 && parsed.refused().size() < parsed.rows();
    }

    public void saveMapping(long accountId, CsvMapping mapping) {
        jdbc.sql("""
                        insert into csv_mapping (account_id, settings, updated_at) values (?, ?, ?)
                        on conflict (account_id) do update set settings = excluded.settings, updated_at = excluded.updated_at
                        """)
                .params(accountId, mapping.toText(), Instant.now(clock).toString())
                .update();
    }

    // ----- reading and importing

    /** The first records of the file as the mapping's delimiter and encoding read them (a header row included). */
    public List<List<String>> head(byte[] file, CsvMapping mapping) {
        return CsvFile.head(CsvFile.decode(file, mapping.encoding()), mapping.delimiter());
    }

    /** What the file would give with this mapping for the account, without storing anything. */
    public ParsedFile preview(long accountId, byte[] file, CsvMapping mapping) {
        return CsvFile.parse(file, mapping, accounts.today(), conflictsIn(accountId));
    }

    /**
     * A row whose reference is already stored for the account with another booking date or amount.
     * The store identifies a transaction by its reference, so delivering it would rewrite the earlier one.
     */
    private Predicate<DeliveredTransaction> conflictsIn(long accountId) {
        return t -> jdbc.sql("select booking_date, amount_cents from transactions where account_id = ? and reference = ?")
                .params(accountId, t.reference().strip())
                .query((rs, n) -> !rs.getString(1).equals(t.bookingDate().toString()) || rs.getLong(2) != t.amountCents())
                .optional()
                .orElse(false);
    }

    /**
     * Imports the file into the account and saves the mapping. The rows that can be read are delivered
     * to the transactions store in one batch; the others are listed with their reasons. A file with no
     * readable row stores nothing (not even a delivery time).
     *
     * @throws CsvImportException {@code NOT_CSV_ACCOUNT}, {@code MAPPING_INCOMPLETE}
     */
    public ImportReport importFile(long accountId, byte[] file, CsvMapping mapping) {
        importable(accountId);
        LocalDate today = accounts.today();
        ParsedFile parsed = CsvFile.parse(file, mapping, today, conflictsIn(accountId));

        DeliveryReport delivery = new DeliveryReport(0, 0, 0, 0);
        if (!parsed.transactions().isEmpty()) {
            delivery = transactions.deliver(accountId, Source.CSV, parsed.transactions());
        }
        if (!parsed.transactions().isEmpty()) {
            saveMapping(accountId, mapping); // a mapping that read nothing is not worth offering again
        }

        BalanceResult balance = BalanceResult.NONE;
        if (mapping.balance() >= 0) {
            if (parsed.balanceUndeterminable()) {
                balance = BalanceResult.NOT_DETERMINABLE;
            } else if (parsed.balance() != null) {
                ImportedBalance recorded = accounts.recordImportedBalance(accountId,
                        Money.eur(parsed.balance().cents()), parsed.balance().date());
                balance = BalanceResult.valueOf(recorded.name());
            }
        }
        List<RefusedRow> refused = parsed.refused();
        int refusedTotal = refused.size() + delivery.refused();
        return new ImportReport(parsed.rows(), delivery.added(), delivery.updated(), delivery.known(), refusedTotal,
                refused.size() > ImportReport.LISTED ? refused.subList(0, ImportReport.LISTED) : refused, balance);
    }
}
