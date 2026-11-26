package finances.accounts;

import finances.money.Money;
import java.util.List;

/**
 * The fixed costs of one scope in short (issue #21): what they come to per month and per year (running ones only; a longer rhythm
 * is spread evenly, the month rounded up), and which are still to come this month, the earliest expected date first, with their
 * total. Nothing here is subtracted from a headline number.
 */
public record FixedCostSummary(Scope scope, int running, Money perMonth, Money perYear, List<FixedCostMonth> stillToCome,
        Money stillToComeTotal) {

    public FixedCostSummary {
        stillToCome = List.copyOf(stillToCome);
    }

    public boolean hasStillToCome() {
        return !stillToCome.isEmpty();
    }
}
