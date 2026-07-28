package dev.nulli0n.vbot.protocol;

import dev.nulli0n.vbot.config.BotPluginConfig.AuthConfig;
import dev.nulli0n.vbot.config.BotPluginConfig.AuthMode;
import dev.nulli0n.vbot.config.BotPluginConfig.BotDefinition;
import dev.nulli0n.vbot.config.BotPluginConfig.ProxyEndpoint;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

class ProtocolResolverTest {
    @Test
    void cachesBackendDetectionAndReportsItsSource() throws Exception {
        AtomicInteger calls = new AtomicInteger();
        ProtocolResolver resolver = new ProtocolResolver(endpoint(ProtocolSelection.autoDetect()), bot(),
            (definition, endpoint) -> {
                calls.incrementAndGet();
                return new DetectedProtocol("1.21.11", 774, "backend:survival");
            });

        assertThat(resolver.resolve()).isEqualTo(ProtocolVersion.MINECRAFT_1_21_11);
        assertThat(resolver.resolve()).isEqualTo(ProtocolVersion.MINECRAFT_1_21_11);
        assertThat(resolver.source()).isEqualTo("backend:survival");
        assertThat(calls).hasValue(1);

        resolver.invalidateAutomaticDetection();
        assertThat(resolver.resolve()).isEqualTo(ProtocolVersion.MINECRAFT_1_21_11);
        assertThat(calls).hasValue(2);
    }

    @Test
    void fixedSelectionDoesNotCallDetector() throws Exception {
        AtomicInteger calls = new AtomicInteger();
        ProtocolResolver resolver = new ProtocolResolver(
            endpoint(ProtocolSelection.fixed(ProtocolVersion.MINECRAFT_1_16_5)), bot(),
            (definition, endpoint) -> {
                calls.incrementAndGet();
                return new DetectedProtocol("unexpected", 776, "test");
            });

        assertThat(resolver.resolve()).isEqualTo(ProtocolVersion.MINECRAFT_1_16_5);
        assertThat(resolver.source()).isEqualTo("manual-config");
        assertThat(calls).hasValue(0);
    }

    private static ProxyEndpoint endpoint(ProtocolSelection selection) {
        return new ProxyEndpoint("127.0.0.1", 25565, "localhost", 25565, selection, 3_000);
    }

    private static BotDefinition bot() {
        AuthConfig auth = new AuthConfig(AuthMode.NONE, "", "", 0, 0, 0,
            List.of(), List.of(), List.of());
        return new BotDefinition("test", true, "AFK_Test", "", "survival", "", 2,
            auth, "server {server}", 1_000, 0, List.of());
    }
}
