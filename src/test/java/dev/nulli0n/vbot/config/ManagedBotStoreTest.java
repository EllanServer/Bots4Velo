package dev.nulli0n.vbot.config;

import dev.nulli0n.vbot.config.BotPluginConfig.BotDefinition;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ManagedBotStoreTest {
    @TempDir
    Path temporaryDirectory;

    @Test
    void atomicallyRoundTripsAndRemovesManagedBot() throws Exception {
        ManagedBotStore store = ManagedBotStore.load(temporaryDirectory);
        BotDefinition definition = ManagedBotStore.createDefinition(
            "Dynamic01", "AFK_Dynamic01", "p:a#ss", "survival");

        store.add(definition);

        ManagedBotStore reloaded = ManagedBotStore.load(temporaryDirectory);
        assertThat(reloaded.contains("dynamic01")).isTrue();
        assertThat(reloaded.definitions().get("dynamic01").password()).isEqualTo("p:a#ss");
        assertThat(reloaded.definitions().get("dynamic01").targetServer()).isEqualTo("survival");
        assertThat(reloaded.definitions().get("dynamic01").protocolDetectionServer()).isEqualTo("survival");
        assertThat(reloaded.definitions().get("dynamic01").enabled()).isTrue();

        assertThat(reloaded.remove("DYNAMIC01")).isTrue();
        assertThat(ManagedBotStore.load(temporaryDirectory).definitions()).isEmpty();
        assertThat(Files.readString(temporaryDirectory.resolve(ManagedBotStore.FILE_NAME)))
            .contains("bots:");
    }

    @Test
    void dashPasswordCreatesAuthenticationDisabledBot() {
        BotDefinition definition = ManagedBotStore.createDefinition(
            "NoAuth", "AFK_NoAuth", "", "");

        assertThat(definition.auth().mode()).isEqualTo(BotPluginConfig.AuthMode.NONE);
        assertThat(definition.targetServer()).isBlank();
    }

    @Test
    void managedBotDefaultsRecognizeAuthMeRegistrationAndLoginSuccess() {
        BotDefinition definition = ManagedBotStore.createDefinition(
            "AuthBot", "AFK_AuthBot", "secret", "lobby");

        assertThat(matchesAny(definition, "You have successfully registered! ")).isTrue();
        assertThat(matchesAny(definition, "Successful login!")).isTrue();
        assertThat(matchesAny(definition, "Login session continued.")).isTrue();
        assertThat(matchesAny(definition, "Logged-in due to Session Reconnection.")).isTrue();
        assertThat(matchesAny(definition, "You are already logged in!")).isTrue();
    }

    @Test
    void roundTripsManagedPlayerState() throws Exception {
        BotDefinition base = ManagedBotStore.createDefinition(
            "Protected01", "AFK_Protected01", "secret", "survival");
        BotPluginConfig.PlayerStateConfig playerState = new BotPluginConfig.PlayerStateConfig(
            BotPluginConfig.InvulnerabilityMode.ENABLED,
            BotPluginConfig.ManagedGameMode.SURVIVAL,
            1_250L,
            new BotPluginConfig.RespawnPointConfig(
                BotPluginConfig.RespawnPointMode.FIXED, "world", 12.5D, 64.0D, -8.25D, 180.0F),
            BotPluginConfig.AfkPreset.FARM,
            BotPluginConfig.ManagedFlag.DISABLED,
            BotPluginConfig.ManagedFlag.ENABLED,
            BotPluginConfig.ManagedFlag.ENABLED,
            BotPluginConfig.ManagedFlag.DISABLED);
        BotDefinition definition = new BotDefinition(
            base.id(), base.enabled(), base.username(), base.password(), base.targetServer(),
            base.protocolDetectionServer(), base.renderDistance(), base.auth(), base.serverSwitchCommand(),
            base.serverSwitchDelayMillis(), base.serverSwitchMaximumAttempts(), base.afterLoginCommands(),
            base.groups(), base.tags(), base.displayName(), base.tabGroup(), base.protocolOverride(),
            base.templateName(), base.behavior(), playerState);

        ManagedBotStore store = ManagedBotStore.load(temporaryDirectory);
        store.add(definition);

        BotDefinition reloaded = ManagedBotStore.load(temporaryDirectory)
            .definitions().get("protected01");
        assertThat(reloaded.playerState()).isEqualTo(playerState);
        assertThat(Files.readString(temporaryDirectory.resolve(ManagedBotStore.FILE_NAME)))
            .contains("player-state:", "afk-preset: FARM", "invulnerable: ENABLED",
                "sleep-ignored: DISABLED", "affects-spawning: ENABLED", "pickup-items: ENABLED",
                "collidable: DISABLED", "game-mode: SURVIVAL", "mode: FIXED");
    }

    @Test
    void roundTripsManagedAuthMeUiConfiguration() throws Exception {
        BotDefinition base = ManagedBotStore.createDefinition(
            "AuthUi01", "AFK_AuthUi01", "secret", "lobby");
        BotPluginConfig.AuthConfig original = base.auth();
        BotPluginConfig.AuthConfig auth = new BotPluginConfig.AuthConfig(
            original.mode(), original.loginCommand(), original.registerCommand(), original.loginDelayMillis(),
            original.fallbackRegisterDelayMillis(), original.afterAuthDelayMillis(), original.loginPrompts(),
            original.registerPrompts(), original.successMessages(), original.failureMessages(),
            original.timeoutMillis(), false, "bot@example.test",
            BotPluginConfig.RegistrationSecondArgument.EMAIL_MANDATORY, 4_500L);
        BotDefinition definition = new BotDefinition(
            base.id(), base.enabled(), base.username(), base.password(), base.targetServer(),
            base.protocolDetectionServer(), base.renderDistance(), auth, base.serverSwitchCommand(),
            base.serverSwitchDelayMillis(), base.serverSwitchMaximumAttempts(), base.afterLoginCommands(),
            base.groups(), base.tags(), base.displayName(), base.tabGroup(), base.protocolOverride(),
            base.templateName(), base.behavior(), base.playerState());

        ManagedBotStore store = ManagedBotStore.load(temporaryDirectory);
        store.add(definition);

        BotPluginConfig.AuthConfig reloaded = ManagedBotStore.load(temporaryDirectory)
            .definitions().get("authui01").auth();
        assertThat(reloaded.acceptRules()).isFalse();
        assertThat(reloaded.registrationEmail()).isEqualTo("bot@example.test");
        assertThat(reloaded.registrationSecondArgument())
            .isEqualTo(BotPluginConfig.RegistrationSecondArgument.EMAIL_MANDATORY);
        assertThat(reloaded.uiDetectionGraceMillis()).isEqualTo(4_500L);
        assertThat(Files.readString(temporaryDirectory.resolve(ManagedBotStore.FILE_NAME)))
            .contains("authmeui:", "accept-rules: false", "registration-email: bot@example.test",
                "registration-second-argument: EMAIL_MANDATORY", "ui-detection-grace-ms: 4500");
    }

    @Test
    void rejectsUnsafeIdentifiersBeforeWriting() {
        assertThatThrownBy(() -> ManagedBotStore.createDefinition(
            "bad id", "AFK_Good", "secret", "survival"))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("Bot id");
    }

    private static boolean matchesAny(BotDefinition definition, String message) {
        return definition.auth().successMessages().stream()
            .map(Pattern::compile)
            .anyMatch(pattern -> pattern.matcher(message).find());
    }
}
