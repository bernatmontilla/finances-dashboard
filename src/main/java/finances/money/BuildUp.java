package finances.money;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * The two headline numbers of {@code docs/product/PRODUCT.md}, and the only place where their definitions exist (issue #19):
 *
 * <ul>
 *   <li><b>Private free to spend</b> = the balances of the private current accounts minus the open private reservations;
 *   <li><b>Business available</b> = the after-tax available amount minus the open business reservations.
 * </ul>
 *
 * <p>Pure rules on plain values: no clock, no database, no web layer, and no knowledge of accounts or of the after-tax source,
 * only of the lines they hand over (that keeps this package free of {@code accounts} and {@code sync}). Which accounts count
 * and why a line is stale is decided by the caller. Reservations are given as a list: until they exist it is empty, and the
 * issue that adds them plugs into the same two methods.
 *
 * <p>The two results are never combined: nothing here, and nothing a result offers, adds them (invariant 1). A number that
 * cannot be known is {@linkplain Result#hidden() hidden with a reason}, never shown as zero. Sums are exact: an amount that
 * does not fit is a reason, not a wrapped-around figure. Nothing here logs.
 */
public final class BuildUp {

    private BuildUp() {
    }

    /**
     * One part of a number: a label, its amount (null when the account has no balance yet), when the figure is correct as of
     * (null when that is not known; used only to find the oldest part) and why it is stale (null when it is fresh).
     */
    public record Line(String label, Money amount, Instant asOf, Freshness.Stale stale) {
        public Line {
            Objects.requireNonNull(label, "label");
        }

        public boolean hasBalance() {
            return amount != null;
        }
    }

    /** An open reservation: the amount that is held back from the number (never negative). */
    public record Reservation(String label, Money amount) {
        public Reservation {
            Objects.requireNonNull(label, "label");
            if (amount.isNegative()) {
                throw new IllegalArgumentException("A reservation cannot be negative.");
            }
        }
    }

    /** Why a number is not shown. */
    public enum Hidden {
        /** There is no active private current account. */
        NO_PRIVATE_CURRENT_ACCOUNT,
        /** A counted account has no balance yet, so any sum would be a partial one shown as the whole. */
        NO_BALANCE_YET,
        /** No after-tax source is configured. */
        NO_SOURCE,
        /** A source is configured but no valid value has been received. */
        NO_VALUE_YET,
        /** A sum does not fit in the amounts this application handles. */
        TOO_LARGE
    }

    /**
     * One number: the counted lines, the open reservations, their sum, and the total; or no total and the reason. {@code stale}
     * lists the stale lines in build-up order and {@code oldest} is the counted line that is correct as of the earliest
     * moment (the age of the total is its age).
     */
    public record Result(Money total, Hidden hidden, List<String> hiddenLabels, List<Line> lines, List<Reservation> reservations,
            Money reserved, List<Line> stale, Line oldest) {

        public Result {
            hiddenLabels = List.copyOf(hiddenLabels);
            lines = List.copyOf(lines);
            reservations = List.copyOf(reservations);
            stale = List.copyOf(stale);
        }

        /** Whether there is a number to show. */
        public boolean shown() {
            return total != null;
        }

        /** Whether the number rests on a stale part. */
        public boolean isStale() {
            return !stale.isEmpty();
        }
    }

    /** Balances of the private current accounts minus the open private reservations. */
    public static Result privateFreeToSpend(List<Line> privateCurrent, List<Reservation> open) {
        if (privateCurrent.isEmpty()) {
            return hidden(Hidden.NO_PRIVATE_CURRENT_ACCOUNT, List.of());
        }
        List<String> without = privateCurrent.stream().filter(line -> !line.hasBalance()).map(Line::label).toList();
        if (!without.isEmpty()) {
            return hidden(Hidden.NO_BALANCE_YET, without);
        }
        return total(privateCurrent, open);
    }

    /** The after-tax available amount (null when there is none) minus the open business reservations. */
    public static Result businessAvailable(Line afterTax, List<Reservation> open) {
        if (afterTax == null || !afterTax.hasBalance()) {
            return hidden(Hidden.NO_VALUE_YET, List.of());
        }
        return total(List.of(afterTax), open);
    }

    /** The business number when no after-tax source is configured: the caller decides that before it asks for a total. */
    public static Result noSource() {
        return hidden(Hidden.NO_SOURCE, List.of());
    }

    private static Result total(List<Line> lines, List<Reservation> open) {
        try {
            long counted = 0;
            for (Line line : lines) {
                counted = Math.addExact(counted, line.amount().cents());
            }
            long held = 0;
            for (Reservation reservation : open) {
                held = Math.addExact(held, reservation.amount().cents());
            }
            Money total = Money.eur(Math.subtractExact(counted, held)); // refuses the one value it cannot show
            return new Result(total, null, List.of(), lines, open, Money.eur(held), staleParts(lines), oldest(lines));
        } catch (ArithmeticException | AmountFormatException e) {
            return hidden(Hidden.TOO_LARGE, List.of());
        }
    }

    private static List<Line> staleParts(List<Line> lines) {
        List<Line> stale = new ArrayList<>();
        for (Line line : lines) {
            if (line.stale() != null) {
                stale.add(line);
            }
        }
        return stale;
    }

    /** The line correct as of the earliest moment; the first one when none knows its moment or on a tie. */
    private static Line oldest(List<Line> lines) {
        Line oldest = lines.getFirst();
        for (Line line : lines) {
            if (line.asOf() != null && (oldest.asOf() == null || line.asOf().isBefore(oldest.asOf()))) {
                oldest = line;
            }
        }
        return oldest;
    }

    private static Result hidden(Hidden reason, List<String> labels) {
        return new Result(null, reason, labels, List.of(), List.of(), null, List.of(), null);
    }
}
