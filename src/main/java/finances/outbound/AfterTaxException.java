package finances.outbound;

/**
 * Why a request to the after-tax source did not give an answer to read. It holds a short code and, for an
 * answer that was not 200, the status number: never a message, URL, token or body from the source (hard rule 11).
 */
public final class AfterTaxException extends RuntimeException {

    /** The ways a request fails; the code is what is stored and mapped to a message. */
    public enum Reason {
        UNREACHABLE("unreachable"),
        TIMEOUT("timeout"),
        STATUS("status"),
        TOO_LARGE("too_large"),
        TOKEN_UNREADABLE("token_unreadable");

        private final String code;

        Reason(String code) {
            this.code = code;
        }

        public String code() {
            return code;
        }
    }

    private final transient Reason reason;
    private final int status;

    AfterTaxException(Reason reason) {
        this(reason, 0);
    }

    AfterTaxException(Reason reason, int status) {
        super(reason.code(), null, false, false);
        this.reason = reason;
        this.status = status;
    }

    public Reason reason() {
        return reason;
    }

    /** The HTTP status for {@link Reason#STATUS}, else 0. */
    public int status() {
        return status;
    }
}
