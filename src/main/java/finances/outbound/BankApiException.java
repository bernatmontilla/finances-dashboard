package finances.outbound;

import java.util.regex.Pattern;

/**
 * A request to Enable Banking that did not give an answer to work with. The message names the kind and,
 * when the API sent one, its text error code (for example {@code ASPSP_RATE_LIMIT_EXCEEDED}): never a
 * token, session id, IBAN, name or amount (AGENTS.md hard rule 11).
 */
public class BankApiException extends RuntimeException {

    public enum Reason {
        /** The application id or the key file is missing or unusable, or the host setting is. */
        NOT_CONFIGURED,
        /** No connection could be made. */
        UNREACHABLE,
        /** The connection or the answer took too long. */
        TIMEOUT,
        /** The bank's limit of fetches was reached: wait (about six hours) and try again at the next run. */
        RATE_LIMITED,
        /** The session expired, was revoked or closed, or does not exist any more. */
        CONSENT_ENDED,
        /** The bank itself failed or timed out. */
        BANK_ERROR,
        /** The user has to do something at the bank first. */
        PSU_ACTION_REQUIRED,
        /** The account cannot be reached through this consent. */
        NOT_ACCESSIBLE,
        /** Enable Banking refused the request (a bad parameter, headers the bank wants, a redirect address it does not know). */
        REJECTED,
        /** The answer was not what the documentation describes. */
        UNEXPECTED
    }

    private static final Pattern CODE = Pattern.compile("[A-Z][A-Z0-9_]{0,59}");

    private final Reason reason;
    private final String apiError;
    private final int status;

    public BankApiException(Reason reason, String apiError, int status) {
        super("Enable Banking: " + reason + (status > 0 ? " (HTTP " + status + ")" : "")
                + (safe(apiError) == null ? "" : " " + safe(apiError)));
        this.reason = reason;
        this.apiError = safe(apiError);
        this.status = status;
    }

    /** The cause is not kept: a network error's message can quote the address it tried. */
    public BankApiException(Reason reason, Throwable ignoredCause) {
        super("Enable Banking: " + reason);
        this.reason = reason;
        this.apiError = null;
        this.status = 0;
    }

    public Reason reason() {
        return reason;
    }

    /** The API's text error code, or null; only ever a short upper-case token. */
    public String apiError() {
        return apiError;
    }

    public int status() {
        return status;
    }

    private static String safe(String code) {
        return code != null && CODE.matcher(code).matches() ? code : null;
    }
}
