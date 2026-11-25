package finances.sync;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalTime;
import java.time.ZoneId;
import org.junit.jupiter.api.Test;

class SyncSchedulerTest {

    private static Clock at(String instant, String zone) {
        return Clock.fixed(Instant.parse(instant), ZoneId.of(zone));
    }

    @Test
    void theNextRunIsTodayWhenTheTimeIsStillAheadElseTomorrowInTheOwnersTimeZone() {
        LocalTime time = LocalTime.of(4, 30);

        assertThat(SyncScheduler.untilNext(at("2026-11-26T02:00:00Z", "UTC"), time)).isEqualTo(Duration.ofMinutes(150));
        assertThat(SyncScheduler.untilNext(at("2026-11-26T04:30:00Z", "UTC"), time)).as("exactly now is the next day").isEqualTo(Duration.ofHours(24));
        assertThat(SyncScheduler.untilNext(at("2026-11-26T10:30:00Z", "UTC"), time)).isEqualTo(Duration.ofHours(18));
        // 04:30 in Amsterdam in November is 03:30 UTC.
        assertThat(SyncScheduler.untilNext(at("2026-11-26T02:30:00Z", "Europe/Amsterdam"), time)).isEqualTo(Duration.ofHours(1));
    }
}
