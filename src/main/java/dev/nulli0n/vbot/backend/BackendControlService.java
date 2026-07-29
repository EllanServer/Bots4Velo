package dev.nulli0n.vbot.backend;

import dev.nulli0n.vbot.backend.protocol.BackendStatus;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;

/**
 * Velocity-side access to the authenticated Paper companion channel.
 * Implementations merge partial patches with the bot's desired policy before
 * sending a complete backend protocol request.
 */
public interface BackendControlService {
    CompletionStage<BackendControlResult> probe(String botId);

    CompletionStage<BackendControlResult> apply(String botId, BackendControlPatch patch);

    CompletionStage<BackendControlResult> respawn(String botId);

    static BackendControlService unavailable() {
        return new BackendControlService() {
            @Override
            public CompletionStage<BackendControlResult> probe(String botId) {
                return unavailableResult(botId);
            }

            @Override
            public CompletionStage<BackendControlResult> apply(String botId, BackendControlPatch patch) {
                return unavailableResult(botId);
            }

            @Override
            public CompletionStage<BackendControlResult> respawn(String botId) {
                return unavailableResult(botId);
            }

            private CompletionStage<BackendControlResult> unavailableResult(String botId) {
                return CompletableFuture.completedFuture(BackendControlResult.failure(botId,
                    BackendStatus.PLUGIN_MISSING, "The Paper companion is not connected."));
            }
        };
    }
}
