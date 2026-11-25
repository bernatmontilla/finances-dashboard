package finances.web;

import finances.accounts.Account;
import finances.importing.CsvMapping;
import finances.importing.ImportCheck;
import finances.importing.ImportReport;
import finances.importing.ParsedFile;
import finances.importing.RefusedRow;
import finances.money.Money;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * What the import pages show and read: the settings as the form names them (words, not characters),
 * the columns as numbers from 1, the reasons as message keys. The templates hold no text.
 */
final class ImportViews {

    static final List<String> DELIMITERS = List.of("comma", "semicolon", "tab");
    static final List<String> DECIMALS = List.of("comma", "point");
    static final List<String> ENCODINGS = List.of("utf-8", "windows-1252");
    /** The column fields in the order they are shown; each has a message {@code import.field.<name>}. */
    static final List<String> FIELDS = List.of("date", "amount", "debit", "credit", "name", "iban", "description", "reference", "balance");
    static final int REFUSED_SHOWN = 10;

    private ImportViews() {
    }

    record Field(String name, int value) {
    }

    record Refused(int row, String reasonKey) {
    }

    record Preview(Account account, String token, String delimiter, String encoding, boolean header, String dateFormat,
            String decimal, List<Field> fields, int columns, List<String> headers, List<List<String>> rows, boolean complete,
            int read, int importable, int refusedCount, List<Refused> refused, String balance, String balanceDate,
            boolean balanceUndeterminable, int storedInRange, boolean allIncoming) {

        /** The rows may be stored a second time: the page asks for a confirmation. */
        public boolean needsConfirmation() {
            return storedInRange > 0;
        }

        public boolean hasRefused() {
            return !refused.isEmpty();
        }

        public boolean hasBalance() {
            return balance != null;
        }

        public boolean isEmpty() {
            return rows.isEmpty();
        }
    }

    record Report(Account account, ImportReport report, List<Refused> refused, String balanceKey) {

        public boolean hasRefused() {
            return !refused.isEmpty();
        }

        public boolean hasBalanceResult() {
            return report.balance() != finances.importing.BalanceResult.NONE;
        }

        public boolean hasMoreRefused() {
            return report.refused() > refused.size();
        }
    }

    static String delimiterCode(char delimiter) {
        return delimiter == ',' ? "comma" : delimiter == ';' ? "semicolon" : "tab";
    }

    static char delimiter(String code) {
        return switch (code) {
            case "comma" -> ',';
            case "semicolon" -> ';';
            case "tab" -> '\t';
            default -> throw new IllegalArgumentException();
        };
    }

    static String encodingCode(String encoding) {
        return encoding.toLowerCase(Locale.ROOT);
    }

    static String encoding(String code) {
        return switch (code) {
            case "utf-8" -> CsvMapping.UTF_8;
            case "windows-1252" -> CsvMapping.WINDOWS_1252;
            default -> throw new IllegalArgumentException();
        };
    }

    static String decimalCode(char decimal) {
        return decimal == ',' ? "comma" : "point";
    }

    static char decimal(String code) {
        return switch (code) {
            case "comma" -> ',';
            case "point" -> '.';
            default -> throw new IllegalArgumentException();
        };
    }

    static int column(CsvMapping m, String field) {
        return switch (field) {
            case "date" -> m.date();
            case "amount" -> m.amount();
            case "debit" -> m.debit();
            case "credit" -> m.credit();
            case "name" -> m.name();
            case "iban" -> m.iban();
            case "description" -> m.description();
            case "reference" -> m.reference();
            default -> m.balance();
        };
    }

    /** The mapping a form asks for. A setting or column that is not one of the allowed ones refuses the whole form. */
    static CsvMapping mapping(Map<String, String> form) {
        try {
            return new CsvMapping(delimiter(form.getOrDefault("delimiter", "")), encoding(form.getOrDefault("encoding", "")),
                    "true".equals(form.get("header")), form.getOrDefault("dateFormat", ""), decimal(form.getOrDefault("decimal", "")),
                    number(form, "date"), number(form, "amount"), number(form, "debit"), number(form, "credit"),
                    number(form, "name"), number(form, "iban"), number(form, "description"), number(form, "reference"),
                    number(form, "balance"));
        } catch (IllegalArgumentException e) {
            throw new finances.importing.CsvImportException(finances.importing.CsvImportException.Reason.MAPPING_INVALID);
        }
    }

    private static int number(Map<String, String> form, String name) {
        String text = form.getOrDefault(name, "-1").strip();
        return text.isEmpty() ? -1 : Integer.parseInt(text);
    }

    static Preview preview(Account account, String token, CsvMapping m, List<List<String>> head, ParsedFile parsed,
            ImportCheck check) {
        List<Field> fields = new ArrayList<>();
        for (String name : FIELDS) {
            fields.add(new Field(name, column(m, name)));
        }
        int columns = head.stream().mapToInt(List::size).max().orElse(0);
        List<String> headers = new ArrayList<>();
        for (int i = 0; i < columns; i++) {
            headers.add(m.header() && !head.isEmpty() && i < head.get(0).size() ? head.get(0).get(i).strip() : "");
        }
        List<List<String>> rows = m.header() && !head.isEmpty() ? head.subList(1, head.size()) : head;
        List<Refused> refused = new ArrayList<>();
        int importable = 0;
        int refusedCount = 0;
        int read = 0;
        String balance = null;
        String balanceDate = null;
        boolean undeterminable = false;
        if (parsed != null) {
            read = parsed.rows();
            importable = parsed.transactions().size();
            refusedCount = parsed.refused().size();
            parsed.refused().stream().limit(REFUSED_SHOWN).forEach(r -> refused.add(refused(r)));
            if (parsed.balance() != null) {
                balance = Money.eur(parsed.balance().cents()).format();
                balanceDate = parsed.balance().date().toString();
            }
            undeterminable = parsed.balanceUndeterminable();
        }
        return new Preview(account, token, delimiterCode(m.delimiter()), encodingCode(m.encoding()), m.header(), m.dateFormat(),
                decimalCode(m.decimal()), fields, columns, headers, rows, parsed != null, read, importable, refusedCount, refused,
                balance, balanceDate, undeterminable, check.storedInRange(), check.allIncoming());
    }

    static Refused refused(RefusedRow row) {
        return new Refused(row.row(), "import.refusal." + row.reason().name().toLowerCase(Locale.ROOT));
    }

    static Report report(Account account, ImportReport report) {
        return new Report(account, report, report.refusedRows().stream().map(ImportViews::refused).toList(),
                "import.balance." + report.balance().name().toLowerCase(Locale.ROOT));
    }
}
