package finances.sync;

import finances.outbound.BankApiException;
import java.util.Locale;

/**
 * Something in the link flow that cannot go on, with a reason the page shows as a message key. The message
 * names the reason only, never a value from the bank (hard rule 11).
 */
public class LinkException extends RuntimeException {

    public enum Reason {
        /** The Enable Banking application (id and key) is not in the data folder. */
        NOT_CONFIGURED,
        /** The address the bank sends the browser back to is not configured. */
        NO_REDIRECT_URL,
        /** The bank chosen is not in the list the API offers (any more). */
        BANK_UNKNOWN,
        /** The callback came with a state that was never issued, was used already or is too old: nothing is stored. */
        UNKNOWN_STATE,
        /** The authorisation was refused, cancelled or came back with an error: nothing is stored. */
        DENIED,
        /** A request to Enable Banking failed; {@link #api()} says how. */
        BANK_API,
        NOT_FOUND,
        /** The account is in another currency than the euro. */
        UNSUPPORTED_CURRENCY,
        /** The account is already an account of the app. */
        ALREADY_LINKED,
        /** The consent has ended: renew it first. */
        CONSENT_ENDED,
        /** A sync is running: try again in a moment. */
        SYNC_BUSY,
        FAILED
    }

    private final Reason reason;
    private final BankApiException.Reason api;

    public LinkException(Reason reason) {
        super("refused: " + reason);
        this.reason = reason;
        this.api = null;
    }

    LinkException(BankApiException cause) {
        super("refused: " + Reason.BANK_API + " " + cause.reason());
        this.reason = Reason.BANK_API;
        this.api = cause.reason();
    }

    public Reason reason() {
        return reason;
    }

    public BankApiException.Reason api() {
        return api;
    }

    /** A short code for the address of the settings page: the reason, and for an API failure how it failed. */
    public String code() {
        return reason == Reason.BANK_API ? "api_" + api.name().toLowerCase(Locale.ROOT) : reason.name().toLowerCase(Locale.ROOT);
    }

    /** The key of the message the page shows. */
    public String messageKey() {
        return reason == Reason.BANK_API
                ? "bank.error.api." + api.name().toLowerCase(Locale.ROOT)
                : "bank.error." + reason.name().toLowerCase(Locale.ROOT);
    }
}
