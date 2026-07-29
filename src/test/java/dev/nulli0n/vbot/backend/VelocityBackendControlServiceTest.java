package dev.nulli0n.vbot.backend;

import dev.nulli0n.vbot.backend.protocol.BackendCapabilities;
import dev.nulli0n.vbot.backend.protocol.BackendGameMode;
import dev.nulli0n.vbot.backend.protocol.BackendInvulnerability;
import dev.nulli0n.vbot.backend.protocol.BackendOperation;
import dev.nulli0n.vbot.backend.protocol.BackendPolicy;
import dev.nulli0n.vbot.backend.protocol.BackendStatus;
import dev.nulli0n.vbot.backend.protocol.ManagedBoolean;
import dev.nulli0n.vbot.backend.protocol.RespawnMode;
import dev.nulli0n.vbot.backend.protocol.RespawnPoint;
import dev.nulli0n.vbot.bot.BotManager;
import dev.nulli0n.vbot.bot.BotSession;
import dev.nulli0n.vbot.config.BotPluginConfig;
import dev.nulli0n.vbot.protocol.ProtocolSelection;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

class VelocityBackendControlServiceTest {
    @Test
    void mergesOnlyTheFieldPresentInACommandPatch() {
        BackendPolicy original = new BackendPolicy(BackendInvulnerability.ENABLED,
            BackendGameMode.SURVIVAL, RespawnPoint.fixed("world", 1, 64, 2, 90, 0),
            ManagedBoolean.ENABLED, ManagedBoolean.DISABLED,
            ManagedBoolean.DISABLED, ManagedBoolean.ENABLED);

        BackendPolicy updated = VelocityBackendControlService.merge(original,
            BackendControlPatch.gameMode(BackendGameMode.CREATIVE));

        assertThat(updated.invulnerability()).isEqualTo(BackendInvulnerability.ENABLED);
        assertThat(updated.gameMode()).isEqualTo(BackendGameMode.CREATIVE);
        assertThat(updated.respawnPoint()).isEqualTo(original.respawnPoint());
        assertThat(updated.sleepingIgnored()).isEqualTo(ManagedBoolean.ENABLED);
        assertThat(updated.affectsSpawning()).isEqualTo(ManagedBoolean.DISABLED);
        assertThat(updated.pickupItems()).isEqualTo(ManagedBoolean.DISABLED);
        assertThat(updated.collidable()).isEqualTo(ManagedBoolean.ENABLED);
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
                "world_nether", 0, 0, 0, 0),
            BotPluginConfig.AfkPreset.SAFE,
            BotPluginConfig.ManagedFlag.ENABLED,
            BotPluginConfig.ManagedFlag.DISABLED,
            BotPluginConfig.ManagedFlag.DISABLED,
            BotPluginConfig.ManagedFlag.ENABLED);

        BackendPolicy policy = VelocityBackendControlService.configuredPolicy(configured);

        assertThat(policy.invulnerability()).isEqualTo(BackendInvulnerability.DISABLED);
        assertThat(policy.gameMode()).isEqualTo(BackendGameMode.ADVENTURE);
        assertThat(policy.respawnPoint().mode()).isEqualTo(RespawnMode.WORLD_SPAWN);
        assertThat(policy.respawnPoint().world()).isEqualTo("world_nether");
        assertThat(policy.sleepingIgnored()).isEqualTo(ManagedBoolean.ENABLED);
        assertThat(policy.affectsSpawning()).isEqualTo(ManagedBoolean.DISABLED);
        assertThat(policy.pickupItems()).isEqualTo(ManagedBoolean.DISABLED);
        assertThat(policy.collidable()).isEqualTo(ManagedBoolean.ENABLED);
    }

    @Test
    void extendedFactoriesCarryPresenceAndCanStopManagingEachField() {
        BackendControlPatch preset = BackendControlPatch.afkPreset(
            InvulnerabilityChange.ENABLED,
            ManagedBoolean.ENABLED,
            ManagedBoolean.DISABLED,
            ManagedBoolean.DISABLED,
            ManagedBoolean.DISABLED);

        BackendPolicy policy = VelocityBackendControlService.merge(BackendPolicy.unchanged(), preset);
        assertThat(preset.extendedFieldsPresent()).isTrue();
        assertThat(policy.invulnerability()).isEqualTo(BackendInvulnerability.ENABLED);
        assertThat(policy.sleepingIgnored()).isEqualTo(ManagedBoolean.ENABLED);
        assertThat(policy.affectsSpawning()).isEqualTo(ManagedBoolean.DISABLED);
        assertThat(policy.pickupItems()).isEqualTo(ManagedBoolean.DISABLED);
        assertThat(policy.collidable()).isEqualTo(ManagedBoolean.DISABLED);

        policy = VelocityBackendControlService.merge(policy,
            BackendControlPatch.sleepingIgnored(ManagedBoolean.UNCHANGED));
        policy = VelocityBackendControlService.merge(policy,
            BackendControlPatch.affectsSpawning(ManagedBoolean.UNCHANGED));
        policy = VelocityBackendControlService.merge(policy,
            BackendControlPatch.pickupItems(ManagedBoolean.UNCHANGED));
        policy = VelocityBackendControlService.merge(policy,
            BackendControlPatch.collidable(ManagedBoolean.UNCHANGED));

        assertThat(policy.invulnerability()).isEqualTo(BackendInvulnerability.ENABLED);
        assertThat(policy.sleepingIgnored()).isEqualTo(ManagedBoolean.UNCHANGED);
        assertThat(policy.affectsSpawning()).isEqualTo(ManagedBoolean.UNCHANGED);
        assertThat(policy.pickupItems()).isEqualTo(ManagedBoolean.UNCHANGED);
        assertThat(policy.collidable()).isEqualTo(ManagedBoolean.UNCHANGED);
    }

    @Test
    void refusesToDowngradeExtendedPolicyForLegacyCompanion() {
        BackendCapabilityCache.Capabilities legacy =
            BackendCapabilityCache.Capabilities.parse("Bots4VeloPaper protocol=1 ready");
        BackendCapabilityCache.Capabilities current = BackendCapabilityCache.Capabilities.parse(
            "Bots4VeloPaper ready; " + BackendCapabilities.ADVERTISEMENT);
        BackendPolicy legacyPolicy = BackendPolicy.unchanged();
        BackendPolicy extendedPolicy = new BackendPolicy(
            BackendInvulnerability.UNCHANGED,
            BackendGameMode.UNCHANGED,
            RespawnPoint.unchanged(),
            ManagedBoolean.ENABLED,
            ManagedBoolean.UNCHANGED,
            ManagedBoolean.DISABLED,
            ManagedBoolean.DISABLED);

        assertThat(VelocityBackendControlService.probeOperation(legacy))
            .isEqualTo(BackendOperation.PROBE);
        assertThat(VelocityBackendControlService.probeOperation(current))
            .isEqualTo(BackendOperation.PROBE_EXT);
        assertThat(VelocityBackendControlService.policyOperation(legacyPolicy, legacy))
            .contains(BackendOperation.APPLY_POLICY);
        assertThat(VelocityBackendControlService.policyOperation(extendedPolicy, legacy)).isEmpty();
        assertThat(VelocityBackendControlService.policyOperation(extendedPolicy, current))
            .contains(BackendOperation.APPLY_POLICY_EXT);
    }

    @Test
    void queuedOperationCannotRetargetARecreatedBotWithTheSameId() {
        BotPluginConfig config = enabledBotConfig();
        BotManager manager = new BotManager(config, LoggerFactory.getLogger(getClass()),
            (definition, endpoint) -> null);
        AtomicReference<BotManager> currentManager = new AtomicReference<>(manager);
        VelocityBackendControlService service = new VelocityBackendControlService(
            null, new Object(), LoggerFactory.getLogger(getClass()), config, currentManager::get);
        service.start();
        BotSession original = manager.find("Farm01").orElseThrow();
        CompletableFuture<BackendControlResult> gate = new CompletableFuture<>();
        service.enqueue("Farm01", () -> gate);

        CompletableFuture<BackendControlResult> queuedProbe = service.probe("Farm01")
            .toCompletableFuture();
        assertThat(manager.remove("Farm01")).isTrue();
        service.removeBot("Farm01");
        assertThat(manager.create(original.definition())).isEqualTo(BotManager.CreateResult.CREATED);
        assertThat(manager.find("Farm01").orElseThrow()).isNotSameAs(original);

        gate.complete(BackendControlResult.failure(
            "Farm01", BackendStatus.APPLY_FAILED, "release queue"));

        assertThat(queuedProbe.join().status()).isEqualTo(BackendStatus.BOT_NOT_ON_SERVER);
        assertThat(queuedProbe.join().detail()).contains("removed or replaced");
        service.close();
        manager.close();
    }

    @Test
    void pendingCancellationUsesExactConnectionIdentityAndSafeRetentionStatus() {
        Object oldConnection = new Object();
        Object currentConnection = new Object();

        assertThat(VelocityBackendControlService.shouldCancelPending(
            "farm01", "Farm01", currentConnection, oldConnection)).isTrue();
        assertThat(VelocityBackendControlService.shouldCancelPending(
            "farm01", "Farm01", currentConnection, currentConnection)).isFalse();
        assertThat(VelocityBackendControlService.shouldCancelPending(
            "farm01", "Other", null, oldConnection)).isFalse();
        assertThat(VelocityBackendControlService.shouldCancelPending(
            "farm01", "Farm01", null, oldConnection)).isTrue();

        assertThat(VelocityBackendControlService.retainsDesiredPolicy(BackendStatus.TIMEOUT)).isTrue();
        assertThat(VelocityBackendControlService.retainsDesiredPolicy(BackendStatus.OK)).isTrue();
        assertThat(VelocityBackendControlService.retainsDesiredPolicy(
            BackendStatus.BOT_NOT_ON_SERVER)).isFalse();
    }

    @Test
    void sessionBindingUsesObjectIdentityRatherThanOnlyTheBotId() {
        Object original = new Object();
        Object replacementWithSameLogicalId = new Object();

        assertThat(VelocityBackendControlService.sameSession(original, original)).isTrue();
        assertThat(VelocityBackendControlService.sameSession(
            original, replacementWithSameLogicalId)).isFalse();
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

    private static BotPluginConfig enabledBotConfig() {
        BotPluginConfig.ProxyEndpoint proxy = new BotPluginConfig.ProxyEndpoint(
            "127.0.0.1", 25577, "localhost", 25577, ProtocolSelection.autoDetect(), 1_000);
        BotPluginConfig.RuntimeConfig runtime = new BotPluginConfig.RuntimeConfig(
            0, 0, 4, 0, 0, BotPluginConfig.ResourcePackMode.DECLINE, false,
            new BotPluginConfig.ReconnectConfig(0, 100, 1.0, 0.0, 0),
            List.of(), "", List.of(), "127.0.0.1", 0,
            new BotPluginConfig.BackendControlConfig(true,
                "0123456789abcdef0123456789abcdef", "", 3_000));
        BotPluginConfig.AuthConfig auth = new BotPluginConfig.AuthConfig(
            BotPluginConfig.AuthMode.NONE, "", "", 0, 0, 0,
            List.of(), List.of(), List.of());
        BotPluginConfig.BotDefinition definition = new BotPluginConfig.BotDefinition(
            "Farm01", false, "AFK_Farm01", "", "afk", "", 2,
            auth, "server {server}", 0, 0, List.of());
        return new BotPluginConfig(proxy, runtime, Map.of("farm01", definition));
    }
}
