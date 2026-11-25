package finances.importing;

/** What an import did with the balance-after column. */
public enum BalanceResult {
    /** The mapping has no balance column, or no row had a balance. */
    NONE,
    /** Which row is the newest cannot be told: rows were refused, or the newest day holds different balances in an order the file does not give. */
    NOT_DETERMINABLE,
    UPDATED,
    /** The same balance on the same date was already recorded. */
    UNCHANGED,
    /** A balance from the same or a later date is already in the history (typed, corrected or imported); the file's one is left out. */
    OLDER,
    /** The account's rules refuse it (a debt cannot hold a negative amount owed). */
    REFUSED
}
