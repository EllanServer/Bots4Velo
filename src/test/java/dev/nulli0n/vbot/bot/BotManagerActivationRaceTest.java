package dev.nulli0n.vbot.bot;

import dev.nulli0n.vbot.config.BotPluginConfig;
import dev.nulli0n.vbot.protocol.ProtocolSelection;
import dev.nulli0n.vbot.protocol.ProtocolVersion;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;

import java.time.Clock;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class BotManagerActivationRaceTest {
    @Test
    void stopPermanentlyInvalidatesAnActivationThatAlreadyPassedPreflight() throws Exception {
        DispatchBarrier barrier = new DispatchBarrier();
        try (BotManager manager = manager(barrier)) {
            AtomicInteger starts = new AtomicInteger();
            manager.addEventListener(event -> {
                if (event.type().equals("START_REQUESTED")) {
                    starts.incrementAndGet();
                }
            });

            assertThat(manager.start("Farm01")).isTrue();
            assertThat(barrier.entered.await(5, TimeUnit.SECONDS)).isTrue();

            assertThat(manager.stop("Farm01")).isTrue();
            barrier.release.countDown();
            assertThat(barrier.finished.await(5, TimeUnit.SECONDS)).isTrue();

            BotSession session = manager.find("Farm01").orElseThrow();
            assertThat(starts).hasValue(0);
            assertThat(session.snapshot().state()).isEqualTo(BotState.STOPPED);
            assertThat(session.nextConnectionAttempt()).isEmpty();
        }
    }

    @Test
    void holdThenResumeCannotReviveAnOlderActivation() throws Exception {
        DispatchBarrier barrier = new DispatchBarrier();
        try (BotManager manager = manager(barrier)) {
            assertThat(manager.start("Farm01")).isTrue();
            assertThat(barrier.entered.await(5, TimeUnit.SECONDS)).isTrue();

            assertThat(manager.hold("Farm01", "maintenance")).isTrue();
            assertThat(manager.resume("Farm01")).isTrue();
            barrier.release.countDown();
            assertThat(barrier.finished.await(5, TimeUnit.SECONDS)).isTrue();

            BotSession session = manager.find("Farm01").orElseThrow();
            assertThat(session.snapshot().state()).isEqualTo(BotState.STOPPED);
            assertThat(session.nextConnectionAttempt()).isEmpty();
        }
    }

    @Test
    void olderActivationCleanupCannotStopANewerReplacement() throws Exception {
        ReplacementBarrier barrier = new ReplacementBarrier();
        try (BotManager manager = manager(barrier::observe)) {
            assertThat(manager.reconnect("Farm01")).isTrue();
            assertThat(barrier.firstCompletionEntered.await(5, TimeUnit.SECONDS)).isTrue();

            assertThat(manager.reconnect("Farm01")).isTrue();
            assertThat(barrier.secondCompletion.await(5, TimeUnit.SECONDS)).isTrue();
            assertThat(manager.find("Farm01").orElseThrow().nextConnectionAttempt()).isPresent();

            barrier.releaseFirstCompletion.countDown();
            assertThat(barrier.firstCompletionReleased.await(5, TimeUnit.SECONDS)).isTrue();
            Thread.sleep(100);

            BotSession current = manager.find("Farm01").orElseThrow();
            assertThat(current.snapshot().state()).isEqualTo(BotState.RECONNECT_WAIT);
            assertThat(current.nextConnectionAttempt()).isPresent();
        }
    }

    @Test
    void closeDoesNotWaitForBlockedProtocolDetection() throws Exception {
        CountDownLatch detectionStarted = new CountDownLatch(1);
        BotManager manager = new BotManager(autoProtocolConfig(),
            LoggerFactory.getLogger(BotManagerActivationRaceTest.class), (definition, endpoint) -> {
                detectionStarted.countDown();
                try {
                    Thread.sleep(30_000);
                }
                catch (InterruptedException exception) {
                    Thread.currentThread().interrupt();
                    throw new java.io.IOException("interrupted", exception);
                }
                throw new java.io.IOException("unexpected completion");
            }, Clock.systemUTC());
        manager.find("Farm01").orElseThrow().start();
        assertThat(detectionStarted.await(5, TimeUnit.SECONDS)).isTrue();

        long startedAt = System.nanoTime();
        manager.close();

        assertThat(Duration.ofNanos(System.nanoTime() - startedAt)).isLessThan(Duration.ofSeconds(1));
    }

    @Test
    void closedManagerRejectsCreateWithoutRetainingTheSession() {
        BotManager manager = manager((ignoredId, ignoredComplete) -> { });
        BotPluginConfig.BotDefinition definition = new BotPluginConfig.BotDefinition(
            "Dynamic01", true, "AFK_Dynamic01", "", "lobby", "", 2,
            config().bots().get("farm01").auth(), "server {server}", 1_000, 0, List.of());
        manager.close();

        assertThatThrownBy(() -> manager.create(definition))
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("not accepting");
        assertThat(manager.find("Dynamic01")).isEmpty();
    }

    @Test
    void createEnforcesCaseInsensitiveUsernameUniquenessInsideManagerLock() {
        try (BotManager manager = manager((ignoredId, ignoredComplete) -> { })) {
            BotPluginConfig.BotDefinition duplicateUsername = new BotPluginConfig.BotDefinition(
                "Dynamic02", true, "afk_farm01", "", "lobby", "", 2,
                config().bots().get("farm01").auth(), "server {server}", 1_000, 0, List.of());

            assertThat(manager.create(duplicateUsername)).isEqualTo(BotManager.CreateResult.ALREADY_EXISTS);
            assertThat(manager.find("Dynamic02")).isEmpty();
        }
    }

    private static BotManager manager(DispatchBarrier barrier) {
        return manager(barrier::observe);
    }

    private static BotManager manager(java.util.function.BiConsumer<String, Boolean> observer) {
        return new BotManager(config(), LoggerFactory.getLogger(BotManagerActivationRaceTest.class),
            (definition, endpoint) -> null, Clock.systemUTC(), observer);
    }

    private static BotPluginConfig config() {
        BotPluginConfig.ProxyEndpoint proxy = new BotPluginConfig.ProxyEndpoint(
            "127.0.0.1", 9, "localhost", 9,
            ProtocolSelection.fixed(ProtocolVersion.MINECRAFT_1_16_5), 100);
        BotPluginConfig.ReconnectConfig reconnect = new BotPluginConfig.ReconnectConfig(
            1_000, 10_000, 2.0D, 0.0D, 3);
        BotPluginConfig.RuntimeConfig runtime = new BotPluginConfig.RuntimeConfig(
            0, 0, 10, 100, 0,
            BotPluginConfig.ResourcePackMode.DECLINE, false, reconnect);
        BotPluginConfig.AuthConfig auth = new BotPluginConfig.AuthConfig(
            BotPluginConfig.AuthMode.NONE, "", "", 0, 0, 0,
            List.of(), List.of(), List.of());
        BotPluginConfig.BotDefinition bot = new BotPluginConfig.BotDefinition(
            "Farm01", true, "AFK_Farm01", "", "lobby", "", 2,
            auth, "server {server}", 1_000, 0, List.of());
        return new BotPluginConfig(proxy, runtime, Map.of("farm01", bot));
    }

    private static BotPluginConfig autoProtocolConfig() {
        BotPluginConfig base = config();
        BotPluginConfig.ProxyEndpoint proxy = new BotPluginConfig.ProxyEndpoint(
            base.proxy().address(), base.proxy().port(), base.proxy().virtualHost(),
            base.proxy().virtualPort(), ProtocolSelection.autoDetect(),
            base.proxy().protocolDetectionTimeoutMillis());
        return new BotPluginConfig(proxy, base.runtime(), base.bots());
    }

    private static final class DispatchBarrier {
        private final CountDownLatch entered = new CountDownLatch(1);
        private final CountDownLatch release = new CountDownLatch(1);
        private final CountDownLatch finished = new CountDownLatch(1);

        private void observe(String ignoredId, boolean complete) {
            if (complete) {
                finished.countDown();
                return;
            }
            entered.countDown();
            try {
                if (!release.await(5, TimeUnit.SECONDS)) {
                    throw new AssertionError("Timed out waiting to release activation dispatch");
                }
            }
            catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
                throw new AssertionError("Activation dispatch was interrupted", exception);
            }
        }
    }

    private static final class ReplacementBarrier {
        private final AtomicInteger completions = new AtomicInteger();
        private final CountDownLatch firstCompletionEntered = new CountDownLatch(1);
        private final CountDownLatch releaseFirstCompletion = new CountDownLatch(1);
        private final CountDownLatch firstCompletionReleased = new CountDownLatch(1);
        private final CountDownLatch secondCompletion = new CountDownLatch(1);

        private void observe(String ignoredId, boolean complete) {
            if (!complete) {
                return;
            }
            int completion = completions.incrementAndGet();
            if (completion == 2) {
                secondCompletion.countDown();
                return;
            }
            if (completion != 1) {
                return;
            }
            firstCompletionEntered.countDown();
            try {
                if (!releaseFirstCompletion.await(5, TimeUnit.SECONDS)) {
                    throw new AssertionError("Timed out waiting to release old activation cleanup");
                }
            }
            catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
                throw new AssertionError("Old activation cleanup was interrupted", exception);
            }
            finally {
                firstCompletionReleased.countDown();
            }
        }
    }
}
