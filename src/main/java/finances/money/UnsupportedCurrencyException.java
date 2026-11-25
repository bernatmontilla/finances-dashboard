package finances.money;

/** Only the euro is supported; another currency is refused, never converted (PRODUCT: what it will not do). */
public class UnsupportedCurrencyException extends IllegalArgumentException {

    public UnsupportedCurrencyException() {
        super("only EUR is supported");
    }
}
