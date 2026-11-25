package finances.accounts;

import java.util.Locale;

/** A transaction is {@code PENDING} until the bank books it; a booked one never goes back to pending. */
public enum TransactionStatus {
    PENDING,
    BOOKED;

    public String code() {
        return name().toLowerCase(Locale.ROOT);
    }

    public static TransactionStatus parse(String code) {
        return valueOf(code.toUpperCase(Locale.ROOT));
    }
}
