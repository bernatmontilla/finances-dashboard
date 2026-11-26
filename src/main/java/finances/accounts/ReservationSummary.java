package finances.accounts;

import finances.money.Money;
import java.util.List;

/** The open reservations of one scope in short: how many, the total reserved so far, and the next ones that come due. */
public record ReservationSummary(Scope scope, int open, Money reserved, List<ReservationStanding> next) {

    public ReservationSummary {
        next = List.copyOf(next);
    }

    public boolean hasOpen() {
        return open > 0;
    }
}
