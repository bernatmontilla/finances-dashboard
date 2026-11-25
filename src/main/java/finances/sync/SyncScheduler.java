package finances.sync;

import jakarta.annotation.PreDestroy;
import java.time.Clock;
import java.time.Duration;
import java.time.LocalTime;
import java.time.ZonedDateTime;
import java.time.format.DateTimeParseException;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

/**
 * The daily sync, once a day at {@code finances.sync.time} (HH:mm in the owner's time zone, default 04:30).
 * It is a background run: no PSU headers. It does nothing when no Enable Banking application is configured.
 * A run missed because the application was not running is not made up: the next run fetches from the last
 * success, so nothing is lost.
 */
@Component
class SyncScheduler {

    private static final Logger LOG = LoggerFactory.getLogger(SyncScheduler.class);

    private final BankSyncService sync;
    private final Clock clock;
    private final boolean enabled;
    private final LocalTime time;
    private ScheduledExecutorService executor;

    SyncScheduler(BankSyncService sync, Clock clock, @Value("${finances.sync.enabled:true}") boolean enabled,
            @Value("${finances.sync.time:04:30}") String time) {
        this.sync = sync;
        this.clock = clock;
        this.enabled = enabled;
        try {
            this.time = LocalTime.parse(time.strip());
        } catch (DateTimeParseException e) {
            throw new IllegalStateException("finances.sync.time must be a time of day like 04:30.");
        }
    }

    @EventListener(ApplicationReadyEvent.class)
    synchronized void start() {
        if (!enabled || executor != null) {
            return;
        }
        executor = Executors.newSingleThreadScheduledExecutor(runnable -> {
            Thread thread = new Thread(runnable, "bank-sync");
            thread.setDaemon(true);
            return thread;
        });
        scheduleNext();
    }

    @PreDestroy
    synchronized void stop() {
        if (executor != null) {
            executor.shutdownNow();
        }
    }

    private synchronized void scheduleNext() {
        executor.schedule(this::runOnce, untilNext(clock, time).toMillis(), TimeUnit.MILLISECONDS);
    }

    private void runOnce() {
        try {
            sync.syncAll(SyncTrigger.SCHEDULED, Map.of());
        } catch (RuntimeException e) {
            LOG.warn("The daily bank sync failed ({}).", e.getClass().getSimpleName());
        } finally {
            synchronized (this) {
                if (!executor.isShutdown()) {
                    scheduleNext();
                }
            }
        }
    }

    /** How long until the next {@code time} of day: today's when it is still ahead, else tomorrow's. */
    static Duration untilNext(Clock clock, LocalTime time) {
        ZonedDateTime now = ZonedDateTime.now(clock);
        ZonedDateTime next = now.toLocalDate().atTime(time).atZone(clock.getZone());
        if (!next.isAfter(now)) {
            next = now.toLocalDate().plusDays(1).atTime(time).atZone(clock.getZone());
        }
        return Duration.between(now, next);
    }
}
