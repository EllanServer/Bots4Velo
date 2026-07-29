package dev.nulli0n.vbot.bot;

import dev.nulli0n.vbot.config.BotPluginConfig;
import dev.nulli0n.vbot.protocol.ProtocolSelection;
import dev.nulli0n.vbot.protocol.ProtocolVersion;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class BotManagerMaintenanceHoldTest {
    @Test
    void holdCancelsQueuedActivationStopsSessionAndBlocksEveryManagerEntry() {
        MaintenanceHoldRegistryTest.MutableClock clock = clock();
        try (BotManager manager = manager(clock)) {
            manager.startEnabled();
            assertThat(manager.activationSnapshots()).containsExactly(
                new ActivationSnapshot("Farm01", clock.instant().plusSeconds(60), ActivationKind.START));

            assertThat(manager.hold("farm01", "proxy maintenance")).isTrue();

            assertThat(manager.activationSnapshots()).isEmpty();
            assertThat(manager.pendingActivation("Farm01")).isEmpty();
            assertThat(manager.find("Farm01").orElseThrow().snapshot().state()).isEqualTo(BotState.STOPPED);
            assertThat(manager.holdSnapshot("FARM01")).get()
                .extracting(MaintenanceHoldSnapshot::reason)
                .isEqualTo("proxy maintenance");
            assertThat(manager.start("Farm01")).isFalse();
            assertThat(manager.startAutomatically("Farm01")).isFalse();
            assertThat(manager.reconnect("Farm01")).isFalse();
            assertThat(manager.reconnectAutomatically("Farm01")).isFalse();
            assertThat(manager.activationSnapshots()).isEmpty();
        }
    }

    @Test
    void resumeOnlyUnlocksAndLeavesStartingToTheCaller() {
        MaintenanceHoldRegistryTest.MutableClock clock = clock();
        try (BotManager manager = manager(clock)) {
            manager.startEnabled();
            manager.hold("Farm01", "manual gate");

            assertThat(manager.resume("farm01")).isTrue();

            assertThat(manager.isHeld("Farm01")).isFalse();
            assertThat(manager.activationSnapshots()).isEmpty();
            assertThat(manager.find("Farm01").orElseThrow().snapshot().state()).isEqualTo(BotState.STOPPED);

            assertThat(manager.start("farm01")).isTrue();
            assertThat(manager.pendingActivation("Farm01")).isPresent();
            assertThat(manager.stop("Farm01")).isTrue();
            assertThat(manager.activationSnapshots()).isEmpty();
            assertThat(manager.resume("Farm01")).isFalse();
        }
    }

    @Test
    void expiredTtlIsRemovedLazilyAndNoLongerBlocksActivation() {
        MaintenanceHoldRegistryTest.MutableClock clock = clock();
        try (BotManager manager = manager(clock)) {
            manager.startEnabled();
            manager.hold("Farm01", "short gate", Duration.ofSeconds(10));

            clock.advance(Duration.ofSeconds(10));

            assertThat(manager.isHeld("Farm01")).isFalse();
            assertThat(manager.holdSnapshots()).isEmpty();
            assertThat(manager.startAutomatically("Farm01")).isTrue();
            assertThat(manager.pendingActivation("Farm01")).isPresent();
        }
    }

    @Test
    void invalidTtlAndUnknownIdsDoNotMutateManagerState() {
        try (BotManager manager = manager(clock())) {
            assertThatThrownBy(() -> manager.hold("Farm01", "invalid", (Duration) null))
                .isInstanceOf(IllegalArgumentException.class);
            assertThatThrownBy(() -> manager.hold("Farm01", "invalid", null, "lobby"))
                .isInstanceOf(IllegalArgumentException.class);
            assertThatThrownBy(() -> manager.hold("Farm01", "invalid", Duration.ZERO))
                .isInstanceOf(IllegalArgumentException.class);
            assertThat(manager.holdSnapshots()).isEmpty();
            assertThat(manager.hold("missing", "maintenance")).isFalse();
            assertThat(manager.resume("missing")).isFalse();
        }
    }

    @Test
    void removingABotClearsItsQueueAndHold() {
        try (BotManager manager = manager(clock())) {
            manager.startEnabled();
            manager.hold("Farm01", "remove test");

            assertThat(manager.remove("Farm01")).isTrue();

            assertThat(manager.find("Farm01")).isEmpty();
            assertThat(manager.holdSnapshots()).isEmpty();
            assertThat(manager.activationSnapshots()).isEmpty();
        }
    }

    @Test
    void activationQueueRetainsStartVersusReconnectReason() {
        MaintenanceHoldRegistryTest.MutableClock clock = clock();
        try (BotManager manager = manager(clock)) {
            assertThat(manager.start("Farm01")).isTrue();
            assertThat(manager.pendingActivation("Farm01")).get()
                .extracting(ActivationSnapshot::kind)
                .isEqualTo(ActivationKind.START);

            assertThat(manager.reconnect("Farm01")).isTrue();
            assertThat(manager.pendingActivation("Farm01")).get()
                .extracting(ActivationSnapshot::kind)
                .isEqualTo(ActivationKind.RECONNECT);
        }
    }

    @Test
    void reloadActivationGateInvalidatesWorkUntilExplicitlyResumed() {
        try (BotManager manager = manager(clock())) {
            manager.startEnabled();
            assertThat(manager.activationSnapshots()).hasSize(1);

            manager.pauseActivations();

            assertThat(manager.activationsPaused()).isTrue();
            assertThat(manager.activationSnapshots()).isEmpty();
            assertThat(manager.start("Farm01")).isFalse();
            assertThat(manager.reconnect("Farm01")).isFalse();

            assertThat(manager.resumeActivations()).isTrue();
            assertThat(manager.activationsPaused()).isFalse();
            assertThat(manager.start("Farm01")).isTrue();
            assertThat(manager.activationSnapshots()).hasSize(1);
        }
    }

    @Test
    void restoringAHoldPreservesItsOriginalAuditMetadata() {
        MaintenanceHoldRegistryTest.MutableClock clock = clock();
        try (BotManager manager = manager(clock)) {
            MaintenanceHoldSnapshot original = new MaintenanceHoldSnapshot(
                "Farm01", "reload gate", clock.instant().minusSeconds(30),
                java.util.Optional.of(clock.instant().plusSeconds(90)), "lobby");

            assertThat(manager.restoreHold(original)).isTrue();
            assertThat(manager.holdSnapshot("farm01")).contains(original);
            assertThat(manager.find("farm01").orElseThrow().snapshot().state())
                .isEqualTo(BotState.STOPPED);
        }
    }

    private static MaintenanceHoldRegistryTest.MutableClock clock() {
        return new MaintenanceHoldRegistryTest.MutableClock(
            Instant.parse("2026-07-30T04:00:00Z"));
    }

    private static BotManager manager(MaintenanceHoldRegistryTest.MutableClock clock) {
        return new BotManager(config(), LoggerFactory.getLogger(BotManagerMaintenanceHoldTest.class),
            (definition, endpoint) -> null, clock);
    }

    private static BotPluginConfig config() {
        BotPluginConfig.ProxyEndpoint proxy = new BotPluginConfig.ProxyEndpoint(
            "127.0.0.1", 9, "localhost", 9,
            ProtocolSelection.fixed(ProtocolVersion.MINECRAFT_1_16_5), 100);
        BotPluginConfig.ReconnectConfig reconnect = new BotPluginConfig.ReconnectConfig(
            1_000, 10_000, 2.0D, 0.0D, 3);
        BotPluginConfig.RuntimeConfig runtime = new BotPluginConfig.RuntimeConfig(
            60_000, 250, 10, 100, 0,
            BotPluginConfig.ResourcePackMode.DECLINE, false, reconnect);
        BotPluginConfig.AuthConfig auth = new BotPluginConfig.AuthConfig(
            BotPluginConfig.AuthMode.NONE, "", "", 0, 0, 0,
            List.of(), List.of(), List.of());
        BotPluginConfig.BotDefinition bot = new BotPluginConfig.BotDefinition(
            "Farm01", true, "AFK_Farm01", "", "lobby", "", 2,
            auth, "server {server}", 1_000, 0, List.of());
        return new BotPluginConfig(proxy, runtime, Map.of("farm01", bot));
    }
}
