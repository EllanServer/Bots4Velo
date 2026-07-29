package dev.nulli0n.vbot.command;

import dev.nulli0n.vbot.backend.BackendControlPatch;
import dev.nulli0n.vbot.backend.BackendControlResult;
import dev.nulli0n.vbot.backend.BackendControlService;
import dev.nulli0n.vbot.backend.InvulnerabilityChange;
import dev.nulli0n.vbot.backend.protocol.ActualState;
import dev.nulli0n.vbot.backend.protocol.BackendGameMode;
import dev.nulli0n.vbot.backend.protocol.BackendStatus;
import dev.nulli0n.vbot.backend.protocol.RespawnMode;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;

import static org.assertj.core.api.Assertions.assertThat;

class PlayerStateCommandHandlerTest {
    @Test
    void aggregatesAsynchronousAcknowledgementsForASelector() {
        RecordingBackend backend = new RecordingBackend();
        backend.failureBot = "two";
        PlayerStateCommandHandler handler = new PlayerStateCommandHandler(backend,
            selector -> selector.equals("@farm") ? List.of("one", "two", "ONE") : List.of());

        PlayerStateCommandHandler.Reply reply = handler.execute("invulnerable",
            new String[]{"invulnerable", "@farm", "on"}).join();

        assertThat(backend.applied).hasSize(2);
        BackendControlPatch patch = backend.applied.getFirst().patch();
        assertThat(patch.invulnerabilityPresent()).isTrue();
        assertThat(patch.invulnerability()).isEqualTo(InvulnerabilityChange.ENABLED);
        assertThat(patch.gameModePresent()).isFalse();
        assertThat(patch.respawnPointPresent()).isFalse();
        assertThat(reply.severity()).isEqualTo(PlayerStateCommandHandler.Severity.WARNING);
        assertThat(reply.lines()).containsExactly(
            "Invulnerability update acknowledged by 1/2 bot(s).",
            "two: APPLY_FAILED - rejected for test");
    }

    @Test
    void keepAndUnchangedAreExplicitFieldUpdates() {
        RecordingBackend backend = new RecordingBackend();
        PlayerStateCommandHandler handler = handler(backend);

        handler.execute("invulnerable", new String[]{"invulnerable", "bot", "keep"}).join();
        handler.execute("gamemode", new String[]{"gamemode", "bot", "unchanged"}).join();

        BackendControlPatch invulnerability = backend.applied.get(0).patch();
        assertThat(invulnerability.invulnerabilityPresent()).isTrue();
        assertThat(invulnerability.invulnerability()).isEqualTo(InvulnerabilityChange.KEEP);
        BackendControlPatch gameMode = backend.applied.get(1).patch();
        assertThat(gameMode.gameModePresent()).isTrue();
        assertThat(gameMode.gameMode()).isEqualTo(BackendGameMode.UNCHANGED);
    }

    @Test
    void parsesFixedAndNamedSpawnPointModes() {
        RecordingBackend backend = new RecordingBackend();
        PlayerStateCommandHandler handler = handler(backend);

        handler.execute("spawnpoint", new String[]{
            "spawnpoint", "bot", "set", "minecraft:overworld", "1.5", "64", "-2.25", "180"
        }).join();
        handler.execute("spawnpoint", new String[]{"spawnpoint", "bot", "current"}).join();
        handler.execute("spawnpoint", new String[]{"spawnpoint", "bot", "worldspawn"}).join();
        handler.execute("spawnpoint", new String[]{"spawnpoint", "bot", "clear"}).join();

        var fixed = backend.applied.get(0).patch().respawnPoint();
        assertThat(fixed.mode()).isEqualTo(RespawnMode.FIXED);
        assertThat(fixed.world()).isEqualTo("minecraft:overworld");
        assertThat(fixed.x()).isEqualTo(1.5D);
        assertThat(fixed.y()).isEqualTo(64.0D);
        assertThat(fixed.z()).isEqualTo(-2.25D);
        assertThat(fixed.yaw()).isEqualTo(180.0F);
        assertThat(backend.applied.subList(1, 4).stream()
            .map(call -> call.patch().respawnPoint().mode()))
            .containsExactly(RespawnMode.CURRENT, RespawnMode.WORLD_SPAWN, RespawnMode.CLEAR);
    }

    @Test
    void respawnUsesTheDedicatedBackendOperation() {
        RecordingBackend backend = new RecordingBackend();

        PlayerStateCommandHandler.Reply reply = handler(backend).execute("respawn",
            new String[]{"respawn", "bot"}).join();

        assertThat(backend.respawned).containsExactly("bot");
        assertThat(backend.applied).isEmpty();
        assertThat(reply.severity()).isEqualTo(PlayerStateCommandHandler.Severity.SUCCESS);
        assertThat(reply.lines()).containsExactly("Respawn request acknowledged by 1/1 bot(s).");
    }

    @Test
    void reportsInputErrorsWithoutCallingTheBackend() {
        RecordingBackend backend = new RecordingBackend();
        PlayerStateCommandHandler handler = handler(backend);

        var invalidMode = handler.execute("gamemode",
            new String[]{"gamemode", "bot", "builder"}).join();
        var invalidCoordinate = handler.execute("spawnpoint",
            new String[]{"spawnpoint", "bot", "set", "world", "NaN", "64", "0"}).join();
        var invalidArity = handler.execute("respawn", new String[]{"respawn"}).join();

        assertThat(invalidMode.severity()).isEqualTo(PlayerStateCommandHandler.Severity.ERROR);
        assertThat(invalidMode.lines().getFirst()).contains("survival").contains("unchanged");
        assertThat(invalidCoordinate.lines()).containsExactly("x must be a finite number.");
        assertThat(invalidArity.severity()).isEqualTo(PlayerStateCommandHandler.Severity.USAGE);
        assertThat(invalidArity.lines()).containsExactly("Usage: /vbot respawn <id|selector>");
        assertThat(backend.applied).isEmpty();
        assertThat(backend.respawned).isEmpty();
    }

    @Test
    void turnsExceptionalAndMismatchedAcksIntoPerBotFailures() {
        BackendControlService backend = new BackendControlService() {
            @Override
            public CompletionStage<BackendControlResult> probe(String botId) {
                return CompletableFuture.completedFuture(ok(botId));
            }

            @Override
            public CompletionStage<BackendControlResult> apply(String botId, BackendControlPatch patch) {
                if (botId.equals("exception")) {
                    return CompletableFuture.failedFuture(new IllegalStateException("channel closed"));
                }
                return CompletableFuture.completedFuture(ok("somebody-else"));
            }

            @Override
            public CompletionStage<BackendControlResult> respawn(String botId) {
                return CompletableFuture.completedFuture(ok(botId));
            }
        };
        PlayerStateCommandHandler handler = new PlayerStateCommandHandler(backend,
            ignored -> List.of("exception", "mismatch"));

        PlayerStateCommandHandler.Reply reply = handler.execute("gamemode",
            new String[]{"gamemode", "all", "creative"}).join();

        assertThat(reply.severity()).isEqualTo(PlayerStateCommandHandler.Severity.ERROR);
        assertThat(reply.lines()).containsExactly(
            "Game mode update acknowledged by 0/2 bot(s).",
            "exception: APPLY_FAILED - channel closed",
            "mismatch: APPLY_FAILED - Backend acknowledgement target mismatch: somebody-else");
    }

    @Test
    void emptySelectionReturnsAUsefulError() {
        RecordingBackend backend = new RecordingBackend();
        PlayerStateCommandHandler handler = new PlayerStateCommandHandler(backend, ignored -> List.of());

        PlayerStateCommandHandler.Reply reply = handler.execute("gamemode",
            new String[]{"gamemode", "@group:missing", "survival"}).join();

        assertThat(reply.severity()).isEqualTo(PlayerStateCommandHandler.Severity.ERROR);
        assertThat(reply.lines()).containsExactly("No bots matched selector: @group:missing");
        assertThat(backend.applied).isEmpty();
    }

    private static PlayerStateCommandHandler handler(RecordingBackend backend) {
        return new PlayerStateCommandHandler(backend, selector -> List.of(selector));
    }

    private static BackendControlResult ok(String botId) {
        return new BackendControlResult(botId, BackendStatus.OK, "applied", ActualState.absent());
    }

    private static final class RecordingBackend implements BackendControlService {
        private final List<ApplyCall> applied = new ArrayList<>();
        private final List<String> respawned = new ArrayList<>();
        private String failureBot = "";

        @Override
        public CompletionStage<BackendControlResult> probe(String botId) {
            return CompletableFuture.completedFuture(ok(botId));
        }

        @Override
        public CompletionStage<BackendControlResult> apply(String botId, BackendControlPatch patch) {
            applied.add(new ApplyCall(botId, patch));
            if (botId.equals(failureBot)) {
                return CompletableFuture.completedFuture(BackendControlResult.failure(botId,
                    BackendStatus.APPLY_FAILED, "rejected for test"));
            }
            return CompletableFuture.completedFuture(ok(botId));
        }

        @Override
        public CompletionStage<BackendControlResult> respawn(String botId) {
            respawned.add(botId);
            return CompletableFuture.completedFuture(ok(botId));
        }
    }

    private record ApplyCall(String botId, BackendControlPatch patch) {
    }
}
