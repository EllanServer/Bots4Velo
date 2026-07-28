package dev.nulli0n.vbot.config;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.charset.StandardCharsets;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ConfigLoaderTest {
    @Test
    void parsesMinimalBotAndAppliesDefaults() {
        BotPluginConfig config = parse("""
            bots:
              Farm01:
                username: AFK_Farm01
                password: secret
            """);

        assertThat(config.proxy().address()).isEqualTo("127.0.0.1");
        assertThat(config.proxy().protocol().automatic()).isTrue();
        assertThat(config.bots()).containsKey("farm01");
        assertThat(config.bots().get("farm01").auth().mode()).isEqualTo(BotPluginConfig.AuthMode.AUTO);
        assertThat(config.bots().get("farm01").enabled()).isFalse();
        assertThat(config.bots().get("farm01").serverSwitchMaximumAttempts()).isZero();
        assertThat(config.bots().get("farm01").protocolDetectionServer()).isBlank();
        assertThat(config.runtime().maximumBots()).isEqualTo(32);
        assertThat(config.bots().get("farm01").auth().timeoutMillis()).isEqualTo(30_000L);
    }

    @Test
    void parsesAuthenticationTimeoutAndRejectsNegativeValue() {
        BotPluginConfig config = parse("""
            bots:
              Farm01:
                username: AFK_Farm01
                password: secret
                auth:
                  timeout-ms: 12000
            """);

        assertThat(config.bots().get("farm01").auth().timeoutMillis()).isEqualTo(12_000L);
        assertThatThrownBy(() -> parse("""
            bots:
              Farm01:
                username: AFK_Farm01
                password: secret
                auth:
                  timeout-ms: -1
            """))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("timeout-ms");
    }

    @Test
    void acceptsManualProtocolVersion() {
        BotPluginConfig config = parse("""
            proxy:
              protocol-version: 1.16.5
            bots:
              Legacy:
                username: AFK_Legacy
                auth:
                  mode: NONE
            """);

        assertThat(config.proxy().protocol().automatic()).isFalse();
        assertThat(config.proxy().protocol().fixedVersion().protocolId()).isEqualTo(754);
    }

    @Test
    void rejectsInvalidOfflineUsername() {
        assertThatThrownBy(() -> parse("""
            bots:
              bad:
                username: name-with-dash
                password: secret
            """))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("username");
    }

    @Test
    void allowsPasswordlessBotWhenAuthenticationIsDisabled() {
        BotPluginConfig config = parse("""
            bots:
              NoAuth:
                username: AFK_NoAuth
                auth:
                  mode: NONE
            """);

        assertThat(config.bots().get("noauth").auth().mode()).isEqualTo(BotPluginConfig.AuthMode.NONE);
    }

    @Test
    void rejectsTargetServerWithoutSwitchCommand() {
        assertThatThrownBy(() -> parse("""
            bots:
              Broken:
                username: AFK_Broken
                auth:
                  mode: NONE
                target-server: survival
                server-switch-command: ""
            """))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("server-switch-command");
    }

    @Test
    void rejectsBotCountAboveConfiguredSafetyLimit() {
        assertThatThrownBy(() -> parse("""
            runtime:
              maximum-bots: 1
            bots:
              One:
                username: AFK_One
                auth:
                  mode: NONE
              Two:
                username: AFK_Two
                auth:
                  mode: NONE
            """))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("maximum-bots");
    }

    @Test
    void rejectsBotIdsThatOnlyDifferByCase() {
        assertThatThrownBy(() -> parse("""
            bots:
              Farm:
                username: AFK_One
                auth:
                  mode: NONE
              farm:
                username: AFK_Two
                auth:
                  mode: NONE
            """))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("ignoring case");
    }

    @Test
    void inheritsTemplatesAndLoadsPasswordFromSecretsFile(@TempDir Path directory) throws IOException {
        Files.writeString(directory.resolve("config.yml"), """
            templates:
              farm-auth:
                groups: [farm]
                tags: [backup, afk]
                protocol-version: 26.2
                auth:
                  mode: LOGIN
                  login-command: login {password}
            bots:
              Farm01:
                template: farm-auth
                username: AFK_Farm01
                password-secret: farm01
            """);
        Files.writeString(directory.resolve("secrets.yml"), """
            passwords:
              farm01: secret-from-file
            """);

        BotPluginConfig config = ConfigLoader.load(directory, Map.of());
        BotPluginConfig.BotDefinition bot = config.bots().get("farm01");

        assertThat(bot.password()).isEqualTo("secret-from-file");
        assertThat(bot.groups()).containsExactly("farm");
        assertThat(bot.tags()).containsExactly("backup", "afk");
        assertThat(bot.protocolOverride().fixedVersion().protocolId()).isEqualTo(776);
        assertThat(bot.templateName()).isEqualTo("farm-auth");
    }

    @Test
    void parsesBehaviorConfigurationFromTemplate() {
        BotPluginConfig config = parse("""
            templates:
              active-farm:
                behavior:
                  enabled: true
                  mode: FARM
                  interval-ms: 1000
                  movement-radius: 2.5
                  yaw-step: 30
                  random-yaw: true
                  jump: true
                  swing: true
                  sneak: true
                  path:
                    - {x: 10, y: 65, z: -10}
                  server-cycle: [lobby, survival]
                  server-cycle-every: 3
            bots:
              Farm01:
                template: active-farm
                username: AFK_Farm01
                password: secret
            """);

        BotPluginConfig.BehaviorConfig behavior = config.bots().get("farm01").behavior();
        assertThat(behavior.enabled()).isTrue();
        assertThat(behavior.mode()).isEqualTo(BotPluginConfig.BehaviorMode.FARM);
        assertThat(behavior.intervalMillis()).isEqualTo(1_000L);
        assertThat(behavior.movementRadius()).isEqualTo(2.5D);
        assertThat(behavior.yawStep()).isEqualTo(30.0F);
        assertThat(behavior.randomYaw()).isTrue();
        assertThat(behavior.jump()).isTrue();
        assertThat(behavior.swing()).isTrue();
        assertThat(behavior.sneak()).isTrue();
        assertThat(behavior.path()).containsExactly(new BotPluginConfig.BehaviorPoint(10, 65, -10));
        assertThat(behavior.serverCycle()).containsExactly("lobby", "survival");
        assertThat(behavior.serverCycleEvery()).isEqualTo(3);
    }

    @Test
    void rejectsAmbiguousPasswordSources() {
        assertThatThrownBy(() -> parse("""
            bots:
              ambiguous:
                username: AFK_Ambiguous
                password: secret
                password-env: BOTS4VELO_PASSWORD
            """))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("only one of password");
    }

    @Test
    void parsesRecurringOperationalSchedules() {
        BotPluginConfig config = parse("""
            runtime:
              schedules:
                - id: restart-farm
                  action: reconnect
                  selector: "@group:farm"
                  initial-delay-ms: 500
                  interval-ms: 60000
                - id: lobby-rotation
                  action: server
                  selector: "@tag:lobby"
                  server: survival
                  interval-ms: 120000
              presence-rules:
                - id: keep-lobby-warm
                  server: lobby
                  selector: "@group:lobby"
                  minimum-bots: 1
                  maximum-humans: 0
                  interval-ms: 30000
            bots:
              Farm:
                username: AFK_Farm
                password: secret
            """);

        assertThat(config.runtime().schedules()).hasSize(2);
        assertThat(config.runtime().schedules().getFirst().selector()).isEqualTo("@group:farm");
        assertThat(config.runtime().schedules().get(1).server()).isEqualTo("survival");
        assertThat(config.runtime().presenceRules()).singleElement().satisfies(rule -> {
            assertThat(rule.server()).isEqualTo("lobby");
            assertThat(rule.minimumBots()).isOne();
        });
    }

    private static BotPluginConfig parse(String yaml) {
        return ConfigLoader.parse(new ByteArrayInputStream(yaml.getBytes(StandardCharsets.UTF_8)));
    }
}
