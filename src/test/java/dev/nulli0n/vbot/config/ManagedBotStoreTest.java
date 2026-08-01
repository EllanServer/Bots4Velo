package dev.nulli0n.vbot.config;

import dev.nulli0n.vbot.config.BotPluginConfig.BotDefinition;
import dev.nulli0n.vbot.protocol.ProtocolVersion;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ManagedBotStoreTest {
    @TempDir
    Path temporaryDirectory;

    @Test
    void atomicallyRoundTripsAndRemovesManagedBot() throws Exception {
        Files.writeString(temporaryDirectory.resolve("secrets.yml"), """
            passwords:
              dynamic-01: "p:a#ss"
            """);
        ManagedBotStore store = ManagedBotStore.load(temporaryDirectory);
        ManagedCredentialReference credential = ManagedCredentialReference.secret("dynamic-01");
        BotDefinition definition = ManagedBotStore.createDefinition(
            "Dynamic01", "AFK_Dynamic01", credential, "survival");

        BotDefinition added = store.add(definition, credential);

        assertThat(added.password()).isEqualTo("p:a#ss");
        ManagedBotStore reloaded = ManagedBotStore.load(temporaryDirectory);
        assertThat(reloaded.contains("dynamic01")).isTrue();
        assertThat(reloaded.definitions().get("dynamic01").password()).isEqualTo("p:a#ss");
        assertThat(reloaded.definitions().get("dynamic01").targetServer()).isEqualTo("survival");
        assertThat(reloaded.definitions().get("dynamic01").protocolDetectionServer()).isEqualTo("survival");
        assertThat(reloaded.definitions().get("dynamic01").enabled()).isTrue();
        assertThat(Files.readString(temporaryDirectory.resolve(ManagedBotStore.FILE_NAME)))
            .contains("password-secret: dynamic-01")
            .doesNotContain("p:a#ss");

        assertThat(reloaded.remove("DYNAMIC01")).isTrue();
        assertThat(ManagedBotStore.load(temporaryDirectory).definitions()).isEmpty();
        assertThat(Files.readString(temporaryDirectory.resolve(ManagedBotStore.FILE_NAME)))
            .contains("bots:");
    }

    @Test
    void dashPasswordCreatesAuthenticationDisabledBot() {
        ManagedCredentialReference credential = ManagedCredentialReference.none();
        BotDefinition definition = ManagedBotStore.createDefinition(
            "NoAuth", "AFK_NoAuth", credential, "");

        assertThat(definition.auth().mode()).isEqualTo(BotPluginConfig.AuthMode.NONE);
        assertThat(definition.targetServer()).isBlank();
    }

    @Test
    void managedBotDefaultsRecognizeAuthMeRegistrationAndLoginSuccess() {
        ManagedCredentialReference credential = ManagedCredentialReference.secret("auth-bot");
        BotDefinition definition = ManagedBotStore.createDefinition(
            "AuthBot", "AFK_AuthBot", credential, "lobby");

        assertThat(matchesAny(definition, "You have successfully registered! ")).isTrue();
        assertThat(matchesAny(definition, "Successful login!")).isTrue();
        assertThat(matchesAny(definition, "Login session continued.")).isTrue();
        assertThat(matchesAny(definition, "Logged-in due to Session Reconnection.")).isTrue();
        assertThat(matchesAny(definition, "You are already logged in!")).isTrue();
    }

    @Test
    void managedBotDefaultsFailClosedForOperatorActionableAuthenticationChallenges() {
        BotDefinition definition = ManagedBotStore.createDefinition(
            "AuthFailureBot", "AFK_AuthFailure", ManagedCredentialReference.secret("auth-failure"), "lobby");

        assertThat(matchesAny(definition.auth().failureMessages(), "Wrong password")).isTrue();
        assertThat(matchesAny(definition.auth().failureMessages(), "Captcha required")).isTrue();
        assertThat(matchesAny(definition.auth().failureMessages(), "2FA verification code required")).isTrue();
        assertThat(matchesAny(definition.auth().failureMessages(), "This account is banned")).isTrue();
    }

    @Test
    void roundTripsManagedPlayerState() throws Exception {
        Files.writeString(temporaryDirectory.resolve("secrets.yml"), """
            passwords:
              protected-01: secret
            """);
        ManagedCredentialReference credential = ManagedCredentialReference.secret("protected-01");
        BotDefinition base = ManagedBotStore.createDefinition(
            "Protected01", "AFK_Protected01", credential, "survival");
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
        store.add(definition, credential);

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
        Files.writeString(temporaryDirectory.resolve("secrets.yml"), """
            passwords:
              auth-ui-01: secret
            """);
        ManagedCredentialReference credential = ManagedCredentialReference.secret("auth-ui-01");
        BotDefinition base = ManagedBotStore.createDefinition(
            "AuthUi01", "AFK_AuthUi01", credential, "lobby");
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
        store.add(definition, credential);

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
            "bad id", "AFK_Good", ManagedCredentialReference.secret("valid"), "survival"))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("Bot id");
    }

    @Test
    void resolvesEnvironmentCredentialWithoutPersistingItsValue() throws Exception {
        ManagedBotStore store = ManagedBotStore.load(
            temporaryDirectory, Map.of("BOT_DYNAMIC_PASSWORD", "from-environment"));
        ManagedCredentialReference credential =
            ManagedCredentialReference.environment("BOT_DYNAMIC_PASSWORD");

        BotDefinition added = store.add("Environment01", "AFK_Env01", credential, "lobby");

        assertThat(added.password()).isEqualTo("from-environment");
        String document = Files.readString(temporaryDirectory.resolve(ManagedBotStore.FILE_NAME));
        assertThat(document).contains("password-env: BOT_DYNAMIC_PASSWORD")
            .doesNotContain("from-environment");
        assertThat(ManagedBotStore.load(
            temporaryDirectory, Map.of("BOT_DYNAMIC_PASSWORD", "from-environment"))
            .definitions().get("environment01").password()).isEqualTo("from-environment");
    }

    @Test
    void persistsNoneWithoutAnyPasswordField() throws Exception {
        ManagedBotStore store = ManagedBotStore.load(temporaryDirectory, Map.of());

        BotDefinition added = store.add("NoAuth01", "AFK_None01",
            ManagedCredentialReference.none(), "");

        assertThat(added.password()).isEmpty();
        assertThat(added.auth().mode()).isEqualTo(BotPluginConfig.AuthMode.NONE);
        String document = Files.readString(temporaryDirectory.resolve(ManagedBotStore.FILE_NAME));
        assertThat(document).doesNotContain("password:", "password-env:", "password-secret:");
        assertThat(ManagedBotStore.load(temporaryDirectory, Map.of())
            .definitions().get("noauth01").password()).isEmpty();
    }

    @Test
    void legacyInlinePasswordLoadsAndRoundTripsWithoutMigration() throws Exception {
        Files.writeString(temporaryDirectory.resolve(ManagedBotStore.FILE_NAME), """
            bots:
              Legacy01:
                enabled: true
                username: AFK_Legacy01
                password: "old:p#a ss"
                auth:
                  mode: AUTO
            """);
        ManagedBotStore store = ManagedBotStore.load(temporaryDirectory, Map.of());

        assertThat(store.definitions().get("legacy01").password()).isEqualTo("old:p#a ss");
        store.add("NoAuth01", "AFK_None01", ManagedCredentialReference.none(), "");

        String document = Files.readString(temporaryDirectory.resolve(ManagedBotStore.FILE_NAME));
        assertThat(document).contains("password: old:p#a ss");
        assertThat(ManagedBotStore.load(temporaryDirectory, Map.of())
            .definitions().get("legacy01").password()).isEqualTo("old:p#a ss");
    }

    @Test
    void legacyInlinePasswordIgnoresBlankReferenceFields() throws Exception {
        Files.writeString(temporaryDirectory.resolve(ManagedBotStore.FILE_NAME), """
            bots:
              Legacy02:
                enabled: true
                username: AFK_Legacy02
                password: old
                password-env: ""
                password-secret: "   "
                auth:
                  mode: AUTO
            """);

        ManagedBotStore store = ManagedBotStore.load(temporaryDirectory, Map.of());

        assertThat(store.definitions().get("legacy02").password()).isEqualTo("old");
        store.add("NoAuth02", "AFK_None02", ManagedCredentialReference.none(), "");
        String persisted = Files.readString(temporaryDirectory.resolve(ManagedBotStore.FILE_NAME));
        assertThat(persisted).contains("password: old")
            .doesNotContain("password-env:", "password-secret:");
        assertThat(ManagedBotStore.load(temporaryDirectory, Map.of())
            .definitions().get("legacy02").password()).isEqualTo("old");
    }

    @Test
    void trimsStoredSecretAndEnvironmentReferencesBeforeResolvingAndPersisting() throws Exception {
        Files.writeString(temporaryDirectory.resolve("secrets.yml"), """
            passwords:
              "team:bot/机器人": secret-value
            """);
        Files.writeString(temporaryDirectory.resolve(ManagedBotStore.FILE_NAME), """
            bots:
              Secret01:
                enabled: true
                username: AFK_Secret01
                password-secret: "  team:bot/机器人  "
                auth:
                  mode: AUTO
              Environment01:
                enabled: true
                username: AFK_Env01
                password-env: "  LEGACY-ENV/ONE  "
                auth:
                  mode: AUTO
            """);

        Map<String, String> environment = Map.of("LEGACY-ENV/ONE", "environment-value");
        ManagedBotStore store = ManagedBotStore.load(temporaryDirectory, environment);

        assertThat(store.definitions().get("secret01").password()).isEqualTo("secret-value");
        assertThat(store.definitions().get("environment01").password()).isEqualTo("environment-value");
        store.add("NoAuth03", "AFK_None03", ManagedCredentialReference.none(), "");
        String persisted = Files.readString(temporaryDirectory.resolve(ManagedBotStore.FILE_NAME));
        assertThat(persisted).contains("password-secret: team:bot/机器人")
            .contains("password-env: LEGACY-ENV/ONE")
            .doesNotContain("  team:bot/机器人  ", "  LEGACY-ENV/ONE  ");

        ManagedBotStore reloaded = ManagedBotStore.load(temporaryDirectory, environment);
        assertThat(reloaded.definitions().get("secret01").password()).isEqualTo("secret-value");
        assertThat(reloaded.definitions().get("environment01").password()).isEqualTo("environment-value");
    }

    @Test
    void rewritePreservesGroupsTagsProtocolTemplateProvenanceAndBehavior() throws Exception {
        Files.writeString(temporaryDirectory.resolve("secrets.yml"), """
            passwords:
              configured: secret-value
            """);
        Files.writeString(temporaryDirectory.resolve(ManagedBotStore.FILE_NAME), """
            bots:
              Configured01:
                enabled: true
                username: AFK_Configured
                password-secret: configured
                groups: [farm, lobby]
                tags: [backup]
                protocol-version: "26.2"
                _template-source: farm-auth
                behavior:
                  mode: PATROL
                  enabled: true
                  interval-ms: 750
                  movement-radius: 0.0
                  yaw-step: 22.5
                  random-yaw: true
                  jump: true
                  swing: true
                  sneak: false
                  commands: ["say patrol"]
                  path:
                    - {x: 1.5, y: 64.0, z: -2.5}
                    - {x: 4.5, y: 65.0, z: 8.5}
                  server-cycle: [lobby, survival]
                  server-cycle-every: 4
                  follow-player: TypeThe0ry
            """);

        ManagedBotStore store = ManagedBotStore.load(temporaryDirectory, Map.of());
        BotDefinition before = store.definitions().get("configured01");
        store.add("NoAuth04", "AFK_None04", ManagedCredentialReference.none(), "");
        BotDefinition after = ManagedBotStore.load(temporaryDirectory, Map.of())
            .definitions().get("configured01");

        assertThat(after.groups()).isEqualTo(before.groups()).containsExactly("farm", "lobby");
        assertThat(after.tags()).isEqualTo(before.tags()).containsExactly("backup");
        assertThat(after.protocolOverride()).isEqualTo(before.protocolOverride());
        assertThat(after.protocolOverride().fixedVersion()).isEqualTo(ProtocolVersion.MINECRAFT_26_2);
        assertThat(after.templateName()).isEqualTo("farm-auth");
        assertThat(after.behavior()).isEqualTo(before.behavior());
        assertThat(Files.readString(temporaryDirectory.resolve(ManagedBotStore.FILE_NAME)))
            .contains("groups:", "tags:", "protocol-version: '26.2'", "_template-source: farm-auth",
                "behavior:", "mode: PATROL", "follow-player: TypeThe0ry");
    }

    @Test
    void rejectsConflictingManagedCredentialSources() throws Exception {
        Files.writeString(temporaryDirectory.resolve(ManagedBotStore.FILE_NAME), """
            bots:
              Conflict01:
                enabled: true
                username: AFK_Conflict
                password: legacy
                password-secret: conflict
            """);

        assertThatThrownBy(() -> ManagedBotStore.load(temporaryDirectory, Map.of()))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("only one of password");
    }

    @Test
    void missingOrEmptyReferencesFailBeforeChangingStoreOrDisk() throws Exception {
        Files.writeString(temporaryDirectory.resolve("secrets.yml"), """
            passwords:
              empty-secret: ""
            """);
        ManagedBotStore store = ManagedBotStore.load(temporaryDirectory, Map.of("EMPTY_ENV", ""));
        ManagedCredentialReference missingSecret = ManagedCredentialReference.secret("missing-secret");
        ManagedCredentialReference emptySecret = ManagedCredentialReference.secret("empty-secret");
        ManagedCredentialReference emptyEnvironment = ManagedCredentialReference.environment("EMPTY_ENV");

        assertThatThrownBy(() -> store.add("Missing01", "AFK_Missing01", missingSecret, ""))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("password-secret");
        assertThatThrownBy(() -> store.add("Empty01", "AFK_Empty01", emptySecret, ""))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("password-secret");
        assertThatThrownBy(() -> store.add("Env01", "AFK_EmptyEnv", emptyEnvironment, ""))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("password-env");

        assertThat(store.definitions()).isEmpty();
        assertThat(Files.exists(temporaryDirectory.resolve(ManagedBotStore.FILE_NAME))).isFalse();
    }

    @Test
    void enabledManagedBotWithMissingReferenceFailsOnLoad() throws Exception {
        Files.writeString(temporaryDirectory.resolve(ManagedBotStore.FILE_NAME), """
            bots:
              Missing01:
                enabled: true
                username: AFK_Missing01
                password-secret: absent
                auth:
                  mode: AUTO
            """);

        assertThatThrownBy(() -> ManagedBotStore.load(temporaryDirectory, Map.of()))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("password-secret");
    }

    @Test
    void plaintextDefinitionCannotUseNewAddPath() throws Exception {
        ManagedBotStore store = ManagedBotStore.load(temporaryDirectory, Map.of());
        BotDefinition legacyFactoryDefinition = ManagedBotStore.createDefinition(
            "Unsafe01", "AFK_Unsafe01", "resolved-password", "");

        assertThatThrownBy(() -> store.add(legacyFactoryDefinition))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("Inline passwords cannot be added");
        assertThat(Files.exists(temporaryDirectory.resolve(ManagedBotStore.FILE_NAME))).isFalse();
    }

    private static boolean matchesAny(BotDefinition definition, String message) {
        return matchesAny(definition.auth().successMessages(), message);
    }

    private static boolean matchesAny(List<String> expressions, String message) {
        return expressions.stream()
            .map(Pattern::compile)
            .anyMatch(pattern -> pattern.matcher(message).find());
    }
}
