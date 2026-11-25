package finances.accounts;

import java.util.Optional;

/** An account with its latest balance: the one with the newest date (the last typed wins a tie). */
public record AccountSummary(Account account, Optional<BalanceEntry> latest) {
}
