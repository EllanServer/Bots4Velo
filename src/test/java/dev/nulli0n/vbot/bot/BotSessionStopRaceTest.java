package dev.nulli0n.vbot.bot;

import dev.nulli0n.vbot.config.BotPluginConfig;
import dev.nulli0n.vbot.protocol.DetectedProtocol;
import dev.nulli0n.vbot.protocol.ProtocolResolver;
import dev.nulli0n.vbot.protocol.ProtocolSelection;
import dev.nulli0n.vbot.protocol.ProtocolVersion;
import dev.nulli0n.vbot.protocol.TransportRegistry;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

class BotSessionStopRaceTest {
    @Test
    void stopDuringProtocolDetectionCannotReviveTheConnection() throws Exception {
        ScheduledExecutorService executor = Executors.newSingleThreadScheduledExecutor();
        CountDownLatch detectionStarted = new CountDownLatch(1);
        CountDownLatch releaseDetection = new CountDownLatch(1);
        BotPluginConfig.ProxyEndpoint endpoint = new BotPluginConfig.ProxyEndpoint(
            "127.0.0.1", 9, "localhost", 9, ProtocolSelection.autoDetect(), 5_000);
        BotPluginConfig.BotDefinition definition = definition();
        ProtocolResolver resolver = new ProtocolResolver(endpoint, definition, (ignored, proxy) -> {
            detectionStarted.countDown();
            try {
                if (!releaseDetection.await(5, TimeUnit.SECONDS)) {
                    throw new IOException("test did not release protocol detection");
                }
            }
            catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
                throw new IOException("protocol detection test interrupted", exception);
            }
            return new DetectedProtocol("1.16.5", ProtocolVersion.MINECRAFT_1_16_5.protocolId(), "test");
        });
        BotSession session = new BotSession(definition, endpoint, runtime(), resolver,
            new TransportRegistry(), new ConnectionRateLimiter(0), executor,
            LoggerFactory.getLogger(BotSessionStopRaceTest.class));
        try {
            session.start();
            assertThat(detectionStarted.await(5, TimeUnit.SECONDS)).isTrue();

            session.stop();
            releaseDetection.countDown();
            executor.submit(() -> { }).get(5, TimeUnit.SECONDS);

            assertThat(session.snapshot().state()).isEqualTo(BotState.STOPPED);
            assertThat(session.nextConnectionAttemptAt()).isEmpty();
        }
        finally {
            releaseDetection.countDown();
            session.stop();
            executor.shutdownNow();
        }
    }

    private static BotPluginConfig.RuntimeConfig runtime() {
        BotPluginConfig.ReconnectConfig reconnect = new BotPluginConfig.ReconnectConfig(
            1_000, 10_000, 2.0D, 0.0D, 3);
        return new BotPluginConfig.RuntimeConfig(0, 0, 10, 100, 0,
            BotPluginConfig.ResourcePackMode.DECLINE, false, reconnect);
    }

    private static BotPluginConfig.BotDefinition definition() {
        BotPluginConfig.AuthConfig auth = new BotPluginConfig.AuthConfig(
            BotPluginConfig.AuthMode.NONE, "", "", 0, 0, 0,
            List.of(), List.of(), List.of());
        return new BotPluginConfig.BotDefinition(
            "race", true, "AFK_Race", "", "", "", 2,
            auth, "server {server}", 1_000, 0, List.of());
    }
}
