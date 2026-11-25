package finances.money;

/**
 * A typed amount that cannot be read. The message names the reason only: what was typed is an
 * amount and stays out of error messages and logs (AGENTS.md, hard rule 11).
 */
public class AmountFormatException extends IllegalArgumentException {

    public enum Reason {
        EMPTY,
        NOT_A_NUMBER,
        /** One separator followed by three digits ("1,234"): thousands or decimals, so it is refused. */
        AMBIGUOUS,
        TOO_MANY_DECIMALS,
        TOO_LARGE
    }

    private final Reason reason;

    public AmountFormatException(Reason reason) {
        super("amount refused: " + reason);
        this.reason = reason;
    }

    public Reason reason() {
        return reason;
    }
}
