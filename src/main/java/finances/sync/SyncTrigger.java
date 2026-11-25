package finances.sync;

import java.util.Locale;

/** What started a sync run: the daily run, "Sync now", or the first sync of the link flow. */
public enum SyncTrigger {
    SCHEDULED,
    USER,
    LINK;

    public String code() {
        return name().toLowerCase(Locale.ROOT);
    }

    public static SyncTrigger parse(String code) {
        return valueOf(code.toUpperCase(Locale.ROOT));
    }

    /** A run the user started can carry the user's own headers; the daily one never does. */
    boolean startedByUser() {
        return this != SCHEDULED;
    }
}
