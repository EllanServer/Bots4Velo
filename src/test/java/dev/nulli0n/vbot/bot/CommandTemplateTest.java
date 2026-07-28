package dev.nulli0n.vbot.bot;

import dev.nulli0n.vbot.config.BotPluginConfig.AuthConfig;
import dev.nulli0n.vbot.config.BotPluginConfig.AuthMode;
import dev.nulli0n.vbot.config.BotPluginConfig.BotDefinition;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class CommandTemplateTest {
    @Test
    void rendersBotVariablesAndRemovesLeadingSlash() {
        AuthConfig auth = new AuthConfig(AuthMode.AUTO, "login {password}", "register {password} {password}",
            1, 1, 1, List.of(), List.of(), List.of());
        BotDefinition bot = new BotDefinition("iron", true, "AFK_Iron", "s3cret", "survival", "survival", 2,
            auth, "server {server}", 1, 0, List.of());

        assertThat(CommandTemplate.render("/msg {username} {password} {server}", bot))
            .isEqualTo("msg AFK_Iron s3cret survival");
    }
}
