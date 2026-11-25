package finances.accounts;

import java.util.Locale;

/**
 * How the age of a balance a bank reported is known (hard rule 9): the bank's own timestamp, only a
 * reference date from the bank, or not at all, in which case the age is the moment of our fetch and
 * is shown as "fetched", never as "correct as of".
 */
public enum AsOfBasis {
    BANK_TIME,
    BANK_DATE,
    FETCHED;

    public String code() {
        return name().toLowerCase(Locale.ROOT);
    }

    public static AsOfBasis parse(String code) {
        return valueOf(code.toUpperCase(Locale.ROOT));
    }
}
