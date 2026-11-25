package finances.importing;

/** A CSV import that cannot start or cannot be read at all. The message names the reason only, never the file's content. */
public class CsvImportException extends RuntimeException {

    public enum Reason {
        /** A setting is not one of the allowed ones, or a column is used twice in a way that would be a guess. */
        MAPPING_INVALID,
        /** No booking date column, or no amount (one column, or debit and credit). */
        MAPPING_INCOMPLETE,
        /** The file is empty or nothing was uploaded. */
        NO_FILE,
        TOO_LARGE,
        /** CSV files can only be imported into an account whose source is a CSV file. */
        NOT_CSV_ACCOUNT,
        /** The uploaded file is no longer held (the app restarted, or it waited too long): upload it again. */
        UPLOAD_EXPIRED
    }

    private final Reason reason;

    public CsvImportException(Reason reason) {
        super("import refused: " + reason);
        this.reason = reason;
    }

    public Reason reason() {
        return reason;
    }
}
