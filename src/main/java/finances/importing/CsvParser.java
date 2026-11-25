package finances.importing;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

/**
 * Reads CSV text one record at a time (RFC 4180 as banks write it): fields in double quotes may hold
 * the delimiter, line breaks and doubled quotes; a record ends at a line break outside quotes
 * (CR, LF or CRLF). The records are handed on as they are read, so a large file is never held as a
 * list of lists.
 */
final class CsvParser {

    private CsvParser() {
    }

    /** Calls {@code sink} with every record in order, blank lines included (a blank line is one empty field). */
    static void records(String text, char delimiter, Consumer<List<String>> sink) {
        List<String> record = new ArrayList<>();
        StringBuilder field = new StringBuilder();
        boolean quoted = false;
        boolean wasQuoted = false;
        boolean any = false;
        int length = text.length();
        for (int i = 0; i < length; i++) {
            char c = text.charAt(i);
            if (quoted) {
                if (c == '"') {
                    if (i + 1 < length && text.charAt(i + 1) == '"') {
                        field.append('"');
                        i++;
                    } else {
                        quoted = false;
                    }
                } else {
                    field.append(c);
                }
                continue;
            }
            if (c == '"' && field.isEmpty() && !wasQuoted) {
                quoted = true;
                wasQuoted = true;
                any = true;
            } else if (c == delimiter) {
                record.add(field.toString());
                field.setLength(0);
                wasQuoted = false;
                any = true;
            } else if (c == '\n' || c == '\r') {
                if (c == '\r' && i + 1 < length && text.charAt(i + 1) == '\n') {
                    i++;
                }
                record.add(field.toString());
                sink.accept(record);
                record = new ArrayList<>();
                field.setLength(0);
                wasQuoted = false;
                any = false;
            } else {
                field.append(c);
                any = true;
            }
        }
        if (any || !field.isEmpty()) {
            record.add(field.toString());
            sink.accept(record);
        }
    }

    /** True for a record that holds nothing but white space. */
    static boolean isBlank(List<String> record) {
        for (String cell : record) {
            if (!cell.isBlank()) {
                return false;
            }
        }
        return true;
    }
}
