package dev.nulli0n.vbot.bot;

import dev.nulli0n.vbot.config.BotPluginConfig;
import dev.nulli0n.vbot.protocol.ProtocolResolver;
import dev.nulli0n.vbot.protocol.ProtocolSelection;
import dev.nulli0n.vbot.protocol.ProtocolVersion;
import dev.nulli0n.vbot.protocol.TransportRegistry;
import dev.nulli0n.vbot.transport.BotPosition;
import dev.nulli0n.vbot.transport.BotTransport;
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

class BotSessionFailClosedAuthenticationTest {
    @Test
    void emptySuccessPatternsRemainPendingUntilAuthenticationTimeout() throws Exception {
        ScheduledExecutorService executor = Executors.newSingleThreadScheduledExecutor();
        BotSession session = session(executor);
        ConnectedTransport transport = new ConnectedTransport();
        try {
            manualStop(session).set(false);
            state(session).set(BotState.PLAY);
            setTransport(session, transport);
            reconnectAttempts(session).set(3);
            stabilityGate(session).connectionStarted(0L);
            stabilityGate(session).enteredPlay(0L);

            invokeInitialAuthentication(session, 0L, BotPluginConfig.AuthMode.LOGIN);
            assertThat(transport.loginSubmitted).isTrue();

            // A task inserted after the old fail-open delay is a deterministic
            // barrier: any completion scheduled by credential submission would
            // have run before this latch on the single-thread executor.
            CountDownLatch afterAuthDelayElapsed = new CountDownLatch(1);
            executor.schedule(afterAuthDelayElapsed::countDown, 30L, TimeUnit.MILLISECONDS);
            assertThat(afterAuthDelayElapsed.await(2, TimeUnit.SECONDS)).isTrue();

            assertThat(authenticationOutcome(session).pending()).isTrue();
            assertThat(session.isAuthenticationComplete()).isFalse();
            assertThat(session.snapshot().reconnectAttempts()).isEqualTo(3);

            invokeAuthenticationTimeout(session, 0L, 100L);

            assertThat(authenticationOutcome(session).failed()).isTrue();
            assertThat(session.snapshot().state()).isEqualTo(BotState.FAILED);
            assertThat(authenticationInterventionRequired(session)).isTrue();
            assertThat(transport.disconnected).isTrue();
            assertThat(session.snapshot().recentEvents())
                .anySatisfy(event -> assertThat(event.type()).isEqualTo("AUTH_TIMEOUT"));
        }
        finally {
            session.stop();
            executor.shutdownNow();
        }
    }

    private static BotSession session(ScheduledExecutorService executor) {
        BotPluginConfig.AuthConfig auth = new BotPluginConfig.AuthConfig(
            BotPluginConfig.AuthMode.LOGIN, "login {password}", "", 0L, 0L, 25L,
            List.of(), List.of(), List.of(), List.of(), 100L,
            true, "", BotPluginConfig.RegistrationSecondArgument.AUTO, 0L);
        BotPluginConfig.ProxyEndpoint endpoint = new BotPluginConfig.ProxyEndpoint(
            "127.0.0.1", 9, "localhost", 9,
            ProtocolSelection.fixed(ProtocolVersion.MINECRAFT_1_16_5), 100);
        BotPluginConfig.ReconnectConfig reconnect = new BotPluginConfig.ReconnectConfig(
            0L, 100L, 1.0D, 0.0D, 3, 0);
        BotPluginConfig.RuntimeConfig runtime = new BotPluginConfig.RuntimeConfig(
            0L, 0L, 10, 100L, 0L,
            BotPluginConfig.ResourcePackMode.DECLINE, false, reconnect);
        BotPluginConfig.BotDefinition definition = new BotPluginConfig.BotDefinition(
            "failclosed", true, "AFK_FailClosed", "credential", "", "", 2,
            auth, "server {server}", 1_000L, 0, List.of());
        ProtocolResolver resolver = new ProtocolResolver(endpoint, definition,
            (ignoredDefinition, ignoredEndpoint) -> null);
        return new BotSession(definition, endpoint, runtime, resolver, new TransportRegistry(),
            new ConnectionRateLimiter(0L), executor,
            LoggerFactory.getLogger(BotSessionFailClosedAuthenticationTest.class));
    }

    private static void invokeInitialAuthentication(BotSession session, long generation,
                                                    BotPluginConfig.AuthMode mode) throws Exception {
        Method method = BotSession.class.getDeclaredMethod(
            "runInitialChatAuthentication", long.class, BotPluginConfig.AuthMode.class);
        method.setAccessible(true);
        method.invoke(session, generation, mode);
    }

    private static void invokeAuthenticationTimeout(BotSession session, long generation,
                                                    long timeoutMillis) throws Exception {
        Method method = BotSession.class.getDeclaredMethod(
            "authenticationTimedOut", long.class, long.class);
        method.setAccessible(true);
        method.invoke(session, generation, timeoutMillis);
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

    private static AtomicInteger reconnectAttempts(BotSession session) throws Exception {
        Field field = BotSession.class.getDeclaredField("reconnectAttempts");
        field.setAccessible(true);
        return (AtomicInteger) field.get(session);
    }

    private static boolean authenticationInterventionRequired(BotSession session) throws Exception {
        Field field = BotSession.class.getDeclaredField("authenticationInterventionRequired");
        field.setAccessible(true);
        return ((AtomicBoolean) field.get(session)).get();
    }

    private static BotSession.AuthenticationOutcomeGate authenticationOutcome(BotSession session)
        throws Exception {
        Field field = BotSession.class.getDeclaredField("authenticationOutcome");
        field.setAccessible(true);
        return (BotSession.AuthenticationOutcomeGate) field.get(session);
    }

    private static ReconnectStabilityGate stabilityGate(BotSession session) throws Exception {
        Field field = BotSession.class.getDeclaredField("reconnectStability");
        field.setAccessible(true);
        return (ReconnectStabilityGate) field.get(session);
    }

    private static final class ConnectedTransport implements BotTransport {
        private final AtomicBoolean connected = new AtomicBoolean(true);
        private final AtomicBoolean loginSubmitted = new AtomicBoolean();
        private final AtomicBoolean disconnected = new AtomicBoolean();

        @Override
        public void connect() {
        }

        @Override
        public void disconnect(String reason) {
            disconnected.set(true);
            connected.set(false);
        }

        @Override
        public boolean isConnected() {
            return connected.get();
        }

        @Override
        public boolean sendCommand(String command) {
            loginSubmitted.set(command.equals("login credential"));
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
}
