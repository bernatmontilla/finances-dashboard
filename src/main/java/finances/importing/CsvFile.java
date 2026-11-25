package finances.importing;

import finances.accounts.DeliveredTransaction;
import finances.accounts.TransactionStatus;
import finances.money.AmountFormatException;
import finances.money.Money;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Turns the bytes of a bank's CSV export into transactions, row by row, with a {@link CsvMapping}.
 * A row that cannot be read is refused with a reason and the others still count; nothing is guessed
 * (an ambiguous amount, a date that does not exist, a debit and a credit in one row). Nothing here
 * is logged and no reason carries a value (hard rule 11).
 */
public final class CsvFile {

    static final int HEAD_RECORDS = 11;

    private CsvFile() {
    }

    // ----- bytes to text

    /** The text of the file in the given encoding; a UTF-8 byte order mark is dropped whichever encoding is chosen. */
    public static String decode(byte[] bytes, String encoding) {
        int start = hasBom(bytes) ? 3 : 0;
        var charset = encoding.equals(CsvMapping.UTF_8) ? StandardCharsets.UTF_8 : java.nio.charset.Charset.forName("windows-1252");
        return new String(bytes, start, bytes.length - start, charset);
    }

    /** UTF-8 when the bytes are valid UTF-8 (a byte order mark or not), otherwise Windows-1252. */
    static String detectEncoding(byte[] bytes) {
        int start = hasBom(bytes) ? 3 : 0;
        try {
            StandardCharsets.UTF_8.newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(bytes, start, bytes.length - start));
            return CsvMapping.UTF_8;
        } catch (CharacterCodingException e) {
            return CsvMapping.WINDOWS_1252;
        }
    }

    private static boolean hasBom(byte[] bytes) {
        return bytes.length >= 3 && (bytes[0] & 0xFF) == 0xEF && (bytes[1] & 0xFF) == 0xBB && (bytes[2] & 0xFF) == 0xBF;
    }

    // ----- reading a file

    /**
     * Reads the whole file with the mapping. {@code today} is the newest date a row may have: a booking
     * date in the future is refused.
     *
     * @throws CsvImportException {@code MAPPING_INCOMPLETE} when there is no date or no amount column
     */
    public static ParsedFile parse(byte[] bytes, CsvMapping mapping, LocalDate today) {
        if (!mapping.isComplete()) {
            throw new CsvImportException(CsvImportException.Reason.MAPPING_INCOMPLETE);
        }
        String text = decode(bytes, mapping.encoding());
        Reader reader = new Reader(mapping, today);
        CsvParser.records(text, mapping.delimiter(), reader::accept);
        return reader.result();
    }

    /** The first records of a file as they are, for the preview (a header row included), and the number of columns they show. */
    public static List<List<String>> head(String text, char delimiter) {
        List<List<String>> head = new ArrayList<>();
        try {
            CsvParser.records(text, delimiter, record -> {
                if (!CsvParser.isBlank(record)) {
                    head.add(record);
                }
                if (head.size() >= HEAD_RECORDS) {
                    throw new StopReading();
                }
            });
        } catch (StopReading done) {
            // enough
        }
        return head;
    }

    private static final class StopReading extends RuntimeException {
        StopReading() {
            super(null, null, false, false);
        }
    }

    private static final class Reader {

        private final CsvMapping mapping;
        private final LocalDate today;
        private final List<DeliveredTransaction> transactions = new ArrayList<>();
        private final List<RefusedRow> refused = new ArrayList<>();
        private final List<List<String>> head = new ArrayList<>();
        private int record;
        private boolean headerSeen;
        private final Set<String> references = new HashSet<>();
        private int rows;
        private int columns;
        private LocalDate firstDate;
        private LocalDate lastDate;
        private LocalDate newestDate;
        private final List<long[]> atNewest = new ArrayList<>(); // {record number, balance cents}

        Reader(CsvMapping mapping, LocalDate today) {
            this.mapping = mapping;
            this.today = today;
        }

        void accept(List<String> cells) {
            record++;
            if (head.size() < HEAD_RECORDS && !CsvParser.isBlank(cells)) {
                head.add(cells);
            }
            if (record <= 50) {
                columns = Math.max(columns, cells.size());
            }
            if (CsvParser.isBlank(cells)) {
                return;
            }
            if (mapping.header() && !headerSeen) {
                headerSeen = true; // the first line that is not blank, as the preview shows it
                return;
            }
            rows++;
            try {
                read(cells);
            } catch (RowRefused e) {
                refused.add(new RefusedRow(record, e.reason));
            }
        }

        private void read(List<String> cells) {
            String dateCell = required(cells, mapping.date());
            LocalDate date = date(dateCell);
            long cents;
            if (mapping.amount() >= 0) {
                cents = amount(required(cells, mapping.amount()));
            } else {
                String debit = required(cells, mapping.debit());
                String credit = required(cells, mapping.credit());
                cents = debitCredit(debit, credit);
            }
            Long balance = null;
            String balanceCell = optional(cells, mapping.balance());
            if (!balanceCell.isEmpty()) {
                try {
                    balance = Amounts.parse(balanceCell, mapping.decimal()).cents();
                } catch (AmountFormatException e) {
                    throw new RowRefused(RefusalReason.BALANCE_INVALID);
                }
            }

            String reference = optional(cells, mapping.reference());
            if (!reference.isEmpty() && !references.add(reference)) {
                // The store identifies a transaction by its reference: a repeat would overwrite the first one.
                throw new RowRefused(RefusalReason.DUPLICATE_REFERENCE);
            }

            transactions.add(new DeliveredTransaction(date, null, cents, Money.EUR, optional(cells, mapping.name()),
                    optional(cells, mapping.iban()), optional(cells, mapping.description()), TransactionStatus.BOOKED,
                    reference, String.join("\t", cells)));

            if (firstDate == null) {
                firstDate = date;
            }
            lastDate = date;
            if (balance != null) {
                if (newestDate == null || date.isAfter(newestDate)) {
                    newestDate = date;
                    atNewest.clear();
                }
                if (date.equals(newestDate)) {
                    atNewest.add(new long[] {record, balance});
                }
            }
        }

        private String required(List<String> cells, int column) {
            if (column >= cells.size()) {
                throw new RowRefused(RefusalReason.TOO_FEW_COLUMNS);
            }
            return cells.get(column).strip();
        }

        private String optional(List<String> cells, int column) {
            return column < 0 || column >= cells.size() ? "" : cells.get(column).strip();
        }

        private LocalDate date(String cell) {
            try {
                LocalDate date = LocalDate.parse(cell, mapping.dateFormatter());
                if (date.isAfter(today)) {
                    throw new RowRefused(RefusalReason.DATE_IN_FUTURE);
                }
                return date;
            } catch (DateTimeParseException e) {
                throw new RowRefused(RefusalReason.DATE_INVALID);
            }
        }

        private long amount(String cell) {
            try {
                return Amounts.parse(cell, mapping.decimal()).cents();
            } catch (AmountFormatException e) {
                throw new RowRefused(refusal(e.reason()));
            }
        }

        /** Debit and credit are unsigned magnitudes: money out is negative, money in positive; exactly one of them is filled. */
        private long debitCredit(String debit, String credit) {
            if (debit.isEmpty() == credit.isEmpty()) {
                throw new RowRefused(RefusalReason.DEBIT_AND_CREDIT);
            }
            String cell = debit.isEmpty() ? credit : debit;
            if (cell.startsWith("-") || cell.startsWith("+")) {
                throw new RowRefused(RefusalReason.SIGN_IN_DEBIT_CREDIT);
            }
            long cents = amount(cell);
            return debit.isEmpty() ? cents : -cents;
        }

        ParsedFile result() {
            ParsedFile.Balance balance = null;
            boolean undeterminable = false;
            if (!atNewest.isEmpty() && !refused.isEmpty()) {
                // A row that could not be read may be the newest one: which balance is last cannot be told.
                undeterminable = true;
            } else if (!atNewest.isEmpty()) {
                Set<Long> distinct = new HashSet<>();
                for (long[] row : atNewest) {
                    distinct.add(row[1]);
                }
                if (distinct.size() == 1) {
                    balance = new ParsedFile.Balance(atNewest.get(0)[1], newestDate);
                } else if (firstDate.isAfter(lastDate)) {
                    // Newest first: the newest row of the newest day is the first one in the file.
                    balance = new ParsedFile.Balance(atNewest.get(0)[1], newestDate);
                } else if (firstDate.isBefore(lastDate)) {
                    balance = new ParsedFile.Balance(atNewest.get(atNewest.size() - 1)[1], newestDate);
                } else {
                    undeterminable = true;
                }
            }
            return new ParsedFile(rows, List.copyOf(transactions), List.copyOf(refused), List.copyOf(head), columns, balance,
                    undeterminable);
        }
    }

    private static RefusalReason refusal(AmountFormatException.Reason reason) {
        return switch (reason) {
            case EMPTY -> RefusalReason.AMOUNT_EMPTY;
            case NOT_A_NUMBER -> RefusalReason.AMOUNT_NOT_A_NUMBER;
            case AMBIGUOUS -> RefusalReason.AMOUNT_AMBIGUOUS;
            case TOO_MANY_DECIMALS -> RefusalReason.AMOUNT_TOO_MANY_DECIMALS;
            case TOO_LARGE -> RefusalReason.AMOUNT_TOO_LARGE;
        };
    }

    private static final class RowRefused extends RuntimeException {
        private final RefusalReason reason;

        RowRefused(RefusalReason reason) {
            super(null, null, false, false);
            this.reason = reason;
        }
    }
}
