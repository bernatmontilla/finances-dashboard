package finances.importing;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import finances.accounts.Account;
import finances.accounts.AccountException;
import finances.accounts.AccountService;
import finances.accounts.BalanceEntry;
import finances.accounts.FixedCostMonth;
import finances.accounts.FixedCostProposalService;
import finances.accounts.FixedCostService;
import finances.accounts.Kind;
import finances.accounts.Scope;
import finances.accounts.Transaction;
import finances.accounts.TransactionService;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.sqlite.SQLiteConfig;
import org.sqlite.SQLiteDataSource;

/** Importing CSV files into a real, migrated SQLite file, without the web layer. Fictional data only. */
class CsvImportServiceTest {

    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-06-15T10:00:00Z"), ZoneOffset.UTC);

    private AccountService accounts;
    private TransactionService transactions;
    private FixedCostService fixedCosts;
    private CsvImportService importing;
    private JdbcClient jdbc;
    private Account csv;

    @BeforeEach
    void open(@TempDir Path folder) {
        SQLiteConfig config = new SQLiteConfig();
        config.enforceForeignKeys(true);
        SQLiteDataSource dataSource = new SQLiteDataSource(config);
        dataSource.setUrl("jdbc:sqlite:" + folder.resolve("finances.db"));
        Flyway.configure().dataSource(dataSource).locations("classpath:db/migration").load().migrate();
        jdbc = JdbcClient.create(dataSource);
        accounts = new AccountService(jdbc, CLOCK);
        transactions = new TransactionService(jdbc, CLOCK, new DataSourceTransactionManager(dataSource));
        fixedCosts = new FixedCostService(jdbc, CLOCK, accounts, transactions);
        importing = new CsvImportService(accounts, transactions, fixedCosts,
                new FixedCostProposalService(jdbc, CLOCK, accounts, new DataSourceTransactionManager(dataSource)), jdbc, CLOCK);
        csv = accounts.addCsv("Imported current", Scope.PRIVATE, Kind.CURRENT, "NL51XMPL0000000001");
    }

    private static byte[] file(String name) throws IOException {
        try (InputStream in = CsvImportServiceTest.class.getResourceAsStream("/csv/" + name)) {
            return in.readAllBytes();
        }
    }

    private static byte[] text(String text) {
        return text.getBytes(StandardCharsets.UTF_8);
    }

    private int stored() {
        return jdbc.sql("select count(*) from transactions where account_id = ?").param(csv.id()).query(Integer.class).single();
    }

    private ImportReport importDetected(byte[] bytes) {
        return importing.importFile(csv.id(), bytes, CsvDetector.detect(bytes));
    }

    /** As {@link #importDetected}, with the confirmation a changed mapping on stored rows needs (#37). */
    private ImportReport importDetectedConfirmed(byte[] bytes) {
        return importing.importFile(csv.id(), bytes, CsvDetector.detect(bytes), true);
    }

    // ----- shape 1: semicolon, decimal comma, debit and credit columns, Windows-1252

    @Test
    void aSemicolonFileWithDecimalCommaAndSeparateDebitAndCreditColumnsImportsOnceAndNotTwice() throws IOException {
        byte[] bytes = CsvFixtures.semicolonDebitCredit();

        CsvMapping mapping = CsvDetector.detect(bytes);
        assertThat(mapping.delimiter()).isEqualTo(';');
        assertThat(mapping.encoding()).isEqualTo(CsvMapping.WINDOWS_1252);
        assertThat(mapping.header()).isTrue();
        assertThat(mapping.dateFormat()).isEqualTo("dd-MM-yyyy");
        assertThat(mapping.decimal()).isEqualTo(',');
        assertThat(mapping).extracting(CsvMapping::date, CsvMapping::amount, CsvMapping::debit, CsvMapping::credit,
                CsvMapping::name, CsvMapping::iban, CsvMapping::description, CsvMapping::reference, CsvMapping::balance)
                .containsExactly(0, -1, 4, 5, 1, 2, 3, -1, -1);

        ImportReport first = importing.importFile(csv.id(), bytes, mapping);
        assertThat(first).extracting(ImportReport::rows, ImportReport::added, ImportReport::updated, ImportReport::known, ImportReport::refused)
                .containsExactly(5, 5, 0, 0, 0);
        assertThat(first.balance()).isEqualTo(BalanceResult.NONE);

        List<Transaction> all = transactions.forAccount(csv.id(), 0).items();
        assertThat(all).extracting(t -> t.amount().cents()).containsExactlyInAnyOrder(-1234L, -123456L, 250000L, -350L, -350L);
        assertThat(all).extracting(Transaction::counterpartyName).contains("Example Café", "Corner Grocery");
        assertThat(all).extracting(Transaction::bookingDate).contains(LocalDate.of(2026, 6, 3), LocalDate.of(2026, 6, 5));

        ImportReport second = importing.importFile(csv.id(), bytes, mapping);
        assertThat(second).extracting(ImportReport::added, ImportReport::updated, ImportReport::known, ImportReport::refused)
                .containsExactly(0, 0, 5, 0);
        assertThat(stored()).isEqualTo(5);
    }

    // ----- shape 2: comma, decimal point, one signed amount, BOM, quoted fields, empty trailing lines

    @Test
    void aCommaFileWithDecimalPointAndASignedAmountImportsOnceAndNotTwice() throws IOException {
        byte[] bytes = file("comma-signed.csv");

        CsvMapping mapping = CsvDetector.detect(bytes);
        assertThat(mapping.delimiter()).isEqualTo(',');
        assertThat(mapping.encoding()).isEqualTo(CsvMapping.UTF_8);
        assertThat(mapping.header()).isTrue();
        assertThat(mapping.dateFormat()).isEqualTo("yyyy-MM-dd");
        assertThat(mapping.decimal()).isEqualTo('.');
        assertThat(mapping).extracting(CsvMapping::date, CsvMapping::amount, CsvMapping::name, CsvMapping::iban,
                CsvMapping::description, CsvMapping::reference).containsExactly(0, 1, 2, 3, 4, -1);
        mapping = mapping.toBuilder().reference(5).build(); // a reference is never proposed: the owner chooses it

        ImportReport first = importing.importFile(csv.id(), bytes, mapping);
        assertThat(first).extracting(ImportReport::rows, ImportReport::added, ImportReport::refused).containsExactly(4, 4, 0);
        List<Transaction> all = transactions.forAccount(csv.id(), 0).items();
        assertThat(all).extracting(t -> t.amount().cents()).containsExactlyInAnyOrder(-1234L, -123456L, 10000L, -500L);
        assertThat(all).extracting(Transaction::description).contains("Rent, June", "Line one\nline two".replace("\n", "\r\n"));

        ImportReport second = importing.importFile(csv.id(), bytes, mapping);
        assertThat(second).extracting(ImportReport::added, ImportReport::updated, ImportReport::known).containsExactly(0, 0, 4);
        assertThat(stored()).isEqualTo(4);
    }

    // ----- fixed costs matched after an import (issue #63)

    @Test
    void anImportedRowThatPaysAFixedCostMarksItPaidAndTheSameFileAgainAddsNoLink() {
        var energy = fixedCosts.create("Example Energy", Scope.PRIVATE, finances.money.Money.parse("100.00"), finances.money.Rhythm.MONTHLY,
                LocalDate.of(2026, 1, 15), null, "NL10SHOP0000000101", null);
        assertThat(fixedCosts.month(energy.id()).status()).isEqualTo(FixedCostMonth.Status.STILL_TO_COME);
        byte[] bytes = text("date,amount,name,iban,description\n2026-06-14,-100.00,Example Energy,NL10SHOP0000000101,Energy June\n");

        assertThat(importDetected(bytes).added()).isEqualTo(1);

        FixedCostMonth month = fixedCosts.month(energy.id());
        assertThat(month.status()).isEqualTo(FixedCostMonth.Status.PAID);
        assertThat(month.payment()).isPresent();
        assertThat(jdbc.sql("select count(*) from fixed_cost_match").query(Integer.class).single()).isEqualTo(1);

        assertThat(importDetectedConfirmed(bytes).added()).isZero();
        assertThat(jdbc.sql("select count(*) from fixed_cost_match").query(Integer.class).single()).isEqualTo(1);
    }

    // ----- fixed cost proposals after an import (issue #71)

    @Test
    void anImportOfRepeatingChargesProposesAFixedCostAndTheSameFileAgainStoresTheSameProposal() {
        byte[] bytes = text("date,amount,name,iban,description\n2026-04-14,-20.00,Example Telecom,NL80SHOP0000000102,April\n"
                + "2026-05-14,-20.00,Example Telecom,NL80SHOP0000000102,May\n2026-06-14,-20.00,Example Telecom,NL80SHOP0000000102,June\n");

        assertThat(importDetected(bytes).added()).isEqualTo(3);

        assertThat(jdbc.sql("select count(*) from fixed_cost_proposal").query(Integer.class).single()).isEqualTo(1);
        assertThat(jdbc.sql("select count(*) from fixed_cost_proposal_charge").query(Integer.class).single()).isEqualTo(3);
        assertThat(jdbc.sql("select count(*) from fixed_cost").query(Integer.class).single()).as("nothing is added by itself").isZero();
        long id = jdbc.sql("select id from fixed_cost_proposal").query(Long.class).single();

        assertThat(importDetectedConfirmed(bytes).added()).isZero();
        assertThat(jdbc.sql("select id from fixed_cost_proposal").query(Long.class).single()).isEqualTo(id);
    }

    @Test
    void aFailureWhileLookingForProposalsDoesNotFailTheImport() {
        jdbc.sql("drop table fixed_cost_proposal_charge").update();
        jdbc.sql("drop table fixed_cost_proposal").update();
        byte[] bytes = text("date,amount,name,iban,description\n2026-06-14,-100.00,Example Energy,NL10SHOP0000000101,Energy June\n");

        assertThat(importDetected(bytes).added()).isEqualTo(1);
    }

    // ----- shape 3: tab, a balance column, thousands separators

    @Test
    void aTabFileWithABalanceColumnImportsOnceUpdatesTheBalanceAndNotTwice() throws IOException {
        byte[] bytes = file("tab-balance.csv");

        CsvMapping mapping = CsvDetector.detect(bytes);
        assertThat(mapping.delimiter()).isEqualTo('\t');
        assertThat(mapping.dateFormat()).isEqualTo("dd/MM/yyyy");
        assertThat(mapping.decimal()).isEqualTo('.');
        assertThat(mapping).extracting(CsvMapping::date, CsvMapping::amount, CsvMapping::name, CsvMapping::description, CsvMapping::balance)
                .containsExactly(0, 1, 2, 3, 4);

        ImportReport first = importing.importFile(csv.id(), bytes, mapping);
        assertThat(first).extracting(ImportReport::added, ImportReport::refused).containsExactly(3, 0);
        assertThat(first.balance()).isEqualTo(BalanceResult.UPDATED);
        List<BalanceEntry> history = accounts.history(csv.id());
        assertThat(history).hasSize(1);
        assertThat(history.get(0).amount().cents()).isEqualTo(248000);
        assertThat(history.get(0).date()).isEqualTo(LocalDate.of(2026, 6, 3));

        ImportReport second = importing.importFile(csv.id(), bytes, mapping);
        assertThat(second).extracting(ImportReport::added, ImportReport::known).containsExactly(0, 3);
        assertThat(second.balance()).isEqualTo(BalanceResult.UNCHANGED);
        assertThat(stored()).isEqualTo(3);
        assertThat(accounts.history(csv.id())).hasSize(1);
    }

    // ----- bad rows

    @Test
    void aFileWithTwoBadRowsImportsTheOthersAndListsTheBadOnesWithTheirReasons() throws IOException {
        ImportReport report = importDetected(file("two-bad-rows.csv"));

        assertThat(report).extracting(ImportReport::rows, ImportReport::added, ImportReport::refused).containsExactly(4, 2, 2);
        assertThat(report.refusedRows()).containsExactly(
                new RefusedRow(3, RefusalReason.DATE_INVALID),
                new RefusedRow(4, RefusalReason.AMOUNT_AMBIGUOUS));
        assertThat(report.everyRowFailed()).isFalse();
        assertThat(transactions.forAccount(csv.id(), 0).items()).extracting(t -> t.amount().cents()).containsExactlyInAnyOrder(-1000L, -550L);
    }

    @Test
    void aFileInWhichEveryRowFailsImportsNothingAndSaysSo() {
        byte[] bytes = text("Date;Amount\n31-02-2026;-1,00\nnot a date;-2,00\n");

        ImportReport report = importDetected(bytes);

        assertThat(report.everyRowFailed()).isTrue();
        assertThat(report).extracting(ImportReport::rows, ImportReport::added, ImportReport::refused).containsExactly(2, 0, 2);
        assertThat(stored()).isZero();
        assertThat(transactions.lastDelivered(csv.id())).as("nothing was delivered").isEmpty();
        assertThat(importing.savedMapping(csv.id())).as("a mapping that read nothing is not kept").isEmpty();
    }

    @Test
    void aBlankLineBeforeTheHeaderDoesNotMakeTheHeaderARow() {
        ImportReport report = importDetected(text("\n\nDate;Amount\n01-06-2026;-1,00\n"));

        assertThat(report).extracting(ImportReport::rows, ImportReport::added, ImportReport::refused).containsExactly(1, 1, 0);
    }

    @Test
    void aRepeatedReferenceIsRefusedSoItNeverOverwritesAnotherTransaction() {
        CsvMapping mapping = new CsvMapping(';', CsvMapping.UTF_8, true, "dd-MM-yyyy", ',', 0, 1, -1, -1, -1, -1, -1, 2, -1);
        byte[] bytes = text("Date;Amount;Reference\n01-06-2026;-12,34;NOTPROVIDED\n02-06-2026;-56,78;NOTPROVIDED\n03-06-2026;-1,00;R-3\n04-06-2026;-2,00;\n05-06-2026;-3,00;\n");

        ImportReport report = importing.importFile(csv.id(), bytes, mapping);

        assertThat(report.added()).isEqualTo(4);
        assertThat(report.refusedRows()).containsExactly(new RefusedRow(3, RefusalReason.DUPLICATE_REFERENCE));
        assertThat(transactions.forAccount(csv.id(), 0).items()).extracting(t -> t.amount().cents())
                .containsExactlyInAnyOrder(-1234L, -100L, -200L, -300L);
    }

    @Test
    void aReferenceSharedByTwoMonthlyFilesNeverOverwritesTheEarlierTransaction() {
        CsvMapping mapping = new CsvMapping(';', CsvMapping.UTF_8, true, "dd-MM-yyyy", ',', 0, 1, -1, -1, -1, -1, -1, 2, -1);
        byte[] october = text("Date;Amount;Reference\n01-05-2026;-100,00;EE-MANDATE-1\n");
        byte[] november = text("Date;Amount;Reference\n01-06-2026;-100,00;EE-MANDATE-1\n02-06-2026;-5,00;R-2\n");
        importing.importFile(csv.id(), october, mapping);

        assertThat(importing.preview(csv.id(), november, mapping).refused())
                .containsExactly(new RefusedRow(2, RefusalReason.REFERENCE_CONFLICT));
        ImportReport report = importing.importFile(csv.id(), november, mapping);

        assertThat(report).extracting(ImportReport::added, ImportReport::updated, ImportReport::refused).containsExactly(1, 0, 1);
        assertThat(report.refusedRows()).containsExactly(new RefusedRow(2, RefusalReason.REFERENCE_CONFLICT));
        assertThat(transactions.forAccount(csv.id(), 0).items()).extracting(t -> t.bookingDate())
                .containsExactlyInAnyOrder(LocalDate.of(2026, 5, 1), LocalDate.of(2026, 6, 2));

        ImportReport again = importing.importFile(csv.id(), october, mapping);
        assertThat(again).extracting(ImportReport::added, ImportReport::updated, ImportReport::known).containsExactly(0, 0, 1);
    }

    @Test
    void theDetectorNeverProposesAReferenceColumn() {
        assertThat(CsvDetector.detect(text("Date;Amount;Mandate reference\n01-06-2026;-1,00;EE-0001\n")).reference()).isEqualTo(-1);
    }

    @Test
    void aBalanceTheOwnerCorrectedOrTypedOnTheSameDayIsNotPushedAside() {
        CsvMapping mapping = new CsvMapping(';', CsvMapping.UTF_8, true, "dd-MM-yyyy", ',', 0, 1, -1, -1, -1, -1, -1, -1, 2);
        byte[] bytes = text("Date;Amount;Balance\n10-06-2026;-1,00;1.000,00\n");
        importing.importFile(csv.id(), bytes, mapping);
        BalanceEntry imported = accounts.history(csv.id()).get(0);
        accounts.correctBalance(csv.id(), imported.id(), finances.money.Money.eur(101000), imported.date());

        ImportReport again = importing.importFile(csv.id(), bytes, mapping);

        assertThat(again.balance()).isEqualTo(BalanceResult.OLDER);
        assertThat(accounts.history(csv.id())).extracting(e -> e.amount().cents()).containsExactly(101000L);

        Account other = accounts.addCsv("Typed first", Scope.PRIVATE, Kind.CURRENT, null);
        accounts.addBalance(other.id(), finances.money.Money.eur(105000), LocalDate.of(2026, 6, 10));
        assertThat(importing.importFile(other.id(), bytes, mapping).balance()).isEqualTo(BalanceResult.OLDER);
        assertThat(accounts.history(other.id())).extracting(e -> e.amount().cents()).containsExactly(105000L);
    }

    @Test
    void whenARowCouldNotBeReadTheBalanceIsLeftAloneBecauseItMayBeTheNewestRow() {
        CsvMapping mapping = new CsvMapping(';', CsvMapping.UTF_8, true, "dd-MM-yyyy", ',', 0, 1, -1, -1, -1, -1, -1, -1, 2);

        ImportReport report = importing.importFile(csv.id(),
                text("Date;Amount;Balance\n19-11-2026;-10,00;990,00\n20-11-2026;1,234;2.224,00\n".replace("19-11-2026", "13-06-2026").replace("20-11-2026", "14-06-2026")),
                mapping);

        assertThat(report.added()).isEqualTo(1);
        assertThat(report.refused()).isEqualTo(1);
        assertThat(report.balance()).isEqualTo(BalanceResult.NOT_DETERMINABLE);
        assertThat(accounts.history(csv.id())).isEmpty();
    }

    @Test
    void aFileWithOnlyAHeaderOrNothingImportsNothing() {
        byte[] header = text("Date;Amount;Description\n");
        CsvMapping mapping = new CsvMapping(';', CsvMapping.UTF_8, true, "dd-MM-yyyy", ',', 0, 1, -1, -1, -1, -1, 2, -1, -1);

        ImportReport report = importing.importFile(csv.id(), header, mapping);
        assertThat(report.noRows()).isTrue();
        assertThat(report.added()).isZero();
        assertThat(importing.importFile(csv.id(), new byte[0], mapping).noRows()).isTrue();
        assertThat(stored()).isZero();
    }

    @Test
    void aRowInTheFutureIsRefusedAndAnAmountIsNeverGuessed() {
        byte[] bytes = text("Date;Amount\n16-06-2026;-1,00\n15-06-2026;-2,00\n14-06-2026;12,5x\n13-06-2026;\n12-06-2026\n11-06-2026;1,234\n");

        ImportReport report = importing.importFile(csv.id(), bytes,
                new CsvMapping(';', CsvMapping.UTF_8, true, "dd-MM-yyyy", ',', 0, 1, -1, -1, -1, -1, -1, -1, -1));

        assertThat(report.added()).isEqualTo(1); // today is fine
        assertThat(report.refusedRows()).containsExactly(
                new RefusedRow(2, RefusalReason.DATE_IN_FUTURE),
                new RefusedRow(4, RefusalReason.AMOUNT_NOT_A_NUMBER),
                new RefusedRow(5, RefusalReason.AMOUNT_EMPTY),
                new RefusedRow(6, RefusalReason.TOO_FEW_COLUMNS),
                new RefusedRow(7, RefusalReason.AMOUNT_AMBIGUOUS));
    }

    @Test
    void aDebitAndACreditInOneRowOrASignInThem() {
        byte[] bytes = text("Date;Debit;Credit\n01-06-2026;1,00;2,00\n02-06-2026;;\n03-06-2026;-3,00;\n04-06-2026;;4,00\n");

        ImportReport report = importing.importFile(csv.id(), bytes,
                new CsvMapping(';', CsvMapping.UTF_8, true, "dd-MM-yyyy", ',', 0, -1, 1, 2, -1, -1, -1, -1, -1));

        assertThat(report.added()).isEqualTo(1);
        assertThat(report.refusedRows()).containsExactly(
                new RefusedRow(2, RefusalReason.DEBIT_AND_CREDIT),
                new RefusedRow(3, RefusalReason.DEBIT_AND_CREDIT),
                new RefusedRow(4, RefusalReason.SIGN_IN_DEBIT_CREDIT));
    }

    // ----- the mapping is kept per account

    @Test
    void theMappingIsSavedPerAccountAndOfferedAgainForTheNextFile() throws IOException {
        byte[] first = CsvFixtures.semicolonDebitCredit();
        CsvMapping detected = CsvDetector.detect(first);
        // The owner moves the description to another column: the next upload offers what was saved.
        CsvMapping changed = detected.toBuilder().description(-1).build();
        importing.importFile(csv.id(), first, changed);

        assertThat(importing.savedMapping(csv.id())).contains(changed);
        byte[] next = text("Booking date;Counterparty;Counterparty IBAN;Description;Debit;Credit\r\n06-06-2026;Example Gym;;Gym;9,99;\r\n");
        assertThat(importing.propose(csv.id(), first).description()).isEqualTo(-1);

        Account other = accounts.addCsv("Another", Scope.PRIVATE, Kind.CURRENT, null);
        assertThat(importing.savedMapping(other.id())).isEmpty();
        assertThat(importing.propose(other.id(), next).description()).isEqualTo(3);
    }

    @Test
    void aSavedMappingThatDoesNotFitTheFileIsNotOffered() throws IOException {
        importing.importFile(csv.id(), file("tab-balance.csv"), CsvDetector.detect(file("tab-balance.csv")));

        CsvMapping offered = importing.propose(csv.id(), CsvFixtures.semicolonDebitCredit());

        assertThat(offered.delimiter()).isEqualTo(';');
    }

    @Test
    void aMappingThatIsReadBackFromItsTextIsTheSameAndATamperedOneIsRefused() {
        CsvMapping mapping = new CsvMapping('\t', CsvMapping.WINDOWS_1252, false, "MM/dd/yyyy", ',', 3, -1, 1, 2, 4, 5, 6, 7, 8);
        assertThat(CsvMapping.fromText(mapping.toText())).isEqualTo(mapping);

        assertThatThrownBy(() -> CsvMapping.fromText(mapping.toText().replace("dateFormat=MM/dd/yyyy", "dateFormat=yyyy;DROP")))
                .isInstanceOf(CsvImportException.class);
        assertThatThrownBy(() -> CsvMapping.fromText("nonsense")).isInstanceOf(CsvImportException.class);
        assertThatThrownBy(() -> mapping.toBuilder().amount(1).build()).as("both an amount and a debit/credit pair")
                .isInstanceOf(CsvImportException.class);
    }

    // ----- balance

    @Test
    void theBalanceOfTheNewestRowIsUsedWhateverTheOrderOfTheFile() {
        CsvMapping mapping = new CsvMapping(';', CsvMapping.UTF_8, true, "dd-MM-yyyy", ',', 0, 1, -1, -1, -1, -1, -1, -1, 2);

        // Newest first, two rows on the newest day: the first of them is the last of the day.
        importing.importFile(csv.id(), text("Date;Amount;Balance\n03-06-2026;-1,00;90,00\n03-06-2026;-5,00;91,00\n02-06-2026;-1,00;96,00\n"), mapping);
        assertThat(accounts.history(csv.id())).extracting(e -> e.amount().cents()).containsExactly(9000L);
    }

    @Test
    void anOldFileNeverPushesANewerBalanceOutAndTheOwnersEntriesStay() {
        CsvMapping mapping = new CsvMapping(';', CsvMapping.UTF_8, true, "dd-MM-yyyy", ',', 0, 1, -1, -1, -1, -1, -1, -1, 2);
        accounts.addBalance(csv.id(), finances.money.Money.eur(5000), LocalDate.of(2026, 6, 10));

        ImportReport older = importing.importFile(csv.id(), text("Date;Amount;Balance\n01-06-2026;-1,00;42,00\n"), mapping);
        assertThat(older.balance()).isEqualTo(BalanceResult.OLDER);
        assertThat(accounts.history(csv.id())).extracting(e -> e.amount().cents()).containsExactly(5000L);

        ImportReport newer = importing.importFile(csv.id(), text("Date;Amount;Balance\n12-06-2026;-1,00;77,00\n"), mapping);
        assertThat(newer.balance()).isEqualTo(BalanceResult.UPDATED);
        assertThat(accounts.history(csv.id())).extracting(e -> e.amount().cents()).containsExactly(7700L, 5000L);
    }

    @Test
    void whenTheOrderOfTheNewestDayCannotBeToldTheBalanceIsLeftAlone() {
        CsvMapping mapping = new CsvMapping(';', CsvMapping.UTF_8, true, "dd-MM-yyyy", ',', 0, 1, -1, -1, -1, -1, -1, -1, 2);

        ImportReport report = importing.importFile(csv.id(), text("Date;Amount;Balance\n03-06-2026;-1,00;90,00\n03-06-2026;-5,00;91,00\n"), mapping);

        assertThat(report.added()).isEqualTo(2);
        assertThat(report.balance()).isEqualTo(BalanceResult.NOT_DETERMINABLE);
        assertThat(accounts.history(csv.id())).isEmpty();
    }

    @Test
    void aFileWithoutABalanceColumnLeavesTheBalanceAlone() throws IOException {
        accounts.addBalance(csv.id(), finances.money.Money.eur(5000), LocalDate.of(2026, 6, 10));

        importDetected(file("comma-signed.csv"));

        assertThat(accounts.history(csv.id())).hasSize(1);
    }

    @Test
    void aBadBalanceCellRefusesItsRow() {
        CsvMapping mapping = new CsvMapping(';', CsvMapping.UTF_8, true, "dd-MM-yyyy", ',', 0, 1, -1, -1, -1, -1, -1, -1, 2);

        ImportReport report = importing.importFile(csv.id(), text("Date;Amount;Balance\n03-06-2026;-1,00;abc\n04-06-2026;-1,00;\n"), mapping);

        assertThat(report.refusedRows()).containsExactly(new RefusedRow(2, RefusalReason.BALANCE_INVALID));
        assertThat(report.added()).isEqualTo(1);
    }

    // ----- accounts

    @Test
    void onlyAnAccountWhoseSourceIsCsvTakesAFile() {
        Account manual = accounts.addManual("By hand", Scope.PRIVATE, Kind.CURRENT, null);
        byte[] bytes = text("Date;Amount\n01-06-2026;-1,00\n");
        CsvMapping mapping = new CsvMapping(';', CsvMapping.UTF_8, true, "dd-MM-yyyy", ',', 0, 1, -1, -1, -1, -1, -1, -1, -1);

        assertThatThrownBy(() -> importing.importFile(manual.id(), bytes, mapping))
                .isInstanceOfSatisfying(CsvImportException.class, e -> assertThat(e.reason()).isEqualTo(CsvImportException.Reason.NOT_CSV_ACCOUNT));
        accounts.archive(csv.id());
        assertThatThrownBy(() -> importing.importFile(csv.id(), bytes, mapping))
                .isInstanceOfSatisfying(AccountException.class, e -> assertThat(e.reason()).isEqualTo(AccountException.Reason.ARCHIVED));
        assertThatThrownBy(() -> importing.importFile(9999, bytes, mapping)).isInstanceOf(AccountException.class);
    }

    @Test
    void aMappingWithoutADateOrAnAmountCannotImport() {
        CsvMapping noAmount = new CsvMapping(';', CsvMapping.UTF_8, true, "dd-MM-yyyy", ',', 0, -1, -1, -1, -1, -1, -1, -1, -1);
        assertThatThrownBy(() -> importing.importFile(csv.id(), text("Date;Amount\n01-06-2026;-1,00\n"), noAmount))
                .isInstanceOfSatisfying(CsvImportException.class, e -> assertThat(e.reason()).isEqualTo(CsvImportException.Reason.MAPPING_INCOMPLETE));
    }

    @Test
    void aCsvAccountCanHaveItsBalanceTypedInLikeAManualOne() {
        assertThat(accounts.addBalance(csv.id(), finances.money.Money.eur(1234), LocalDate.of(2026, 6, 1)).amount().cents()).isEqualTo(1234);
        assertThat(accounts.get(csv.id()).source().code()).isEqualTo("csv");
    }

    @Test
    void anAccountCannotBeCreatedWithTheBankSource() {
        assertThatThrownBy(() -> accounts.add("Bank", Scope.PRIVATE, Kind.CURRENT, null, finances.accounts.Source.BANK))
                .isInstanceOfSatisfying(AccountException.class, e -> assertThat(e.reason()).isEqualTo(AccountException.Reason.INVALID_SOURCE));
    }

    // ----- a changed mapping on data that is already there (#37)

    private ParsedFile previewOf(CsvMapping mapping, byte[] bytes) {
        return importing.preview(csv.id(), bytes, mapping);
    }

    @Test
    void theSameMappingOnTheSameFileGivesNoWarningAndAddsNothing() {
        byte[] bytes = CsvFixtures.semicolonDebitCredit();
        CsvMapping saved = CsvDetector.detect(bytes);
        importing.importFile(csv.id(), bytes, saved);
        assertThat(stored()).isEqualTo(5);

        ImportCheck check = importing.check(csv.id(), saved, previewOf(saved, bytes));

        assertThat(check.needsConfirmation()).isFalse();
        assertThat(check.storedInRange()).isZero();
        ImportReport again = importing.importFile(csv.id(), bytes, saved);
        assertThat(again.added()).isZero();
        assertThat(again.known()).isEqualTo(5);
        assertThat(stored()).isEqualTo(5);
    }

    @Test
    void aChangedDescriptionColumnOnStoredRowsWarnsAndIsRefusedWithoutConfirmation() {
        byte[] bytes = CsvFixtures.semicolonDebitCredit();
        CsvMapping saved = CsvDetector.detect(bytes);
        importing.importFile(csv.id(), bytes, saved);
        CsvMapping changed = saved.toBuilder().description(-1).build();

        ImportCheck check = importing.check(csv.id(), changed, previewOf(changed, bytes));

        assertThat(check.needsConfirmation()).isTrue();
        assertThat(check.storedInRange()).isEqualTo(5);
        assertThatThrownBy(() -> importing.importFile(csv.id(), bytes, changed))
                .isInstanceOfSatisfying(CsvImportException.class,
                        e -> assertThat(e.reason()).isEqualTo(CsvImportException.Reason.CONFIRMATION_REQUIRED));
        assertThat(stored()).as("nothing was imported").isEqualTo(5);
        assertThat(importing.savedMapping(csv.id())).as("the saved mapping is untouched").contains(saved);

        ImportReport confirmed = importing.importFile(csv.id(), bytes, changed, true);

        assertThat(confirmed.added()).as("this is the risk the owner confirmed").isPositive();
        assertThat(importing.savedMapping(csv.id())).contains(changed);
    }

    @Test
    void everyFieldThatFeedsATransactionsIdentityWarnsWhenItChanges() {
        byte[] bytes = CsvFixtures.semicolonDebitCredit();
        CsvMapping saved = CsvDetector.detect(bytes);
        importing.importFile(csv.id(), bytes, saved);

        List<CsvMapping> changes = new java.util.ArrayList<>();
        for (String field : List.of("name", "iban", "description", "reference")) {
            CsvMapping.Builder b = saved.toBuilder();
            switch (field) {
                case "name" -> b.name = -1;
                case "iban" -> b.iban = -1;
                case "description" -> b.description = -1;
                default -> b.reference = 3; // a reference column that was not there
            }
            changes.add(b.build());
        }
        CsvMapping.Builder swapped = saved.toBuilder();
        swapped.debit = saved.credit();
        swapped.credit = saved.debit();
        changes.add(swapped.build());
        CsvMapping.Builder otherDate = saved.toBuilder();
        otherDate.dateFormat = "MM/dd/yyyy";
        changes.add(otherDate.build());
        CsvMapping.Builder otherDecimal = saved.toBuilder();
        otherDecimal.decimal = '.';
        changes.add(otherDecimal.build());
        CsvMapping.Builder otherEncoding = saved.toBuilder();
        otherEncoding.encoding = CsvMapping.UTF_8;
        changes.add(otherEncoding.build());

        for (CsvMapping changed : changes) {
            ParsedFile parsed = previewOf(changed, bytes);
            if (parsed.transactions().isEmpty()) {
                continue; // a date format that reads no row stores nothing, so there is nothing to warn about
            }
            assertThat(importing.check(csv.id(), changed, parsed).needsConfirmation()).as(changed.toString()).isTrue();
        }
        assertThat(changes.stream().filter(c -> !previewOf(c, bytes).transactions().isEmpty()).count()).isGreaterThanOrEqualTo(5);
    }

    @Test
    void aSettingThatDoesNotFeedTheIdentityDoesNotWarn() {
        byte[] bytes = text("Date;Amount;Balance;Description\n03-06-2026;-1,00;90,00;Gym\n04-06-2026;-2,00;88,00;Gym\n");
        CsvMapping saved = new CsvMapping(';', CsvMapping.UTF_8, true, "dd-MM-yyyy", ',', 0, 1, -1, -1, -1, -1, 3, -1, 2);
        importing.importFile(csv.id(), bytes, saved);
        CsvMapping.Builder builder = saved.toBuilder();
        builder.balance = -1;
        builder.delimiter = ';';
        builder.header = true;
        CsvMapping noBalance = builder.build();

        assertThat(importing.check(csv.id(), noBalance, previewOf(noBalance, bytes)).needsConfirmation()).isFalse();
    }

    @Test
    void aChangedMappingOnAFileWhoseRangeHasNothingStoredImportsAsBefore() {
        byte[] june = text("Date;Amount;Description\n03-06-2026;-1,00;Gym\n04-06-2026;-2,00;Gym\n");
        CsvMapping saved = new CsvMapping(';', CsvMapping.UTF_8, true, "dd-MM-yyyy", ',', 0, 1, -1, -1, -1, -1, 2, -1, -1);
        importing.importFile(csv.id(), june, saved);
        byte[] may = text("Date;Amount;Description\n03-05-2026;-1,00;Gym\n04-05-2026;-2,00;Gym\n");
        CsvMapping changed = saved.toBuilder().description(-1).build();

        ImportCheck check = importing.check(csv.id(), changed, previewOf(changed, may));

        assertThat(check.needsConfirmation()).isFalse();
        assertThat(importing.importFile(csv.id(), may, changed).added()).isEqualTo(2);
    }

    @Test
    void theFirstImportOfAnAccountHasNoSavedMappingAndNeedsNoConfirmation() {
        byte[] bytes = CsvFixtures.semicolonDebitCredit();
        CsvMapping mapping = CsvDetector.detect(bytes);

        assertThat(importing.check(csv.id(), mapping, previewOf(mapping, bytes)).needsConfirmation()).isFalse();
        assertThat(importing.importFile(csv.id(), bytes, mapping).added()).isEqualTo(5);
    }

    @Test
    void theStoredRowsAreCountedOnlyInsideTheRangeOfTheFile() {
        byte[] bytes = CsvFixtures.semicolonDebitCredit(); // 3 to 5 June: 5 rows
        CsvMapping saved = CsvDetector.detect(bytes);
        importing.importFile(csv.id(), bytes, saved);
        CsvMapping changed = saved.toBuilder().description(-1).build();
        byte[] narrow = text("Booking date;Counterparty;Counterparty IBAN;Description;Debit;Credit\r\n04-06-2026;Example Energy;NL80SHOP0000000102;Energy June;1.234,56;\r\n");

        ImportCheck check = importing.check(csv.id(), changed, previewOf(changed, narrow));

        assertThat(check.storedInRange()).as("only the one stored row of 4 June").isEqualTo(1);
    }

    // ----- no negative amounts (#37)

    @Test
    void aFileWithOnlyPositiveAmountsOnACurrentAccountWarnsThatEverythingIsMoneyComingIn() {
        byte[] bytes = text("Date;Amount\n01-06-2026;5,00\n02-06-2026;7,50\n");
        CsvMapping mapping = new CsvMapping(';', CsvMapping.UTF_8, true, "dd-MM-yyyy", ',', 0, 1, -1, -1, -1, -1, -1, -1, -1);

        ImportCheck check = importing.check(csv.id(), mapping, previewOf(mapping, bytes));

        assertThat(check.allIncoming()).isTrue();
        assertThat(check.needsConfirmation()).as("a warning only, it does not block").isFalse();
        assertThat(importing.importFile(csv.id(), bytes, mapping).added()).isEqualTo(2);
    }

    @Test
    void theSameFileOnASavingsAccountDoesNotWarn() {
        Account savings = accounts.addCsv("Imported savings", Scope.PRIVATE, Kind.SAVINGS, "NL24XMPL0000000002");
        byte[] bytes = text("Date;Amount\n01-06-2026;5,00\n02-06-2026;7,50\n");
        CsvMapping mapping = new CsvMapping(';', CsvMapping.UTF_8, true, "dd-MM-yyyy", ',', 0, 1, -1, -1, -1, -1, -1, -1, -1);

        assertThat(importing.check(savings.id(), mapping, importing.preview(savings.id(), bytes, mapping)).allIncoming()).isFalse();
    }

    @Test
    void oneNegativeRowADebitCreditMappingOrNoRowsMeansNoIncomeWarning() {
        CsvMapping signed = new CsvMapping(';', CsvMapping.UTF_8, true, "dd-MM-yyyy", ',', 0, 1, -1, -1, -1, -1, -1, -1, -1);
        byte[] mixed = text("Date;Amount\n01-06-2026;5,00\n02-06-2026;-7,50\n");
        assertThat(importing.check(csv.id(), signed, previewOf(signed, mixed)).allIncoming()).isFalse();

        byte[] debitCredit = text("Date;Debit;Credit\n01-06-2026;;5,00\n02-06-2026;;7,50\n");
        CsvMapping split = new CsvMapping(';', CsvMapping.UTF_8, true, "dd-MM-yyyy", ',', 0, -1, 1, 2, -1, -1, -1, -1, -1);
        assertThat(importing.check(csv.id(), split, previewOf(split, debitCredit)).allIncoming()).isFalse();

        byte[] none = text("Date;Amount\n");
        assertThat(importing.check(csv.id(), signed, previewOf(signed, none)).allIncoming()).isFalse();
    }

    // ----- the file is read twice only for a head check (#37)

    @Test
    void whetherASavedMappingFitsReadsOnlyTheFirstRowsOfTheFile() {
        StringBuilder big = new StringBuilder("Date;Amount\n");
        for (int i = 0; i < 500; i++) {
            big.append("03-06-2026;-1,00\n");
        }
        CsvMapping mapping = new CsvMapping(';', CsvMapping.UTF_8, true, "dd-MM-yyyy", ',', 0, 1, -1, -1, -1, -1, -1, -1, -1);

        ParsedFile head = CsvFile.parseHead(text(big.toString()), mapping, LocalDate.of(2026, 6, 15), 60);

        assertThat(head.rows()).isEqualTo(60);
        assertThat(CsvFile.parse(text(big.toString()), mapping, LocalDate.of(2026, 6, 15)).rows()).isEqualTo(500);
    }

    @Test
    void aSavedMappingStillFitsAFileWhoseLaterRowsAreBadButItsFirstRowsRead() {
        byte[] bytes = text("Date;Amount\n03-06-2026;-1,00\n04-06-2026;-2,00\n");
        CsvMapping mapping = new CsvMapping(';', CsvMapping.UTF_8, true, "dd-MM-yyyy", ',', 0, 1, -1, -1, -1, -1, -1, -1, -1);
        importing.importFile(csv.id(), bytes, mapping);

        assertThat(importing.propose(csv.id(), bytes)).isEqualTo(mapping);
    }

    // ----- what reaches the log

    @Test
    void nothingOfTheFileReachesTheLog() throws IOException {
        Logger root = (Logger) LoggerFactory.getLogger(Logger.ROOT_LOGGER_NAME);
        Level before = root.getLevel();
        ListAppender<ILoggingEvent> log = new ListAppender<>();
        log.start();
        root.addAppender(log);
        root.setLevel(Level.DEBUG);
        try {
            importDetectedConfirmed(file("comma-signed.csv"));
            importDetectedConfirmed(file("comma-signed.csv"));
            importDetectedConfirmed(file("two-bad-rows.csv"));
            importDetectedConfirmed(text("Date;Amount\n31-02-2026;-123,45\n"));
        } finally {
            root.detachAppender(log);
            root.setLevel(before);
        }
        String everything = log.list.stream().map(ILoggingEvent::getFormattedMessage).reduce("", (a, b) -> a + "\n" + b);
        assertThat(everything).doesNotContain("Corner Grocery", "Example Landlord", "NL10SHOP", "NL80SHOP", "1,234.56", "123,45", "REF-00", "Rent");
    }
}
