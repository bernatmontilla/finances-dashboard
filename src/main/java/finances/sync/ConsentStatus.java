package finances.sync;

import java.util.Locale;

/** Expired and revoked consents look the same to the owner ("consent expired", Renew): the accounts stay. */
public enum ConsentStatus {
    AUTHORIZED,
    EXPIRED,
    REVOKED;

    public String code() {
        return name().toLowerCase(Locale.ROOT);
    }

    public static ConsentStatus parse(String code) {
        return valueOf(code.toUpperCase(Locale.ROOT));
    }
}
