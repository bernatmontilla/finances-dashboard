package finances.accounts;

import java.util.Locale;

/** Where an account's data comes from (PRODUCT: Words). Only {@code MANUAL} is built so far. */
public enum Source {
    MANUAL,
    CSV,
    BANK;

    public String code() {
        return name().toLowerCase(Locale.ROOT);
    }

    public static Source parse(String code) {
        return valueOf(code.toUpperCase(Locale.ROOT));
    }
}
