package dev.nulli0n.vbot.config;

import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;

import static dev.nulli0n.vbot.config.ConfigChangePreview.ChangeType.BOT_ADDED;
import static dev.nulli0n.vbot.config.ConfigChangePreview.ChangeType.BOT_CHANGED;
import static dev.nulli0n.vbot.config.ConfigChangePreview.ChangeType.BOT_REMOVED;
import static dev.nulli0n.vbot.config.ConfigChangePreview.ChangeType.PROXY_CHANGED;
import static dev.nulli0n.vbot.config.ConfigChangePreview.ChangeType.RUNTIME_CHANGED;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ConfigChangePreviewTest {
    @Test
    void reportsNoChangesWithoutRetainingConfiguration() {
        BotPluginConfig config = parse("bots: {}\n");

        ConfigChangePreview.Preview preview = ConfigChangePreview.compare(config, config);

        assertThat(preview.hasChanges()).isFalse();
        assertThat(preview.changes()).isEmpty();
        assertThat(preview.totalChanges()).isZero();
        assertThat(preview.omittedChanges()).isZero();
        assertThat(preview.renderEnglishLines()).containsExactly("No configuration changes.");
    }

    @Test
    void sortsScopesBotKindsIdsAndFieldsStably() {
        BotPluginConfig current = parse("""
            proxy:
              address: 127.0.0.1
            runtime:
              spawn-interval-ms: 1000
            bots:
              RemovedZ:
                username: RemovedZ
                auth: {mode: NONE}
              changedB:
                username: ChangedB
                auth: {mode: NONE}
              ChangedA:
                username: ChangedA
                auth: {mode: NONE}
            """);
        BotPluginConfig candidate = parse("""
            proxy:
              address: velocity.internal
              port: 25590
            runtime:
              spawn-interval-ms: 2000
              auto-respawn: false
            bots:
              addedZ:
                username: AddedZ
                auth: {mode: NONE}
              AddedA:
                username: AddedA
                auth: {mode: NONE}
              changedB:
                enabled: true
                username: ChangedB
                render-distance: 3
                auth: {mode: NONE}
              ChangedA:
                username: ChangedA2
                auth: {mode: NONE}
            """);

        ConfigChangePreview.Preview preview = ConfigChangePreview.compare(current, candidate);

        assertThat(preview.changes()).extracting(ConfigChangePreview.Change::type)
            .containsExactly(PROXY_CHANGED, RUNTIME_CHANGED, BOT_ADDED, BOT_ADDED,
                BOT_REMOVED, BOT_CHANGED, BOT_CHANGED);
        assertThat(preview.changes()).extracting(ConfigChangePreview.Change::botId)
            .containsExactly("", "", "addeda", "addedz", "removedz", "changeda", "changedb");
        assertThat(preview.changes().get(0).fields()).containsExactly("address", "port");
        assertThat(preview.changes().get(1).fields())
            .containsExactly("spawn-interval-ms", "auto-respawn");
        assertThat(preview.changes().get(5).fields()).containsExactly("username");
        assertThat(preview.changes().get(6).fields()).containsExactly("enabled", "render-distance");
        assertThat(preview.renderEnglishLines()).containsExactly(
            "Proxy settings changed: address, port.",
            "Runtime settings changed: spawn-interval-ms, auto-respawn.",
            "Bot added: addeda.",
            "Bot added: addedz.",
            "Bot removed: removedz.",
            "Bot changed: changeda (fields: username).",
            "Bot changed: changedb (fields: enabled, render-distance)."
        );
    }

    @Test
    void reportsImportantNestedBotFieldsWithoutTheirValues() {
        BotPluginConfig current = parse("""
            bots:
              farm:
                username: AFK_Farm
                password: old-password
                target-server: lobby
                auth:
                  mode: LOGIN
                  login-command: login old-password
                behavior:
                  mode: STATIC
                player-state:
                  game-mode: SURVIVAL
            """);
        BotPluginConfig candidate = parse("""
            bots:
              farm:
                username: AFK_Farm
                password: new-password
                target-server: survival
                auth:
                  mode: LOGIN
                  login-command: login new-password
                behavior:
                  enabled: true
                  mode: FARM
                player-state:
                  game-mode: CREATIVE
                  respawn-point:
                    mode: FIXED
                    world: world_nether
                    x: 5
                    y: 70
                    z: -5
            """);

        ConfigChangePreview.Change change = ConfigChangePreview.compare(current, candidate)
            .changes().getFirst();

        assertThat(change.type()).isEqualTo(BOT_CHANGED);
        assertThat(change.fields()).containsExactly(
            "password",
            "target-server",
            "auth.login-command",
            "behavior.mode",
            "behavior.enabled",
            "player-state.game-mode",
            "player-state.respawn-point.mode",
            "player-state.respawn-point.world",
            "player-state.respawn-point.x",
            "player-state.respawn-point.y",
            "player-state.respawn-point.z"
        );
        assertThat(change.localizationKey()).isEqualTo("reload-check-bot-changed");
        assertThat(change.localizationArguments()).containsExactly(
            "farm", String.join(", ", change.fields()));
    }

    @Test
    void neverLeaksCredentialsEnvironmentValuesUrlsOrCommandContents() {
        String currentPassword = "old-bot-password-9713";
        String candidatePassword = "new-bot-password-8426";
        String currentBackendSecret = "old-backend-secret-5137-xxxxxxxxxxxx";
        String candidateBackendSecret = "new-backend-secret-6842-yyyyyyyyyyyy";
        String currentExpandedCommand = "login old-expanded-password-1179";
        String candidateExpandedCommand = "login new-expanded-password-2280";
        String currentWebhook = "https://example.invalid/hook/old-token-3371";
        String candidateWebhook = "https://example.invalid/hook/new-token-4482";
        String yaml = """
            runtime:
              backend-control:
                enabled: true
                secret-env: BACKEND_TOKEN
              webhook-url: "%s"
            bots:
              SecretBot:
                username: SecretBot
                password: "%s"
                auth:
                  mode: LOGIN
                  login-command: "%s"
                after-login-commands:
                  - "%s"
            """;
        BotPluginConfig current = parse(yaml.formatted(
                currentWebhook, currentPassword, currentExpandedCommand, currentExpandedCommand),
            Map.of("BACKEND_TOKEN", currentBackendSecret));
        BotPluginConfig candidate = parse(yaml.formatted(
                candidateWebhook, candidatePassword, candidateExpandedCommand, candidateExpandedCommand),
            Map.of("BACKEND_TOKEN", candidateBackendSecret));

        ConfigChangePreview.Preview preview = ConfigChangePreview.compare(current, candidate);
        String allObservableOutput = preview + "\n" + String.join("\n", preview.renderEnglishLines())
            + "\n" + preview.changes().stream()
                .map(change -> change.localizationArguments().toString())
                .reduce("", (left, right) -> left + right);

        assertThat(preview.changes()).extracting(ConfigChangePreview.Change::type)
            .containsExactly(RUNTIME_CHANGED, BOT_CHANGED);
        assertThat(preview.changes().get(0).fields())
            .containsExactly("webhook-url", "backend-control.secret");
        assertThat(preview.changes().get(1).fields())
            .containsExactly("password", "auth.login-command", "after-login-commands");
        assertThat(allObservableOutput)
            .doesNotContain(
                currentPassword, candidatePassword,
                currentBackendSecret, candidateBackendSecret,
                currentExpandedCommand, candidateExpandedCommand,
                currentWebhook, candidateWebhook,
                "old-expanded-password-1179", "new-expanded-password-2280"
            );
    }

    @Test
    void detectsCredentialSourceChangesEvenWhenResolvedPasswordIsUnchanged() {
        String sharedPassword = "same-resolved-secret-5831";
        BotPluginConfig current = parse("""
            bots:
              farm:
                enabled: true
                username: AFK_Farm
                password: same-resolved-secret-5831
                auth: {mode: LOGIN}
            """, Map.of("BOT_PASSWORD_ALIAS", sharedPassword));
        BotPluginConfig candidate = parse("""
            bots:
              farm:
                enabled: true
                username: AFK_Farm
                password-env: BOT_PASSWORD_ALIAS
                auth: {mode: LOGIN}
            """, Map.of("BOT_PASSWORD_ALIAS", sharedPassword));

        ConfigChangePreview.Change change = ConfigChangePreview.compare(current, candidate)
            .changes().getFirst();
        String observable = change + " " + change.localizationArguments();

        assertThat(change.type()).isEqualTo(BOT_CHANGED);
        assertThat(change.fields()).containsExactly("credential-source");
        assertThat(observable).doesNotContain(sharedPassword, "BOT_PASSWORD_ALIAS");
    }

    @Test
    void truncatesAtRequestedLimitAndReportsOmittedCount() {
        StringBuilder bots = new StringBuilder("bots:\n");
        for (int index = 9; index >= 0; index--) {
            bots.append("  Bot").append(index).append(":\n")
                .append("    username: AFK_Bot").append(index).append("\n")
                .append("    auth: {mode: NONE}\n");
        }

        ConfigChangePreview.Preview preview = ConfigChangePreview.compare(
            parse("bots: {}\n"), parse(bots.toString()), 3);

        assertThat(preview.changes()).hasSize(3);
        assertThat(preview.changes()).extracting(ConfigChangePreview.Change::botId)
            .containsExactly("bot0", "bot1", "bot2");
        assertThat(preview.totalChanges()).isEqualTo(10);
        assertThat(preview.omittedChanges()).isEqualTo(7);
        assertThat(preview.truncated()).isTrue();
        assertThat(preview.renderEnglishLines()).hasSize(4).last()
            .isEqualTo("... 7 additional changes omitted.");
    }

    @Test
    void rejectsLimitsThatWouldBreakTheHardOutputBound() {
        BotPluginConfig config = parse("bots: {}\n");

        assertThatThrownBy(() -> ConfigChangePreview.compare(config, config, 0))
            .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> ConfigChangePreview.compare(
            config, config, ConfigChangePreview.MAX_CHANGES + 1))
            .isInstanceOf(IllegalArgumentException.class);
    }

    private static BotPluginConfig parse(String yaml) {
        return parse(yaml, Map.of());
    }

    private static BotPluginConfig parse(String yaml, Map<String, String> environment) {
        return ConfigLoader.parse(
            new ByteArrayInputStream(yaml.getBytes(StandardCharsets.UTF_8)), environment);
    }
}
