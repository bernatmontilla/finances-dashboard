package finances.accounts;

import finances.money.Money;
import java.time.Instant;
import java.time.LocalDate;

/** A balance the owner entered: the amount, the date it is from, and when it was typed in (UTC). */
public record BalanceEntry(long id, long accountId, Money amount, LocalDate date, Instant enteredAt) {
}
