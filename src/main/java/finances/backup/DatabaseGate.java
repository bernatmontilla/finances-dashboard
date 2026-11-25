package finances.backup;

import java.time.Duration;
import java.util.concurrent.Callable;
import java.util.concurrent.Semaphore;

/**
 * Lets a restore have the database to itself. Every open connection of the application holds one
 * permit (see {@link GatedDataSource}); a restore takes all of them, so no statement runs while
 * the file is swapped, and a new connection waits until it is done. Not tied to a thread, because a
 * connection may be closed by another thread than the one that opened it.
 */
public final class DatabaseGate {

    private static final int PERMITS = 1_000_000;

    private final Semaphore permits = new Semaphore(PERMITS, true);

    void enter() {
        permits.acquireUninterruptibly();
    }

    void leave() {
        permits.release();
    }

    /** Runs {@code work} with no other connection open; {@code BUSY} when they do not close in time. */
    <T> T exclusive(Duration wait, Callable<T> work) throws Exception {
        boolean taken;
        try {
            taken = permits.tryAcquire(PERMITS, wait.toMillis(), java.util.concurrent.TimeUnit.MILLISECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new BackupException(BackupException.Reason.BUSY, "Interrupted while waiting for the database.");
        }
        if (!taken) {
            throw new BackupException(BackupException.Reason.BUSY,
                    "The database is in use right now (a sync or an import may be running). Nothing was changed; try again in a minute.");
        }
        try {
            return work.call();
        } finally {
            permits.release(PERMITS);
        }
    }
}
