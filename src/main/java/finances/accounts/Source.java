package finances.accounts;

import java.util.Locale;

/** Where an account's data comes from (PRODUCT: Words). {@code MANUAL} and {@code CSV} can be chosen; {@code BANK} comes with the bank sync. */
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

    /** What a form says: an unknown code is refused as a reason, not as a stack trace. */
    public static Source choose(String code) {
        for (Source source : values()) {
            if (source.code().equals(code)) {
                return source;
            }
        }
        throw new AccountException(AccountException.Reason.INVALID_SOURCE);
    }
}
