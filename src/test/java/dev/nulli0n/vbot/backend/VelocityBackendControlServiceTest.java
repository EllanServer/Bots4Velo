package dev.nulli0n.vbot.backend;

import dev.nulli0n.vbot.backend.protocol.BackendGameMode;
import dev.nulli0n.vbot.backend.protocol.BackendInvulnerability;
import dev.nulli0n.vbot.backend.protocol.BackendPolicy;
import dev.nulli0n.vbot.backend.protocol.BackendStatus;
import dev.nulli0n.vbot.backend.protocol.RespawnMode;
import dev.nulli0n.vbot.backend.protocol.RespawnPoint;
import dev.nulli0n.vbot.config.BotPluginConfig;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

import static org.assertj.core.api.Assertions.assertThat;

class VelocityBackendControlServiceTest {
    @Test
    void mergesOnlyTheFieldPresentInACommandPatch() {
        BackendPolicy original = new BackendPolicy(BackendInvulnerability.ENABLED,
            BackendGameMode.SURVIVAL, RespawnPoint.fixed("world", 1, 64, 2, 90, 0));

        BackendPolicy updated = VelocityBackendControlService.merge(original,
            BackendControlPatch.gameMode(BackendGameMode.CREATIVE));

        assertThat(updated.invulnerability()).isEqualTo(BackendInvulnerability.ENABLED);
        assertThat(updated.gameMode()).isEqualTo(BackendGameMode.CREATIVE);
        assertThat(updated.respawnPoint()).isEqualTo(original.respawnPoint());
    }

    @Test
    void explicitKeepAndUnchangedStopManagingTheirFields() {
        BackendPolicy original = new BackendPolicy(BackendInvulnerability.ENABLED,
            BackendGameMode.CREATIVE, RespawnPoint.fixed("world", 1, 64, 2, 90, 0));

        BackendPolicy invulnerabilityReset = VelocityBackendControlService.merge(original,
            BackendControlPatch.invulnerability(InvulnerabilityChange.KEEP));
        BackendPolicy gameModeReset = VelocityBackendControlService.merge(invulnerabilityReset,
            BackendControlPatch.gameMode(BackendGameMode.UNCHANGED));
        BackendPolicy spawnReset = VelocityBackendControlService.merge(gameModeReset,
            BackendControlPatch.respawnPoint(RespawnPoint.unchanged()));

        assertThat(VelocityBackendControlService.isUnchanged(spawnReset)).isTrue();
    }

    @Test
    void mapsConfiguredPlayerStateToTheWirePolicy() {
        BotPluginConfig.PlayerStateConfig configured = new BotPluginConfig.PlayerStateConfig(
            BotPluginConfig.InvulnerabilityMode.DISABLED,
            BotPluginConfig.ManagedGameMode.ADVENTURE,
            500,
            new BotPluginConfig.RespawnPointConfig(BotPluginConfig.RespawnPointMode.WORLD_SPAWN,
                "world_nether", 0, 0, 0, 0));

        BackendPolicy policy = VelocityBackendControlService.configuredPolicy(configured);

        assertThat(policy.invulnerability()).isEqualTo(BackendInvulnerability.DISABLED);
        assertThat(policy.gameMode()).isEqualTo(BackendGameMode.ADVENTURE);
        assertThat(policy.respawnPoint().mode()).isEqualTo(RespawnMode.WORLD_SPAWN);
        assertThat(policy.respawnPoint().world()).isEqualTo("world_nether");
    }

    @Test
    void serializesOperationsForTheSameBotEvenWhenTheFirstFails() {
        VelocityBackendControlService service = disabledService();
        CompletableFuture<BackendControlResult> firstAck = new CompletableFuture<>();
        List<String> invocations = new ArrayList<>();

        CompletableFuture<BackendControlResult> first = service.enqueue("Farm01", () -> {
            invocations.add("first");
            return firstAck;
        }).toCompletableFuture();
        CompletableFuture<BackendControlResult> second = service.enqueue("farm01", () -> {
            invocations.add("second");
            return CompletableFuture.completedFuture(BackendControlResult.failure(
                "Farm01", BackendStatus.PLUGIN_MISSING, "expected test failure"));
        }).toCompletableFuture();

        assertThat(invocations).containsExactly("first");
        firstAck.complete(BackendControlResult.failure(
            "Farm01", BackendStatus.APPLY_FAILED, "first failure"));

        assertThat(first.join().status()).isEqualTo(BackendStatus.APPLY_FAILED);
        assertThat(second.join().status()).isEqualTo(BackendStatus.PLUGIN_MISSING);
        assertThat(invocations).containsExactly("first", "second");
        service.close();
    }

    private static VelocityBackendControlService disabledService() {
        BotPluginConfig.RuntimeConfig runtime = new BotPluginConfig.RuntimeConfig(
            0, 0, 1, 0, 0, BotPluginConfig.ResourcePackMode.DECLINE, false,
            new BotPluginConfig.ReconnectConfig(0, 100, 1.0, 0.0, 0));
        return new VelocityBackendControlService(null, new Object(), null,
            new BotPluginConfig(null, runtime, Map.of()), () -> null);
    }
}
