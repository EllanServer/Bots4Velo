package dev.nulli0n.vbot.protocol;

import dev.nulli0n.vbot.config.BotPluginConfig.AuthConfig;
import dev.nulli0n.vbot.config.BotPluginConfig.AuthMode;
import dev.nulli0n.vbot.config.BotPluginConfig.BotDefinition;
import dev.nulli0n.vbot.config.BotPluginConfig.ProxyEndpoint;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
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

    @Test
    void invalidationRetiresAnInFlightDetectionWithoutRestoringItsCacheOrSource() throws Exception {
        AtomicInteger calls = new AtomicInteger();
        CountDownLatch oldDetectionStarted = new CountDownLatch(1);
        CountDownLatch releaseOldDetection = new CountDownLatch(1);
        ProtocolResolver resolver = new ProtocolResolver(endpoint(ProtocolSelection.autoDetect()), bot(),
            (definition, endpoint) -> {
                int call = calls.incrementAndGet();
                if (call == 1) {
                    oldDetectionStarted.countDown();
                    await(releaseOldDetection);
                    // This retired result is deliberately unsupported: epoch
                    // validation must discard it before protocol validation.
                    return new DetectedProtocol("unsupported-old", 999, "old-detection");
                }
                return new DetectedProtocol("1.21.11", 774, "new-detection");
            });
        ExecutorService workers = Executors.newFixedThreadPool(2);
        try {
            Future<ProtocolVersion> oldResolve = workers.submit(resolver::resolve);
            assertThat(oldDetectionStarted.await(2, TimeUnit.SECONDS)).isTrue();

            resolver.invalidateAutomaticDetection();
            Future<ProtocolVersion> newResolve = workers.submit(resolver::resolve);
            assertThat(newResolve.get(2, TimeUnit.SECONDS)).isEqualTo(ProtocolVersion.MINECRAFT_1_21_11);
            assertThat(resolver.source()).isEqualTo("new-detection");

            releaseOldDetection.countDown();
            assertThat(oldResolve.get(2, TimeUnit.SECONDS)).isEqualTo(ProtocolVersion.MINECRAFT_1_21_11);
            assertThat(resolver.resolve()).isEqualTo(ProtocolVersion.MINECRAFT_1_21_11);
            assertThat(resolver.source()).isEqualTo("new-detection");
            assertThat(calls).hasValue(2);
        }
        finally {
            releaseOldDetection.countDown();
            workers.shutdownNow();
        }
    }

    @Test
    void concurrentDetectionLoserCannotOverwriteWinnerSource() throws Exception {
        AtomicInteger calls = new AtomicInteger();
        CountDownLatch slowDetectionStarted = new CountDownLatch(1);
        CountDownLatch releaseSlowDetection = new CountDownLatch(1);
        ProtocolResolver resolver = new ProtocolResolver(endpoint(ProtocolSelection.autoDetect()), bot(),
            (definition, endpoint) -> {
                int call = calls.incrementAndGet();
                if (call == 1) {
                    slowDetectionStarted.countDown();
                    await(releaseSlowDetection);
                    return new DetectedProtocol("1.16.5",
                        ProtocolVersion.MINECRAFT_1_16_5.protocolId(), "slow-loser");
                }
                return new DetectedProtocol("1.21.11", 774, "fast-winner");
            });
        ExecutorService workers = Executors.newFixedThreadPool(2);
        try {
            Future<ProtocolVersion> slowResolve = workers.submit(resolver::resolve);
            assertThat(slowDetectionStarted.await(2, TimeUnit.SECONDS)).isTrue();
            Future<ProtocolVersion> fastResolve = workers.submit(resolver::resolve);

            assertThat(fastResolve.get(2, TimeUnit.SECONDS)).isEqualTo(ProtocolVersion.MINECRAFT_1_21_11);
            releaseSlowDetection.countDown();
            assertThat(slowResolve.get(2, TimeUnit.SECONDS)).isEqualTo(ProtocolVersion.MINECRAFT_1_21_11);
            assertThat(resolver.source()).isEqualTo("fast-winner");
            assertThat(calls).hasValue(2);
        }
        finally {
            releaseSlowDetection.countDown();
            workers.shutdownNow();
        }
    }

    private static void await(CountDownLatch latch) throws IOException {
        try {
            if (!latch.await(2, TimeUnit.SECONDS)) {
                throw new IOException("test detector barrier timed out");
            }
        }
        catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new IOException("test detector barrier interrupted", exception);
        }
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
