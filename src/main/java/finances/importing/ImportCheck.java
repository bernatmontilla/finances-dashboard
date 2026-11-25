package finances.importing;

/**
 * What the owner should know before importing a file, worked out from the parsed file and what the
 * account already holds (docs/decisions/37-csv-import-warnings.md).
 *
 * @param storedInRange how many transactions the account already holds inside the file's date range, when the mapping
 *        differs from the saved one in a field that feeds a transaction's identity; 0 otherwise. Above 0 the rows
 *        may be stored a second time, so the import needs an explicit confirmation.
 * @param allIncoming one amount column, no negative row and a current account: every row would be stored as money
 *        coming in. A warning only.
 */
public record ImportCheck(int storedInRange, boolean allIncoming) {

    public static final ImportCheck NONE = new ImportCheck(0, false);

    /** The import is refused until the owner confirms. */
    public boolean needsConfirmation() {
        return storedInRange > 0;
    }
}
