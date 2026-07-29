package dev.nulli0n.vbot.backend;

import dev.nulli0n.vbot.backend.protocol.BackendStatus;
import org.junit.jupiter.api.Test;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;

import static org.assertj.core.api.Assertions.assertThat;

class BackendControlServiceTest {
    @Test
    void legacyImplementationGetsExplicitUnsupportedRecoveryDefault() {
        BackendControlService legacy = new BackendControlService() {
            @Override
            public CompletionStage<BackendControlResult> probe(String botId) {
                return unused(botId);
            }

            @Override
            public CompletionStage<BackendControlResult> apply(String botId, BackendControlPatch patch) {
                return unused(botId);
            }

            @Override
            public CompletionStage<BackendControlResult> respawn(String botId) {
                return unused(botId);
            }

            private CompletionStage<BackendControlResult> unused(String botId) {
                return CompletableFuture.completedFuture(BackendControlResult.failure(
                    botId, BackendStatus.APPLY_FAILED, "unused"));
            }
        };

        BackendControlResult result = legacy.recover("farm-1").toCompletableFuture().join();

        assertThat(result.status()).isEqualTo(BackendStatus.UNSUPPORTED);
        assertThat(result.botId()).isEqualTo("farm-1");
    }

    @Test
    void unavailableServiceReportsMissingCompanionForRecovery() {
        BackendControlResult result = BackendControlService.unavailable()
            .recover("farm-1").toCompletableFuture().join();
        BackendControlResult nullIdResult = BackendControlService.unavailable()
            .recover(null).toCompletableFuture().join();

        assertThat(result.status()).isEqualTo(BackendStatus.PLUGIN_MISSING);
        assertThat(result.botId()).isEqualTo("farm-1");
        assertThat(nullIdResult.status()).isEqualTo(BackendStatus.PLUGIN_MISSING);
        assertThat(nullIdResult.botId()).isEmpty();
    }
}
