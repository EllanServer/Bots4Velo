package dev.nulli0n.vbot.schedule;

import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;

class DailyScheduleTest {
    @Test
    void calculatesTheNextOccurrenceInTheConfiguredTimezone() {
        Duration delay = DailySchedule.delayUntilNext("03:30", "Asia/Singapore",
            Instant.parse("2026-07-28T18:45:00Z"));

        assertThat(delay).isEqualTo(Duration.ofMinutes(45));
    }

    @Test
    void movesAnAlreadyPassedTimeToTomorrow() {
        Duration delay = DailySchedule.delayUntilNext("03:30", "UTC",
            Instant.parse("2026-07-29T03:30:00Z"));

        assertThat(delay).isEqualTo(Duration.ofDays(1));
    }
}
