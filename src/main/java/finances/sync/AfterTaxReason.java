package finances.sync;

import finances.outbound.AfterTaxException;

/**
 * Why a fetch of the after-tax source gave no new value: the fixed short codes that are stored and shown through
 * {@code aftertax.reason.<code>}. Never a text from the source (hard rule 11).
 */
public enum AfterTaxReason {
    UNREACHABLE("unreachable"),
    TIMEOUT("timeout"),
    STATUS("status"),
    TOO_LARGE("too_large"),
    NOT_JSON("not_json"),
    MISSING_FIELD("missing_field"),
    INVALID_FIELD("invalid_field"),
    NOT_AN_INTEGER("not_an_integer"),
    CURRENCY("currency"),
    VERSION("version"),
    AS_OF_IN_FUTURE("as_of_in_future"),
    TOKEN_UNREADABLE("token_unreadable"),
    STORE_FAILED("store_failed");

    private final String code;

    AfterTaxReason(String code) {
        this.code = code;
    }

    public String code() {
        return code;
    }

    static AfterTaxReason of(AfterTaxException.Reason reason) {
        return switch (reason) {
            case UNREACHABLE -> UNREACHABLE;
            case TIMEOUT -> TIMEOUT;
            case STATUS -> STATUS;
            case TOO_LARGE -> TOO_LARGE;
            case TOKEN_UNREADABLE -> TOKEN_UNREADABLE;
        };
    }
}
