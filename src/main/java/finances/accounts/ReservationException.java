package finances.accounts;

/** A rule of the reservations that refused a change. The message names the reason only, never a value. */
public class ReservationException extends RuntimeException {

    public enum Reason {
        NAME_REQUIRED,
        NAME_TOO_LONG,
        INVALID_SCOPE,
        AMOUNT_NOT_POSITIVE,
        AMOUNT_TOO_LARGE,
        DUE_DATE_REQUIRED,
        DUE_DATE_INVALID,
        DUE_DATE_IN_PAST,
        DUE_DATE_TOO_FAR,
        RESERVED_OUT_OF_RANGE,
        NOT_FOUND,
        CLOSED,
        ALREADY_CLOSED,
        NOT_CLOSED,
        NEXT_ROUND_CHANGED
    }

    private final Reason reason;

    public ReservationException(Reason reason) {
        super("refused: " + reason);
        this.reason = reason;
    }

    public Reason reason() {
        return reason;
    }
}
