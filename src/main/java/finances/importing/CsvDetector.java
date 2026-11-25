package finances.importing;

import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;

/**
 * Proposes a {@link CsvMapping} for a file the owner uploads: encoding, delimiter, header row, date
 * format, decimal separator and the columns. It only proposes: the preview shows what it would
 * import and every setting can be corrected. A column it cannot place stays "none".
 */
public final class CsvDetector {

    private static final int SAMPLE_CHARS = 32 * 1024;
    private static final int SAMPLE_RECORDS = 60;
    private static final Pattern NUMBER = Pattern.compile("[-+]?[0-9][0-9.,  ]*");
    private static final Pattern DECIMAL_COMMA = Pattern.compile(".*,[0-9]{1,2}");
    private static final Pattern DECIMAL_POINT = Pattern.compile(".*\\.[0-9]{1,2}");

    private CsvDetector() {
    }

    public static CsvMapping detect(byte[] bytes) {
        String encoding = CsvFile.detectEncoding(bytes);
        String text = CsvFile.decode(bytes, encoding);
        String sample = text.length() > SAMPLE_CHARS ? text.substring(0, SAMPLE_CHARS) : text;
        boolean cut = sample.length() < text.length();
        char delimiter = delimiter(sample);
        List<List<String>> all = new ArrayList<>();
        CsvParser.records(sample, delimiter, record -> {
            if (!CsvParser.isBlank(record)) {
                all.add(record);
            }
        });
        if (cut && !all.isEmpty()) {
            all.remove(all.size() - 1); // the last one may be cut in half
        }
        List<List<String>> records = all.size() > SAMPLE_RECORDS ? all.subList(0, SAMPLE_RECORDS) : all;

        CsvMapping.Builder b = new CsvMapping(',', encoding, false, "yyyy-MM-dd", '.', -1, -1, -1, -1, -1, -1, -1, -1, -1).toBuilder();
        b.delimiter = delimiter;
        b.header = hasHeader(records);
        List<List<String>> data = b.header ? records.subList(Math.min(1, records.size()), records.size()) : records;
        List<String> headers = b.header && !records.isEmpty() ? records.get(0) : List.of();

        int columns = records.stream().mapToInt(List::size).max().orElse(0);
        dateColumn(b, data, headers, columns);
        if (b.date < 0) {
            // No cell reads as a date (every row may be broken): go by the header, so the report can name the rows.
            for (int column = 0; column < headers.size() && b.date < 0; column++) {
                String h = headers.get(column).toLowerCase(Locale.ROOT);
                if (h.contains("date") || h.contains("datum")) {
                    b.date = column;
                }
            }
        }
        namedColumns(b, headers);
        numericColumns(b, data, headers, columns);
        b.decimal = decimal(b, data);
        return b.build();
    }

    // ----- delimiter

    private static char delimiter(String sample) {
        char best = ',';
        int bestRows = 0;
        int bestColumns = 0;
        for (char candidate : CsvMapping.DELIMITERS) {
            java.util.Map<Integer, Integer> counts = new java.util.HashMap<>();
            CsvParser.records(sample, candidate, record -> {
                if (!CsvParser.isBlank(record)) {
                    counts.merge(record.size(), 1, Integer::sum);
                }
            });
            for (var entry : counts.entrySet()) {
                int columns = entry.getKey();
                int rows = entry.getValue();
                if (columns > 1 && (rows > bestRows || (rows == bestRows && columns > bestColumns))) {
                    best = candidate;
                    bestRows = rows;
                    bestColumns = columns;
                }
            }
        }
        return best;
    }

    // ----- header and dates

    /** A first row without any date, above a row that has one, is a header. */
    private static boolean hasHeader(List<List<String>> records) {
        if (records.isEmpty()) {
            return false;
        }
        boolean firstHasDate = records.get(0).stream().anyMatch(CsvDetector::looksLikeDate);
        if (firstHasDate) {
            return false;
        }
        boolean named = records.get(0).stream().map(c -> c.toLowerCase(Locale.ROOT))
                .anyMatch(c -> c.contains("date") || c.contains("datum"));
        return named || records.size() == 1 || records.get(1).stream().anyMatch(CsvDetector::looksLikeDate);
    }

    private static boolean looksLikeDate(String cell) {
        return CsvMapping.DATE_FORMATS.stream().anyMatch(format -> parses(cell, format));
    }

    private static boolean parses(String cell, String format) {
        try {
            LocalDate.parse(cell.strip(), CsvMapping.formatter(format));
            return true;
        } catch (DateTimeParseException e) {
            return false;
        }
    }

    /** The column and format that read the most rows as dates; a column named like a booking date wins a tie. */
    private static void dateColumn(CsvMapping.Builder b, List<List<String>> data, List<String> headers, int columns) {
        int bestCount = 0;
        for (int column = 0; column < columns; column++) {
            for (String format : CsvMapping.DATE_FORMATS) {
                int count = 0;
                for (List<String> row : data) {
                    if (column < row.size() && parses(row.get(column), format)) {
                        count++;
                    }
                }
                boolean booking = column < headers.size() && headers.get(column).toLowerCase(Locale.ROOT).contains("book");
                if (count > bestCount || (count == bestCount && count > 0 && booking && b.date != column)) {
                    bestCount = count;
                    b.date = column;
                    b.dateFormat = format;
                }
            }
        }
    }

    // ----- the other columns

    private static void namedColumns(CsvMapping.Builder b, List<String> headers) {
        for (int column = 0; column < headers.size(); column++) {
            String h = headers.get(column).toLowerCase(Locale.ROOT).strip();
            if (h.contains("debit") || h.equals("out") || h.contains("withdraw")) {
                b.debit = column;
            } else if (h.contains("credit") || h.equals("in") || h.contains("deposit")) {
                b.credit = column;
            } else if (h.contains("balance") || h.contains("saldo")) {
                b.balance = column;
            } else if (h.contains("amount") || h.contains("bedrag")) {
                b.amount = column;
            } else if (h.contains("iban")) {
                b.iban = column;
            } else if (h.contains("name") || h.contains("counterparty") || h.contains("payee") || h.contains("naam")) {
                b.name = column;
            } else if (h.contains("descr") || h.contains("memo") || h.contains("details") || h.contains("omschrijving")) {
                b.description = column;
            }
            // No reference column is proposed: a "reference" of a bank export is often a mandate or a placeholder
            // that repeats, and a repeated reference means one transaction. The owner chooses it knowingly.
        }
        if (b.amount >= 0 && (b.debit >= 0 || b.credit >= 0)) {
            // Both kinds of column named: a debit/credit pair is the more specific of the two.
            b.amount = -1;
        }
        if (b.debit >= 0 != b.credit >= 0) {
            b.debit = -1;
            b.credit = -1;
        }
    }

    /** With no header to go by, the first column that is all numbers (and not the date) is the amount. */
    private static void numericColumns(CsvMapping.Builder b, List<List<String>> data, List<String> headers, int columns) {
        if (b.amount >= 0 || b.debit >= 0) {
            return;
        }
        for (int column = 0; column < columns; column++) {
            if (column == b.date || column == b.balance) {
                continue;
            }
            boolean any = false;
            boolean all = true;
            for (List<String> row : data) {
                String cell = column < row.size() ? row.get(column).strip() : "";
                if (cell.isEmpty()) {
                    continue;
                }
                any = true;
                if (!NUMBER.matcher(cell).matches()) {
                    all = false;
                    break;
                }
            }
            if (any && all) {
                b.amount = column;
                return;
            }
        }
    }

    private static char decimal(CsvMapping.Builder b, List<List<String>> data) {
        int comma = 0;
        int point = 0;
        for (int column : new int[] {b.amount, b.debit, b.credit, b.balance}) {
            if (column < 0) {
                continue;
            }
            for (List<String> row : data) {
                String cell = column < row.size() ? row.get(column).strip() : "";
                if (DECIMAL_COMMA.matcher(cell).matches()) {
                    comma++;
                }
                if (DECIMAL_POINT.matcher(cell).matches()) {
                    point++;
                }
            }
        }
        return comma > point ? ',' : '.';
    }
}
