package finances.accounts;

/** A rule of the transactions store that refused something. The message names the reason only, never a value. */
public class TransactionException extends RuntimeException {

    public enum Reason {
        NOT_FOUND,
        ARCHIVED,
        INCOMPLETE,
        NOTE_TOO_LONG
    }

    private final Reason reason;

    public TransactionException(Reason reason) {
        super("refused: " + reason);
        this.reason = reason;
    }

    public Reason reason() {
        return reason;
    }
}
