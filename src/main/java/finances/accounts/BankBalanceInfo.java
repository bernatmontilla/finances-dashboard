package finances.accounts;

import finances.money.Money;
import java.time.Instant;
import java.time.LocalDate;

/**
 * A balance a bank reported: the amount, the day it is from, which balance type it is (for example CLBD,
 * the booked balance), and how its age is known. {@code asOf} is null for {@link AsOfBasis#BANK_DATE}.
 */
public record BankBalanceInfo(Money amount, LocalDate date, String type, Instant asOf, AsOfBasis basis) {

    /** CLBD and ITBD are the booked balances; any other type is shown for what it is, never as booked. */
    public boolean booked() {
        return "CLBD".equals(type) || "ITBD".equals(type);
    }
}
