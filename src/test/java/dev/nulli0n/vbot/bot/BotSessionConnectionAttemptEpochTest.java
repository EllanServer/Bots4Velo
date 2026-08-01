package dev.nulli0n.vbot.bot;

import dev.nulli0n.vbot.config.BotPluginConfig;
import dev.nulli0n.vbot.protocol.DetectedProtocol;
import dev.nulli0n.vbot.protocol.ProtocolResolver;
import dev.nulli0n.vbot.protocol.ProtocolSelection;
import dev.nulli0n.vbot.protocol.ProtocolVersion;
import dev.nulli0n.vbot.protocol.TransportRegistry;
import dev.nulli0n.vbot.transport.BotPosition;
import dev.nulli0n.vbot.transport.BotTransport;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

class BotSessionConnectionAttemptEpochTest {
    @Test
    void cancelledRunningAttemptCannotClearOrExecuteItsReplacement() throws Exception {
        AttemptBarrierExecutor executor = new AttemptBarrierExecutor();
        CountDownLatch detectionStarted = new CountDownLatch(1);
        CountDownLatch releaseDetection = new CountDownLatch(1);
        AtomicInteger detections = new AtomicInteger();
        BotPluginConfig.ProxyEndpoint endpoint = new BotPluginConfig.ProxyEndpoint(
            "127.0.0.1", 9, "localhost", 9, ProtocolSelection.autoDetect(), 5_000);
        BotPluginConfig.BotDefinition definition = definition();
        ProtocolResolver resolver = new ProtocolResolver(endpoint, definition, (ignored, proxy) -> {
            detections.incrementAndGet();
            detectionStarted.countDown();
            try {
                if (!releaseDetection.await(5, TimeUnit.SECONDS)) {
                    throw new IOException("test did not release protocol detection");
                }
            }
            catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
                throw new IOException("protocol detection interrupted", exception);
            }
            return new DetectedProtocol("1.16.5", ProtocolVersion.MINECRAFT_1_16_5.protocolId(), "test");
        });
        BotSession session = new BotSession(definition, endpoint, runtime(), resolver,
            new TransportRegistry(), new ConnectionRateLimiter(0L), executor,
            LoggerFactory.getLogger(BotSessionConnectionAttemptEpochTest.class));
        try {
            ConnectionAttemptSnapshot replacement;
            synchronized (session) {
                session.start();
                assertThat(executor.firstEntered.await(5, TimeUnit.SECONDS)).isTrue();

                // The first runnable is already executing but cannot acquire
                // the session monitor. cancel(false) cannot make it disappear.
                session.reconnectNow();
                replacement = session.nextConnectionAttempt().orElseThrow();
                assertThat(replacement.kind()).isEqualTo(ActivationKind.RECONNECT);
            }

            assertThat(executor.firstFinished.await(5, TimeUnit.SECONDS)).isTrue();
            assertThat(executor.secondEntered.await(5, TimeUnit.SECONDS)).isTrue();
            assertThat(session.nextConnectionAttempt()).contains(replacement);
            assertThat(detections).hasValue(0);

            executor.releaseSecond.countDown();
            assertThat(detectionStarted.await(5, TimeUnit.SECONDS)).isTrue();
            assertThat(session.nextConnectionAttempt()).isEmpty();
            assertThat(detections).hasValue(1);

            // Retire the valid replacement while it is in protocol detection.
            // It must not reach transport creation after the detector returns.
            session.stop();
            releaseDetection.countDown();
            executor.submit(() -> { }).get(5, TimeUnit.SECONDS);

            assertThat(session.snapshot().state()).isEqualTo(BotState.STOPPED);
            assertThat(session.nextConnectionAttempt()).isEmpty();
            assertThat(detections).hasValue(1);
        }
        finally {
            executor.releaseSecond.countDown();
            releaseDetection.countDown();
            session.stop();
            executor.shutdownNow();
        }
    }

    @Test
    void queuedAfterLoginCommandCannotCrossIntoReplacementTransport() throws Exception {
        assertOldGenerationCommandCannotCrossTransport(CommandEntryPoint.AFTER_LOGIN);
    }

    @Test
    void serverSwitchCommandCannotCrossIntoReplacementTransport() throws Exception {
        assertOldGenerationCommandCannotCrossTransport(CommandEntryPoint.SERVER_SWITCH);
    }

    private static void assertOldGenerationCommandCannotCrossTransport(CommandEntryPoint entryPoint)
        throws Exception {
        ScheduledExecutorService executor = Executors.newSingleThreadScheduledExecutor();
        BotSession session = raceSession(executor);
        CountDownLatch commandEntered = new CountDownLatch(1);
        CountDownLatch releaseCommand = new CountDownLatch(1);
        BlockingCommandTransport oldTransport = new BlockingCommandTransport(commandEntered, releaseCommand);
        RecordingTransport replacementTransport = new RecordingTransport();
        AtomicReference<Throwable> entryFailure = new AtomicReference<>();
        AtomicReference<Throwable> disconnectFailure = new AtomicReference<>();
        CountDownLatch entryFinished = new CountDownLatch(1);
        CountDownLatch disconnectStarted = new CountDownLatch(1);
        CountDownLatch disconnectFinished = new CountDownLatch(1);
        try {
            synchronized (session) {
                manualStop(session).set(false);
                state(session).set(BotState.PLAY);
                setTransport(session, oldTransport);
                if (entryPoint == CommandEntryPoint.SERVER_SWITCH) {
                    serverSwitchPending(session).set(true);
                }
            }

            Thread entry = new Thread(() -> {
                try {
                    invokeCommandEntry(session, entryPoint, 0L);
                }
                catch (Throwable failure) {
                    entryFailure.set(failure);
                }
                finally {
                    entryFinished.countDown();
                }
            }, "bot-session-old-command-" + entryPoint.name().toLowerCase());
            entry.start();
            assertThat(commandEntered.await(2, TimeUnit.SECONDS)).isTrue();

            // Keep reconnection out of this focused race. onDisconnected still
            // retires generation 0 and clears the old transport.
            manualStop(session).set(true);
            Thread disconnect = new Thread(() -> {
                disconnectStarted.countDown();
                try {
                    invokeDisconnected(session, 0L, "old transport closed");
                }
                catch (Throwable failure) {
                    disconnectFailure.set(failure);
                }
                finally {
                    disconnectFinished.countDown();
                }
            }, "bot-session-command-disconnect-" + entryPoint.name().toLowerCase());
            disconnect.start();
            assertThat(disconnectStarted.await(2, TimeUnit.SECONDS)).isTrue();
            awaitBlocked(disconnect);
            assertThat(disconnectFinished.getCount()).isEqualTo(1L);

            releaseCommand.countDown();
            assertThat(entryFinished.await(2, TimeUnit.SECONDS)).isTrue();
            assertThat(disconnectFinished.await(2, TimeUnit.SECONDS)).isTrue();
            assertThat(entryFailure.get()).isNull();
            assertThat(disconnectFailure.get()).isNull();

            synchronized (session) {
                setTransport(session, replacementTransport);
                state(session).set(BotState.PLAY);
                manualStop(session).set(false);
            }

            // Exercise the entry explicitly after installing generation 1;
            // sendWhenPlayable also has a generation-0 retry queued when its
            // blocked send returned false.
            invokeCommandEntry(session, entryPoint, 0L);
            CountDownLatch retryWindowElapsed = new CountDownLatch(1);
            executor.schedule(retryWindowElapsed::countDown, 350L, TimeUnit.MILLISECONDS);
            assertThat(retryWindowElapsed.await(2, TimeUnit.SECONDS)).isTrue();

            assertThat(oldTransport.commands).containsExactly(entryPoint.expectedCommand);
            assertThat(replacementTransport.commands).isEmpty();
        }
        finally {
            releaseCommand.countDown();
            session.stop();
            executor.shutdownNow();
        }
    }

    private static BotSession raceSession(ScheduledExecutorService executor) {
        BotPluginConfig.ProxyEndpoint endpoint = new BotPluginConfig.ProxyEndpoint(
            "127.0.0.1", 9, "localhost", 9,
            ProtocolSelection.fixed(ProtocolVersion.MINECRAFT_1_16_5), 100);
        BotPluginConfig.BotDefinition definition = definition();
        ProtocolResolver resolver = new ProtocolResolver(endpoint, definition,
            (ignoredDefinition, ignoredEndpoint) -> null);
        return new BotSession(definition, endpoint, runtime(), resolver,
            new TransportRegistry(), new ConnectionRateLimiter(0L), executor,
            LoggerFactory.getLogger(BotSessionConnectionAttemptEpochTest.class));
    }

    private static void invokeCommandEntry(BotSession session, CommandEntryPoint entryPoint,
                                           long generation) throws Exception {
        if (entryPoint == CommandEntryPoint.SERVER_SWITCH) {
            Method method = BotSession.class.getDeclaredMethod("attemptServerSwitch", long.class);
            method.setAccessible(true);
            method.invoke(session, generation);
            return;
        }
        Method method = BotSession.class.getDeclaredMethod(
            "sendWhenPlayable", long.class, String.class, int.class);
        method.setAccessible(true);
        method.invoke(session, generation, entryPoint.expectedCommand, 0);
    }

    private static void invokeDisconnected(BotSession session, long generation, String reason)
        throws Exception {
        Method method = BotSession.class.getDeclaredMethod(
            "onDisconnected", long.class, String.class, Throwable.class);
        method.setAccessible(true);
        method.invoke(session, generation, reason, null);
    }

    private static void awaitBlocked(Thread thread) {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2);
        while (thread.getState() != Thread.State.BLOCKED && System.nanoTime() < deadline) {
            Thread.onSpinWait();
        }
        assertThat(thread.getState()).isEqualTo(Thread.State.BLOCKED);
    }

    private static void setTransport(BotSession session, BotTransport transport) throws Exception {
        Field field = BotSession.class.getDeclaredField("transport");
        field.setAccessible(true);
        field.set(session, transport);
    }

    @SuppressWarnings("unchecked")
    private static AtomicReference<BotState> state(BotSession session) throws Exception {
        Field field = BotSession.class.getDeclaredField("state");
        field.setAccessible(true);
        return (AtomicReference<BotState>) field.get(session);
    }

    private static AtomicBoolean manualStop(BotSession session) throws Exception {
        Field field = BotSession.class.getDeclaredField("manualStop");
        field.setAccessible(true);
        return (AtomicBoolean) field.get(session);
    }

    private static AtomicBoolean serverSwitchPending(BotSession session) throws Exception {
        Field field = BotSession.class.getDeclaredField("serverSwitchPending");
        field.setAccessible(true);
        return (AtomicBoolean) field.get(session);
    }

    private static BotPluginConfig.RuntimeConfig runtime() {
        BotPluginConfig.ReconnectConfig reconnect = new BotPluginConfig.ReconnectConfig(
            1_000L, 10_000L, 2.0D, 0.0D, 3);
        return new BotPluginConfig.RuntimeConfig(0L, 0L, 10, 100L, 0L,
            BotPluginConfig.ResourcePackMode.DECLINE, false, reconnect);
    }

    private static BotPluginConfig.BotDefinition definition() {
        BotPluginConfig.AuthConfig auth = new BotPluginConfig.AuthConfig(
            BotPluginConfig.AuthMode.NONE, "", "", 0L, 0L, 0L,
            List.of(), List.of(), List.of());
        return new BotPluginConfig.BotDefinition(
            "epoch", true, "AFK_Epoch", "", "lobby", "", 2,
            auth, "server {server}", 1_000L, 0, List.of());
    }

    private enum CommandEntryPoint {
        AFTER_LOGIN("say stale"),
        SERVER_SWITCH("server lobby");

        private final String expectedCommand;

        CommandEntryPoint(String expectedCommand) {
            this.expectedCommand = expectedCommand;
        }
    }

    private static final class BlockingCommandTransport implements BotTransport {
        private final CountDownLatch commandEntered;
        private final CountDownLatch releaseCommand;
        private final List<String> commands = new CopyOnWriteArrayList<>();

        private BlockingCommandTransport(CountDownLatch commandEntered, CountDownLatch releaseCommand) {
            this.commandEntered = commandEntered;
            this.releaseCommand = releaseCommand;
        }

        @Override
        public void connect() {
        }

        @Override
        public void disconnect(String reason) {
        }

        @Override
        public boolean isConnected() {
            return true;
        }

        @Override
        public boolean sendCommand(String command) {
            commands.add(command);
            commandEntered.countDown();
            try {
                releaseCommand.await(2, TimeUnit.SECONDS);
            }
            catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
            }
            return false;
        }

        @Override
        public boolean moveTo(double x, double y, double z) {
            return false;
        }

        @Override
        public boolean look(float yaw, float pitch) {
            return false;
        }

        @Override
        public BotPosition position() {
            return BotPosition.unknown();
        }
    }

    private static final class RecordingTransport implements BotTransport {
        private final List<String> commands = new CopyOnWriteArrayList<>();

        @Override
        public void connect() {
        }

        @Override
        public void disconnect(String reason) {
        }

        @Override
        public boolean isConnected() {
            return true;
        }

        @Override
        public boolean sendCommand(String command) {
            commands.add(command);
            return true;
        }

        @Override
        public boolean moveTo(double x, double y, double z) {
            return false;
        }

        @Override
        public boolean look(float yaw, float pitch) {
            return false;
        }

        @Override
        public BotPosition position() {
            return BotPosition.unknown();
        }
    }

    private static final class AttemptBarrierExecutor extends ScheduledThreadPoolExecutor {
        private final AtomicInteger attempts = new AtomicInteger();
        private final CountDownLatch firstEntered = new CountDownLatch(1);
        private final CountDownLatch firstFinished = new CountDownLatch(1);
        private final CountDownLatch secondEntered = new CountDownLatch(1);
        private final CountDownLatch releaseSecond = new CountDownLatch(1);

        private AttemptBarrierExecutor() {
            super(1);
            setRemoveOnCancelPolicy(true);
        }

        @Override
        public ScheduledFuture<?> schedule(Runnable command, long delay, TimeUnit unit) {
            int attempt = attempts.incrementAndGet();
            Runnable wrapped = switch (attempt) {
                case 1 -> () -> {
                    firstEntered.countDown();
                    try {
                        command.run();
                    }
                    finally {
                        firstFinished.countDown();
                    }
                };
                case 2 -> () -> {
                    secondEntered.countDown();
                    try {
                        if (!releaseSecond.await(5, TimeUnit.SECONDS)) {
                            throw new AssertionError("test did not release the replacement attempt");
                        }
                    }
                    catch (InterruptedException exception) {
                        Thread.currentThread().interrupt();
                        throw new AssertionError("replacement attempt interrupted", exception);
                    }
                    command.run();
                };
                default -> command;
            };
            return super.schedule(wrapped, delay, unit);
        }
    }
}
