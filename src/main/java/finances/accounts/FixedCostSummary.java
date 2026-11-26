package finances.accounts;

import finances.money.Money;
import java.util.List;
import java.util.Optional;

/**
 * The fixed costs of one scope in short (issue #21): what they come to per month and per year (running ones only; a longer rhythm
 * is spread evenly, the month rounded up), and which are still to come this month, the earliest expected date first, with their
 * total. Nothing here is subtracted from a headline number. {@code basis} is present when at least one of them can be matched
 * against transactions (issue #63): then "still to come" is only as current as those transactions.
 */
public record FixedCostSummary(Scope scope, int running, Money perMonth, Money perYear, List<FixedCostMonth> stillToCome,
        Money stillToComeTotal, Optional<FixedCostBasis> basis) {

    public FixedCostSummary {
        stillToCome = List.copyOf(stillToCome);
    }

    public boolean hasStillToCome() {
        return !stillToCome.isEmpty();
    }
}
