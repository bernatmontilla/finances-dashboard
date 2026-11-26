package finances.accounts;

/** A rule of the fixed costs that refused a change. The message names the reason only, never a value. */
public class FixedCostException extends RuntimeException {

    public enum Reason {
        NAME_REQUIRED,
        NAME_TOO_LONG,
        INVALID_SCOPE,
        INVALID_RHYTHM,
        AMOUNT_NOT_POSITIVE,
        AMOUNT_TOO_LARGE,
        FIRST_DUE_REQUIRED,
        FIRST_DUE_INVALID,
        FIRST_DUE_TOO_FAR,
        END_DATE_INVALID,
        END_BEFORE_FIRST_DUE,
        COUNTERPARTY_TOO_LONG,
        ACCOUNT_NOT_FOUND,
        ACCOUNT_ARCHIVED,
        ACCOUNT_OUT_OF_SCOPE,
        NOT_FOUND,
        ENDED,
        PAUSED,
        ALREADY_PAUSED,
        NOT_PAUSED_OR_ENDED,
        NO_CHARGE_THIS_MONTH,
        NOT_FLAGGED,
        NOT_SKIPPED
    }

    private final Reason reason;

    public FixedCostException(Reason reason) {
        super("refused: " + reason);
        this.reason = reason;
    }

    public Reason reason() {
        return reason;
    }
}
