package finances.money;

import java.util.Locale;

/** How often a fixed cost is charged (issue #21): every this many months. */
public enum Rhythm {
    MONTHLY(1),
    TWO_MONTHLY(2),
    QUARTERLY(3),
    HALF_YEARLY(6),
    YEARLY(12);

    private final int months;

    Rhythm(int months) {
        this.months = months;
    }

    /** The months between two charges. */
    public int months() {
        return months;
    }

    /** How many times it is charged in a year. */
    public int perYear() {
        return 12 / months;
    }

    public String code() {
        return name().toLowerCase(Locale.ROOT);
    }

    /** The rhythm with this stored code; an unknown code is an {@link IllegalArgumentException}. */
    public static Rhythm parse(String code) {
        for (Rhythm rhythm : values()) {
            if (rhythm.code().equals(code)) {
                return rhythm;
            }
        }
        throw new IllegalArgumentException("unknown rhythm");
    }
}
