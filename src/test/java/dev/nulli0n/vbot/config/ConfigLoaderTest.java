package dev.nulli0n.vbot.config;

import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;

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

    private static BotPluginConfig parse(String yaml) {
        return ConfigLoader.parse(new ByteArrayInputStream(yaml.getBytes(StandardCharsets.UTF_8)));
    }
}
