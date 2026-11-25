package finances.importing;

import java.util.List;

/**
 * What an import did: the rows read, what the store did with them (added, updated, already known,
 * refused), the refused rows with their reasons (the first {@value #LISTED} are listed, the count is
 * complete), and what happened to the balance.
 */
public record ImportReport(int rows, int added, int updated, int known, int refused, List<RefusedRow> refusedRows,
        BalanceResult balance) {

    public static final int LISTED = 200;

    /** The file held rows but none could be imported: nothing was stored. */
    public boolean everyRowFailed() {
        return rows > 0 && refused == rows;
    }

    /** The file held no data rows at all (only a header, or nothing). */
    public boolean noRows() {
        return rows == 0;
    }

    public boolean hasRefusedRows() {
        return !refusedRows.isEmpty();
    }
}
