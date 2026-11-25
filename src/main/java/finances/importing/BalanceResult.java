package finances.importing;

/** What an import did with the balance-after column. */
public enum BalanceResult {
    /** The mapping has no balance column, or no row had a balance. */
    NONE,
    /** The newest day holds rows with different balances and the file's order does not say which came last. */
    NOT_DETERMINABLE,
    UPDATED,
    /** The same balance on the same date was already recorded. */
    UNCHANGED,
    /** A balance from a later date was already recorded; the file's one is older and is left out. */
    OLDER,
    /** The account's rules refuse it (a debt cannot hold a negative amount owed). */
    REFUSED
}
