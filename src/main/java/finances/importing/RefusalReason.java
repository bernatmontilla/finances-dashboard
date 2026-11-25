package finances.importing;

/** Why one row of a file was not imported. Never carries the row's content: only its number and this reason. */
public enum RefusalReason {
    /** The row has fewer columns than the date and the amount need. */
    TOO_FEW_COLUMNS,
    DATE_INVALID,
    DATE_IN_FUTURE,
    AMOUNT_EMPTY,
    AMOUNT_NOT_A_NUMBER,
    /** One separator and three digits ("1,234"): thousands or decimals, so it is refused, not guessed. */
    AMOUNT_AMBIGUOUS,
    AMOUNT_TOO_MANY_DECIMALS,
    AMOUNT_TOO_LARGE,
    /** Debit and credit both hold a value, or neither does. */
    DEBIT_AND_CREDIT,
    /** A debit or credit column holds a sign: which way it points would be a guess. */
    SIGN_IN_DEBIT_CREDIT,
    BALANCE_INVALID
}
