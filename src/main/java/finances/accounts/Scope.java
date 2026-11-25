package finances.accounts;

import java.util.Locale;

/** Whose money it is: every account has one (PRODUCT: Words). */
public enum Scope {
    PRIVATE,
    BUSINESS;

    public String code() {
        return name().toLowerCase(Locale.ROOT);
    }

    public static Scope parse(String code) {
        for (Scope scope : values()) {
            if (scope.code().equals(code)) {
                return scope;
            }
        }
        throw new AccountException(AccountException.Reason.INVALID_SCOPE);
    }
}
