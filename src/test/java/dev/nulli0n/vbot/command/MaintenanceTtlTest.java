package dev.nulli0n.vbot.command;

import org.junit.jupiter.api.Test;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class MaintenanceTtlTest {
    @Test
    void parsesSupportedUnitsCaseInsensitively() {
        assertThat(MaintenanceTtl.parse("30s")).isEqualTo(Duration.ofSeconds(30));
        assertThat(MaintenanceTtl.parse("15M")).isEqualTo(Duration.ofMinutes(15));
        assertThat(MaintenanceTtl.parse("2h")).isEqualTo(Duration.ofHours(2));
        assertThat(MaintenanceTtl.parse("7d")).isEqualTo(Duration.ofDays(7));
    }

    @Test
    void rejectsZeroMissingUnitsAndExcessiveValues() {
        assertThatThrownBy(() -> MaintenanceTtl.parse("0s")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> MaintenanceTtl.parse("60")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> MaintenanceTtl.parse("31d")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> MaintenanceTtl.parse("999999999999999999999d"))
            .isInstanceOf(IllegalArgumentException.class);
    }
}
