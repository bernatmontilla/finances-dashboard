package finances.importing;

import finances.accounts.DeliveredTransaction;
import java.time.LocalDate;
import java.util.List;

/**
 * What reading a file with a mapping gave, before anything is stored: the transactions that can be
 * imported, the rows that cannot (with their reasons), the first records as they are in the file, and
 * the balance of its newest row when the mapping has a balance column.
 *
 * @param rows how many non-blank data rows the file holds (header and blank lines not counted)
 * @param head the first records of the file as read, the header row included
 * @param columns the most columns seen in the first records
 * @param balance the balance after the newest row, when there is one and it can be told which row that is
 * @param balanceUndeterminable the file has a balance column but its newest date holds rows with different balances in an order that cannot be told
 */
public record ParsedFile(int rows, List<DeliveredTransaction> transactions, List<RefusedRow> refused,
        List<List<String>> head, int columns, Balance balance, boolean balanceUndeterminable) {

    /** The balance after the newest row and the date of that row. */
    public record Balance(long cents, LocalDate date) {
    }
}
