package finances.importing;

import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.time.format.DateTimeFormatter;
import java.time.format.ResolverStyle;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * How to read one kind of CSV file: the file's own conventions (delimiter, encoding, header row, date
 * format, decimal separator) and which column holds what. Columns are counted from 0; {@code -1} means
 * "none". The amount is either one signed column or a debit and a credit column. Saved per account
 * and offered again at the next upload.
 */
public record CsvMapping(char delimiter, String encoding, boolean header, String dateFormat, char decimal,
        int date, int amount, int debit, int credit, int name, int iban, int description, int reference, int balance) {

    public static final String UTF_8 = "UTF-8";
    public static final String WINDOWS_1252 = "windows-1252";
    public static final List<Character> DELIMITERS = List.of(',', ';', '\t');
    public static final List<String> ENCODINGS = List.of(UTF_8, WINDOWS_1252);
    public static final List<Character> DECIMALS = List.of(',', '.');

    /**
     * The date formats that can be chosen, the most common first. {@code dd} before {@code MM} wins when a
     * file would read both ways, and the preview shows how the dates were read.
     */
    public static final List<String> DATE_FORMATS = List.of(
            "yyyy-MM-dd", "dd-MM-yyyy", "dd/MM/yyyy", "dd.MM.yyyy", "MM/dd/yyyy", "yyyy/MM/dd", "yyyyMMdd",
            "dd-MM-yy", "dd/MM/yy", "dd.MM.yy");

    private static final Map<String, DateTimeFormatter> FORMATTERS = new LinkedHashMap<>();

    static {
        for (String format : DATE_FORMATS) {
            // STRICT resolving needs "uuuu" for the year: 31-02-2026 is refused, not moved to March.
            FORMATTERS.put(format, DateTimeFormatter.ofPattern(format.replace("yyyy", "uuuu").replace("yy", "uu"), Locale.ROOT).withResolverStyle(ResolverStyle.STRICT));
        }
    }

    public CsvMapping {
        if (!DELIMITERS.contains(delimiter) || !ENCODINGS.contains(encoding) || !DATE_FORMATS.contains(dateFormat)
                || !DECIMALS.contains(decimal)) {
            throw new CsvImportException(CsvImportException.Reason.MAPPING_INVALID);
        }
        for (int column : new int[] {date, amount, debit, credit, name, iban, description, reference, balance}) {
            if (column < -1 || column > 999) {
                throw new CsvImportException(CsvImportException.Reason.MAPPING_INVALID);
            }
        }
        if (amount >= 0 && (debit >= 0 || credit >= 0)) {
            // One signed column or a debit and a credit column; both at once would be a guess which one counts.
            throw new CsvImportException(CsvImportException.Reason.MAPPING_INVALID);
        }
    }

    /** A booking date and an amount (one column, or both a debit and a credit column) are the least a row needs. */
    public boolean isComplete() {
        return date >= 0 && (amount >= 0 || (debit >= 0 && credit >= 0));
    }

    public Charset charset() {
        return encoding.equals(UTF_8) ? StandardCharsets.UTF_8 : Charset.forName("windows-1252");
    }

    public DateTimeFormatter dateFormatter() {
        return FORMATTERS.get(dateFormat);
    }

    static DateTimeFormatter formatter(String dateFormat) {
        return FORMATTERS.get(dateFormat);
    }

    /** The highest column index in use, or -1. */
    int highestColumn() {
        int highest = -1;
        for (int column : new int[] {date, amount, debit, credit, name, iban, description, reference, balance}) {
            highest = Math.max(highest, column);
        }
        return highest;
    }

    /** The same mapping with other columns (used by the guess). */
    public Builder toBuilder() {
        return new Builder(this);
    }

    /** Lines of {@code key=value}: what is kept per account. */
    public String toText() {
        return "delimiter=" + (delimiter == '\t' ? "tab" : String.valueOf(delimiter)) + "\n"
                + "encoding=" + encoding + "\n"
                + "header=" + header + "\n"
                + "dateFormat=" + dateFormat + "\n"
                + "decimal=" + decimal + "\n"
                + "date=" + date + "\namount=" + amount + "\ndebit=" + debit + "\ncredit=" + credit + "\n"
                + "name=" + name + "\niban=" + iban + "\ndescription=" + description + "\nreference=" + reference + "\n"
                + "balance=" + balance + "\n";
    }

    /** Reads what {@link #toText} wrote; anything unreadable is a refused mapping, never a guess. */
    public static CsvMapping fromText(String text) {
        Map<String, String> values = new LinkedHashMap<>();
        for (String line : text.split("\n")) {
            int equals = line.indexOf('=');
            if (equals > 0) {
                values.put(line.substring(0, equals), line.substring(equals + 1));
            }
        }
        try {
            String delimiter = values.getOrDefault("delimiter", "");
            String decimal = values.getOrDefault("decimal", "");
            if ((delimiter.length() != 1 && !delimiter.equals("tab")) || decimal.length() != 1) {
                throw new CsvImportException(CsvImportException.Reason.MAPPING_INVALID);
            }
            return new CsvMapping(delimiter.equals("tab") ? '\t' : delimiter.charAt(0), values.getOrDefault("encoding", ""),
                    Boolean.parseBoolean(values.get("header")), values.getOrDefault("dateFormat", ""), decimal.charAt(0),
                    number(values, "date"), number(values, "amount"), number(values, "debit"), number(values, "credit"),
                    number(values, "name"), number(values, "iban"), number(values, "description"), number(values, "reference"),
                    number(values, "balance"));
        } catch (NumberFormatException e) {
            throw new CsvImportException(CsvImportException.Reason.MAPPING_INVALID);
        }
    }

    private static int number(Map<String, String> values, String key) {
        return Integer.parseInt(values.getOrDefault(key, "-1").strip());
    }

    /** A mutable copy to change a few things of a mapping. */
    public static final class Builder {
        public char delimiter;
        public String encoding;
        public boolean header;
        public String dateFormat;
        public char decimal;
        public int date;
        public int amount;
        public int debit;
        public int credit;
        public int name;
        public int iban;
        public int description;
        public int reference;
        public int balance;

        Builder(CsvMapping m) {
            delimiter = m.delimiter;
            encoding = m.encoding;
            header = m.header;
            dateFormat = m.dateFormat;
            decimal = m.decimal;
            date = m.date;
            amount = m.amount;
            debit = m.debit;
            credit = m.credit;
            name = m.name;
            iban = m.iban;
            description = m.description;
            reference = m.reference;
            balance = m.balance;
        }

        public Builder amount(int column) {
            amount = column;
            return this;
        }

        public Builder reference(int column) {
            reference = column;
            return this;
        }

        public Builder description(int column) {
            description = column;
            return this;
        }

        public CsvMapping build() {
            return new CsvMapping(delimiter, encoding, header, dateFormat, decimal, date, amount, debit, credit, name, iban,
                    description, reference, balance);
        }
    }
}
