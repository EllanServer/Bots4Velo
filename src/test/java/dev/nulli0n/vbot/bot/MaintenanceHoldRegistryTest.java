package dev.nulli0n.vbot.bot;

import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class MaintenanceHoldRegistryTest {
    @Test
    void permanentHoldNormalizesLookupAndSuppliesADefaultReason() {
        MutableClock clock = new MutableClock(Instant.parse("2026-07-30T00:00:00Z"));
        MaintenanceHoldRegistry registry = new MaintenanceHoldRegistry(clock);

        MaintenanceHoldSnapshot created = registry.hold("Farm01", "  ");

        assertThat(created.botId()).isEqualTo("Farm01");
        assertThat(created.reason()).isEqualTo("maintenance");
        assertThat(created.createdAt()).isEqualTo(clock.instant());
        assertThat(created.expiresAt()).isEmpty();
        assertThat(registry.snapshot(" farm01 ")).contains(created);
        assertThat(registry.isHeld("FARM01")).isTrue();
    }

    @Test
    void ttlExpiresLazilyAtTheExactBoundary() {
        MutableClock clock = new MutableClock(Instant.parse("2026-07-30T01:00:00Z"));
        MaintenanceHoldRegistry registry = new MaintenanceHoldRegistry(clock);
        MaintenanceHoldSnapshot created = registry.hold("lobby", "rolling restart", Duration.ofMinutes(5));

        clock.advance(Duration.ofMinutes(5).minusNanos(1));
        assertThat(registry.snapshot("lobby")).contains(created);

        clock.advance(Duration.ofNanos(1));
        assertThat(registry.snapshot("lobby")).isEmpty();
        assertThat(registry.snapshots()).isEmpty();
        assertThat(registry.resume("lobby")).isFalse();
    }

    @Test
    void aNewHoldAtomicallyReplacesThePreviousSnapshot() {
        MutableClock clock = new MutableClock(Instant.parse("2026-07-30T02:00:00Z"));
        MaintenanceHoldRegistry registry = new MaintenanceHoldRegistry(clock);
        registry.hold("backup", "first");
        clock.advance(Duration.ofSeconds(1));

        MaintenanceHoldSnapshot replacement = registry.hold(
            "BACKUP", "  deploy  ", Duration.ofSeconds(30));

        assertThat(registry.snapshots()).containsExactly(replacement);
        assertThat(replacement.reason()).isEqualTo("deploy");
        assertThat(replacement.createdAt()).isEqualTo(clock.instant());
        assertThat(replacement.expiresAt()).contains(clock.instant().plusSeconds(30));
        assertThat(registry.resume("backup")).isTrue();
        assertThat(registry.isHeld("backup")).isFalse();
    }

    @Test
    void rejectsNonPositiveTtlWithoutCreatingAHold() {
        MaintenanceHoldRegistry registry = new MaintenanceHoldRegistry(
            new MutableClock(Instant.parse("2026-07-30T03:00:00Z")));

        assertThatThrownBy(() -> registry.hold("farm", "bad", Duration.ZERO))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("positive");
        assertThatThrownBy(() -> registry.hold("farm", "bad", Duration.ofSeconds(-1)))
            .isInstanceOf(IllegalArgumentException.class);
        assertThat(registry.snapshots()).isEmpty();
    }

    @Test
    void restorePreservesAuditTimesExpiryAndServerExactly() {
        MutableClock clock = new MutableClock(Instant.parse("2026-07-30T04:00:00Z"));
        MaintenanceHoldRegistry registry = new MaintenanceHoldRegistry(clock);
        MaintenanceHoldSnapshot original = new MaintenanceHoldSnapshot(
            "Lobby01", "proxy upgrade", Instant.parse("2026-07-30T03:00:00Z"),
            java.util.Optional.of(Instant.parse("2026-07-30T05:00:00Z")), "lobby");

        assertThat(registry.restore(original)).isTrue();
        assertThat(registry.snapshot("lobby01")).contains(original);
        assertThat(registry.snapshot("lobby01").orElseThrow().server()).isEqualTo("lobby");

        clock.advance(Duration.ofHours(1));
        assertThat(registry.snapshot("lobby01")).isEmpty();
        assertThat(registry.restore(original)).isFalse();
    }

    static final class MutableClock extends Clock {
        private Instant instant;

        MutableClock(Instant instant) {
            this.instant = instant;
        }

        void advance(Duration duration) {
            instant = instant.plus(duration);
        }

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return instant;
        }
    }
}
