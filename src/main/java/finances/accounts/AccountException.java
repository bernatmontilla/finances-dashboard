package finances.accounts;

/** A rule of the accounts domain that refused a change. The message names the reason only, never a value. */
public class AccountException extends RuntimeException {

    public enum Reason {
        NAME_REQUIRED,
        NAME_TOO_LONG,
        INVALID_SCOPE,
        INVALID_KIND,
        IBAN_INVALID,
        IBAN_DUPLICATE,
        NOT_FOUND,
        DATE_REQUIRED,
        DATE_INVALID,
        DATE_IN_FUTURE,
        NOT_MANUAL,
        ARCHIVED,
        DEBT_NEGATIVE,
        HAS_NEGATIVE_BALANCES
    }

    private final Reason reason;

    public AccountException(Reason reason) {
        super("refused: " + reason);
        this.reason = reason;
    }

    public Reason reason() {
        return reason;
    }
}
