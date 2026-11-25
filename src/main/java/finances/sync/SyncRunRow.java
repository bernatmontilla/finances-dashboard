package finances.sync;

import java.time.Instant;

/** One account's part of one sync run, as the settings page lists it. {@code reason} is a short code, never a bank message. */
public record SyncRunRow(long id, long accountId, String accountName, Instant startedAt, SyncTrigger trigger, boolean headersSent,
        boolean ok, String reason, int added, int updated, int known, int refused, int pendingRemoved, int pendingKept) {
}
