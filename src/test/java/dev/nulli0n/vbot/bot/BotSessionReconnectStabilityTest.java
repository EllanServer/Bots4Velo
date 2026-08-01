package dev.nulli0n.vbot.bot;

import dev.nulli0n.vbot.config.BotPluginConfig;
import dev.nulli0n.vbot.protocol.ProtocolResolver;
import dev.nulli0n.vbot.protocol.ProtocolSelection;
import dev.nulli0n.vbot.protocol.ProtocolVersion;
import dev.nulli0n.vbot.protocol.TransportRegistry;
import dev.nulli0n.vbot.transport.BotPosition;
import dev.nulli0n.vbot.transport.BotTransport;
import dev.nulli0n.vbot.transport.TransportState;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

class BotSessionReconnectStabilityTest {
    @Test
    void aShortAuthenticatedPlayDoesNotRenewAnExhaustedReconnectBudget() throws Exception {
        ScheduledExecutorService executor = Executors.newSingleThreadScheduledExecutor();
        BotSession session = session(executor);
        try {
            manualStop(session).set(false);
            setTransport(session, new ConnectedTransport());
            reconnectAttempts(session).set(3);
            stabilityGate(session).connectionStarted(0L);

            invokeTransportState(session, 0L, TransportState.PLAY);
            invokeDisconnected(session, 0L, "short-lived backend admission");

            assertThat(session.snapshot().state()).isEqualTo(BotState.FAILED);
            assertThat(session.snapshot().reconnectAttempts()).isEqualTo(4);
            assertThat(session.nextConnectionAttempt()).isEmpty();
        }
        finally {
            session.stop();
            executor.shutdownNow();
        }
    }

    @Test
    void successfulPromptDoesNotResetBudgetBeforePostAuthenticationContinuation() throws Exception {
        ScheduledExecutorService executor = Executors.newSingleThreadScheduledExecutor();
        BotPluginConfig.AuthConfig auth = new BotPluginConfig.AuthConfig(
            BotPluginConfig.AuthMode.LOGIN, "login {password}", "", 0L, 0L, 250L,
            List.of(), List.of(), List.of("success"), List.of(), 30_000L,
            true, "", BotPluginConfig.RegistrationSecondArgument.AUTO, 0L);
        BotSession session = session(executor, auth, 0);
        try {
            manualStop(session).set(false);
            setTransport(session, new ConnectedTransport());
            reconnectAttempts(session).set(3);
            stabilityGate(session).connectionStarted(0L);

            invokeTransportState(session, 0L, TransportState.PLAY);
            invokeAuthMessage(session, 0L, "success");

            assertThat(session.isAuthenticationComplete()).isFalse();
            assertThat(session.snapshot().reconnectAttempts()).isEqualTo(3);

            long deadline = System.nanoTime() + java.util.concurrent.TimeUnit.SECONDS.toNanos(2);
            while (!session.isAuthenticationComplete() && System.nanoTime() < deadline) {
                Thread.onSpinWait();
            }
            assertThat(session.isAuthenticationComplete()).isTrue();
            while (session.snapshot().reconnectAttempts() != 0 && System.nanoTime() < deadline) {
                Thread.onSpinWait();
            }
            assertThat(session.snapshot().reconnectAttempts()).isZero();
        }
        finally {
            session.stop();
            executor.shutdownNow();
        }
    }

    @Test
    void delayedStableCallbackCannotResetAfterTransportClosesBeforeDisconnectEvent() throws Exception {
        ScheduledExecutorService executor = Executors.newSingleThreadScheduledExecutor();
        BotSession session = session(executor);
        try {
            manualStop(session).set(false);
            ConnectedTransport transport = new ConnectedTransport();
            setTransport(session, transport);
            reconnectAttempts(session).set(3);
            stabilityGate(session).connectionStarted(0L);
            invokeTransportState(session, 0L, TransportState.PLAY);
            assertThat(session.isAuthenticationComplete()).isTrue();

            transport.connected.set(false);
            invokeStableReset(session, 0L);

            assertThat(session.snapshot().state()).isEqualTo(BotState.PLAY);
            assertThat(session.snapshot().reconnectAttempts()).isEqualTo(3);
        }
        finally {
            session.stop();
            executor.shutdownNow();
        }
    }

    @Test
    void callbacksQueuedByDisconnectedTransportCannotRestorePlayOrAuthentication() throws Exception {
        ScheduledExecutorService executor = Executors.newSingleThreadScheduledExecutor();
        BotPluginConfig.AuthConfig auth = new BotPluginConfig.AuthConfig(
            BotPluginConfig.AuthMode.LOGIN, "login {password}", "", 0L, 0L, 0L,
            List.of(), List.of(), List.of("success"), List.of(), 30_000L,
            true, "", BotPluginConfig.RegistrationSecondArgument.AUTO, 0L);
        BotSession session = session(executor, auth, 1);
        try {
            manualStop(session).set(false);
            setTransport(session, new ConnectedTransport());
            stabilityGate(session).connectionStarted(0L);
            invokeTransportState(session, 0L, TransportState.PLAY);
            long playEntries = session.snapshot().playEntries();

            // Prevent a retry so the final state remains deterministic while
            // exercising late events from the retired listener generation.
            manualStop(session).set(true);
            invokeDisconnected(session, 0L, "closed");
            invokeTransportState(session, 0L, TransportState.PLAY);
            invokeAuthMessage(session, 0L, "success");
            invokeAuthenticationTimeout(session, 0L, 100L);

            assertThat(session.snapshot().state()).isEqualTo(BotState.STOPPED);
            assertThat(session.snapshot().playEntries()).isEqualTo(playEntries);
            assertThat(session.isAuthenticationComplete()).isFalse();
            assertThat(authenticationInterventionRequired(session)).isFalse();
        }
        finally {
            session.stop();
            executor.shutdownNow();
        }
    }

    @Test
    void authenticationSendAndDisconnectCannotInterleaveAcrossGenerations() throws Exception {
        ScheduledExecutorService executor = Executors.newSingleThreadScheduledExecutor();
        BotPluginConfig.AuthConfig auth = new BotPluginConfig.AuthConfig(
            BotPluginConfig.AuthMode.LOGIN, "login {password}", "", 0L, 0L, 0L,
            List.of(), List.of(), List.of("success"), List.of(), 30_000L,
            true, "", BotPluginConfig.RegistrationSecondArgument.AUTO, 0L);
        BotSession session = session(executor, auth, 1);
        CountDownLatch commandEntered = new CountDownLatch(1);
        CountDownLatch releaseCommand = new CountDownLatch(1);
        try {
            manualStop(session).set(false);
            setTransport(session, new ConnectedTransport(commandEntered, releaseCommand));
            stabilityGate(session).connectionStarted(0L);
            invokeTransportState(session, 0L, TransportState.PLAY);
            assertThat(commandEntered.await(2, TimeUnit.SECONDS)).isTrue();

            manualStop(session).set(true);
            CountDownLatch disconnectFinished = new CountDownLatch(1);
            AtomicReference<Throwable> disconnectFailure = new AtomicReference<>();
            Thread disconnect = new Thread(() -> {
                try {
                    invokeDisconnected(session, 0L, "closed while login was queued");
                }
                catch (Throwable failure) {
                    disconnectFailure.set(failure);
                }
                finally {
                    disconnectFinished.countDown();
                }
            }, "bot-session-disconnect-race-test");
            disconnect.start();

            // The credential submission owns the session generation until it
            // finishes; disconnect/reset cannot retire it between check/send.
            assertThat(disconnectFinished.await(150, TimeUnit.MILLISECONDS)).isFalse();
            releaseCommand.countDown();
            assertThat(disconnectFinished.await(2, TimeUnit.SECONDS)).isTrue();
            assertThat(disconnectFailure.get()).isNull();
            assertThat(session.snapshot().state()).isEqualTo(BotState.STOPPED);
            assertThat(session.isAuthenticationComplete()).isFalse();
        }
        finally {
            releaseCommand.countDown();
            session.stop();
            executor.shutdownNow();
        }
    }

    private static BotSession session(ScheduledExecutorService executor) {
        BotPluginConfig.AuthConfig auth = new BotPluginConfig.AuthConfig(
            BotPluginConfig.AuthMode.NONE, "", "", 0L, 0L, 0L,
            List.of(), List.of(), List.of());
        return session(executor, auth, 1);
    }

    private static BotSession session(ScheduledExecutorService executor,
                                      BotPluginConfig.AuthConfig auth,
                                      int stableResetSeconds) {
        BotPluginConfig.ProxyEndpoint endpoint = new BotPluginConfig.ProxyEndpoint(
            "127.0.0.1", 9, "localhost", 9,
            ProtocolSelection.fixed(ProtocolVersion.MINECRAFT_1_16_5), 100);
        BotPluginConfig.ReconnectConfig reconnect = new BotPluginConfig.ReconnectConfig(
            0L, 100L, 1.0D, 0.0D, 3, stableResetSeconds);
        BotPluginConfig.RuntimeConfig runtime = new BotPluginConfig.RuntimeConfig(
            0L, 0L, 10, 100L, 0L,
            BotPluginConfig.ResourcePackMode.DECLINE, false, reconnect);
        BotPluginConfig.BotDefinition definition = new BotPluginConfig.BotDefinition(
            "stable", true, "AFK_Stable", "credential", "", "", 2,
            auth, "server {server}", 1_000L, 0, List.of());
        ProtocolResolver resolver = new ProtocolResolver(endpoint, definition,
            (ignoredDefinition, ignoredEndpoint) -> null);
        return new BotSession(definition, endpoint, runtime, resolver, new TransportRegistry(),
            new ConnectionRateLimiter(0L), executor,
            LoggerFactory.getLogger(BotSessionReconnectStabilityTest.class));
    }

    private static void setTransport(BotSession session, BotTransport transport) throws Exception {
        Field field = BotSession.class.getDeclaredField("transport");
        field.setAccessible(true);
        field.set(session, transport);
    }

    private static AtomicInteger reconnectAttempts(BotSession session) throws Exception {
        Field field = BotSession.class.getDeclaredField("reconnectAttempts");
        field.setAccessible(true);
        return (AtomicInteger) field.get(session);
    }

    private static AtomicBoolean manualStop(BotSession session) throws Exception {
        Field field = BotSession.class.getDeclaredField("manualStop");
        field.setAccessible(true);
        return (AtomicBoolean) field.get(session);
    }

    private static boolean authenticationInterventionRequired(BotSession session) throws Exception {
        Field field = BotSession.class.getDeclaredField("authenticationInterventionRequired");
        field.setAccessible(true);
        return ((AtomicBoolean) field.get(session)).get();
    }

    private static ReconnectStabilityGate stabilityGate(BotSession session) throws Exception {
        Field field = BotSession.class.getDeclaredField("reconnectStability");
        field.setAccessible(true);
        return (ReconnectStabilityGate) field.get(session);
    }

    private static void invokeTransportState(BotSession session, long generation,
                                             TransportState state) throws Exception {
        Method method = BotSession.class.getDeclaredMethod("onTransportState", long.class, TransportState.class);
        method.setAccessible(true);
        method.invoke(session, generation, state);
    }

    private static void invokeDisconnected(BotSession session, long generation, String reason) throws Exception {
        Method method = BotSession.class.getDeclaredMethod(
            "onDisconnected", long.class, String.class, Throwable.class);
        method.setAccessible(true);
        method.invoke(session, generation, reason, null);
    }

    private static void invokeAuthMessage(BotSession session, long generation, String message) throws Exception {
        Method method = BotSession.class.getDeclaredMethod("handleAuthMessage", long.class, String.class);
        method.setAccessible(true);
        method.invoke(session, generation, message);
    }

    private static void invokeStableReset(BotSession session, long generation) throws Exception {
        Method method = BotSession.class.getDeclaredMethod("resetReconnectAttemptsIfStable", long.class);
        method.setAccessible(true);
        method.invoke(session, generation);
    }

    private static void invokeAuthenticationTimeout(BotSession session, long generation,
                                                    long timeoutMillis) throws Exception {
        Method method = BotSession.class.getDeclaredMethod(
            "authenticationTimedOut", long.class, long.class);
        method.setAccessible(true);
        method.invoke(session, generation, timeoutMillis);
    }

    private static final class ConnectedTransport implements BotTransport {
        private final AtomicBoolean connected = new AtomicBoolean(true);
        private final CountDownLatch commandEntered;
        private final CountDownLatch releaseCommand;

        private ConnectedTransport() {
            this(null, null);
        }

        private ConnectedTransport(CountDownLatch commandEntered, CountDownLatch releaseCommand) {
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
            return connected.get();
        }

        @Override
        public boolean sendCommand(String command) {
            if (commandEntered == null) {
                return false;
            }
            commandEntered.countDown();
            try {
                return releaseCommand.await(5, TimeUnit.SECONDS);
            }
            catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
                return false;
            }
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
}
