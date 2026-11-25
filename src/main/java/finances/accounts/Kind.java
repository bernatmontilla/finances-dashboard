package finances.accounts;

import java.util.Locale;

/** What an account is (PRODUCT: Words). A debt holds the amount owed. */
public enum Kind {
    CURRENT,
    SAVINGS,
    INVESTMENT,
    DEBT;

    public String code() {
        return name().toLowerCase(Locale.ROOT);
    }

    public static Kind parse(String code) {
        for (Kind kind : values()) {
            if (kind.code().equals(code)) {
                return kind;
            }
        }
        throw new AccountException(AccountException.Reason.INVALID_KIND);
    }
}
