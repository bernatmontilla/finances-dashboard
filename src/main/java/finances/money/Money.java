package finances.money;

import java.util.Collection;
import java.util.regex.Pattern;

/**
 * An amount: whole cents as a {@code long} plus a currency. Euro only (AGENTS.md, hard rule 8);
 * no {@code double}, {@code float} or decimal arithmetic anywhere in here.
 */
public record Money(long cents, String currency) {

    public static final String EUR = "EUR";

    public Money {
        if (!EUR.equals(currency)) {
            throw new UnsupportedCurrencyException();
        }
        if (cents == Long.MIN_VALUE) {
            // Math.abs has no answer for it, so formatting could not be right.
            throw new AmountFormatException(AmountFormatException.Reason.TOO_LARGE);
        }
    }

    public static Money eur(long cents) {
        return new Money(cents, EUR);
    }

    public static Money of(long cents, String currency) {
        return new Money(cents, currency);
    }

    public static Money zero() {
        return eur(0);
    }

    public static Money sum(Collection<Money> amounts) {
        long total = 0;
        for (Money amount : amounts) {
            total = Math.addExact(total, amount.cents);
        }
        return eur(total);
    }

    public Money plus(Money other) {
        return eur(Math.addExact(cents, other.cents));
    }

    public Money minus(Money other) {
        return eur(Math.subtractExact(cents, other.cents));
    }

    public Money negate() {
        return eur(-cents);
    }

    public boolean isNegative() {
        return cents < 0;
    }

    public boolean isZero() {
        return cents == 0;
    }

    /** {@code €1,234.56}, {@code -€12.34}. */
    public String format() {
        long absolute = Math.abs(cents);
        String units = Long.toString(absolute / 100);
        StringBuilder grouped = new StringBuilder();
        for (int i = 0; i < units.length(); i++) {
            if (i > 0 && (units.length() - i) % 3 == 0) {
                grouped.append(',');
            }
            grouped.append(units.charAt(i));
        }
        long fraction = absolute % 100;
        return (cents < 0 ? "-" : "") + "€" + grouped + "." + (fraction < 10 ? "0" : "") + fraction;
    }

    /**
     * Reads a typed amount: {@code 1234,56}, {@code 1.234,56}, {@code 1234.56}, {@code 1,234.56}.
     * An optional sign in front. When both separators appear, the last one is the decimal
     * separator. One separator followed by three digits ({@code 1,234}) could be thousands or
     * decimals and is refused, never guessed (after a leading zero it cannot be thousands, so it
     * is simply too many decimals).
     */
    public static Money parse(String input) {
        if (input == null || input.isBlank()) {
            throw new AmountFormatException(AmountFormatException.Reason.EMPTY);
        }
        String text = input.strip();
        boolean negative = false;
        if (text.charAt(0) == '-' || text.charAt(0) == '+') {
            negative = text.charAt(0) == '-';
            text = text.substring(1);
        }
        if (text.isEmpty() || !isDigit(text.charAt(0)) || !isDigit(text.charAt(text.length() - 1))) {
            throw notANumber();
        }
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (!isDigit(c) && c != ',' && c != '.') {
                throw notANumber();
            }
        }

        int last = Math.max(text.lastIndexOf(','), text.lastIndexOf('.'));
        String whole = text;
        String fraction = "";
        if (last >= 0) {
            char separator = text.charAt(last);
            char other = separator == ',' ? '.' : ',';
            boolean repeated = text.indexOf(separator) != last;
            boolean otherPresent = text.indexOf(other) >= 0;
            String before = text.substring(0, last);
            String after = text.substring(last + 1);
            if (otherPresent) {
                // "1.234,56" / "1,234.56": the last separator is the decimal one.
                if (repeated || !validGrouping(before, other)) {
                    throw notANumber();
                }
                whole = before.replace(String.valueOf(other), "");
                fraction = after;
            } else if (repeated) {
                // "1.234.567": the same separator twice can only be grouping.
                if (!validGrouping(text, separator)) {
                    throw notANumber();
                }
                whole = text.replace(String.valueOf(separator), "");
            } else if (after.length() == 3 && before.length() <= 3 && !before.startsWith("0")) {
                throw new AmountFormatException(AmountFormatException.Reason.AMBIGUOUS);
            } else {
                whole = before;
                fraction = after;
            }
        }
        if (fraction.length() > 2) {
            throw new AmountFormatException(AmountFormatException.Reason.TOO_MANY_DECIMALS);
        }
        try {
            long units = 0;
            for (int i = 0; i < whole.length(); i++) {
                units = Math.addExact(Math.multiplyExact(units, 10L), whole.charAt(i) - '0');
            }
            long hundredths = fraction.isEmpty() ? 0 : (fraction.charAt(0) - '0') * 10L;
            if (fraction.length() == 2) {
                hundredths += fraction.charAt(1) - '0';
            }
            long cents = Math.addExact(Math.multiplyExact(units, 100L), hundredths);
            return eur(negative ? -cents : cents);
        } catch (ArithmeticException e) {
            throw new AmountFormatException(AmountFormatException.Reason.TOO_LARGE);
        }
    }

    private static boolean isDigit(char c) {
        return c >= '0' && c <= '9';
    }

    private static AmountFormatException notANumber() {
        return new AmountFormatException(AmountFormatException.Reason.NOT_A_NUMBER);
    }

    /** One to three digits, then groups of exactly three, split by {@code separator}. */
    private static boolean validGrouping(String text, char separator) {
        String[] groups = text.split(Pattern.quote(String.valueOf(separator)), -1);
        for (int i = 0; i < groups.length; i++) {
            String group = groups[i];
            boolean sizeOk = i == 0 ? group.length() >= 1 && group.length() <= 3 : group.length() == 3;
            if (!sizeOk) {
                return false;
            }
            for (int j = 0; j < group.length(); j++) {
                if (!isDigit(group.charAt(j))) {
                    return false;
                }
            }
        }
        return true;
    }
}
