package finances.accounts;

import finances.money.Money;
import finances.money.Rhythm;
import java.time.LocalDate;

/**
 * A fixed cost that repeating booked spending suggests (issue #71), open until the owner adds it as a fixed cost or says it is not
 * one. {@code amount} is the newest charge's amount, {@code firstDue} that charge's booking date (its day is the usual day) and
 * {@code latestBooked} the same date, shown as the age of the proposal (hard rule 9). {@code counterpartyIban} is null for a
 * counterparty that has none; {@code counterpartyName} is null when the charges carry no name. {@code charges} counts the run.
 */
public record FixedCostProposal(long id, Scope scope, String name, String counterpartyName, String counterpartyIban, String nameKey,
        Money amount, Rhythm rhythm, LocalDate firstDue, LocalDate latestBooked, int charges) {
}
