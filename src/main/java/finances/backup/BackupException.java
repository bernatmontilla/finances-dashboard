package finances.backup;

/**
 * A backup or a restore that did not happen, and why. A refused restore has changed nothing. The
 * message is English for the command line and safe to print: it never holds a path's content, an
 * amount or a name from the data. The web pages show {@link Reason#messageKey()} instead.
 */
public class BackupException extends RuntimeException {

    public enum Reason {
        /** There is no database yet, so there is nothing to back up. */
        NO_DATABASE,
        /** The file is not a backup made by this application. */
        NOT_A_BACKUP,
        /** The file is a backup but cut off or damaged. */
        DAMAGED,
        /** Made by a newer version of the file format than this application knows. */
        NEWER_FORMAT,
        /** Made from a newer database than this application knows. */
        NEWER_SCHEMA,
        /** The database is in use and did not become free in time. */
        BUSY,
        /** The disk or the file system refused (full, read-only, no permission). */
        FAILED,
        /** The restored database could not be brought up to date; the old one is back. */
        MIGRATION_FAILED,
        /** The word was not typed. */
        NOT_CONFIRMED,
        /** The upload to restore is no longer there. */
        UNKNOWN_UPLOAD,
        /** There is no earlier database to go back to. */
        NOTHING_TO_UNDO,
        /** The target file of a command-line backup is already there. */
        TARGET_EXISTS;

        public String messageKey() {
            return "backup.error." + name().toLowerCase(java.util.Locale.ROOT);
        }
    }

    private final Reason reason;

    public BackupException(Reason reason, String message) {
        super(message);
        this.reason = reason;
    }

    public BackupException(Reason reason, String message, Throwable cause) {
        super(message, cause);
        this.reason = reason;
    }

    public Reason reason() {
        return reason;
    }
}
