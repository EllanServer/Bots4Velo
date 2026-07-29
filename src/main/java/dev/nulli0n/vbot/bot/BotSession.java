package dev.nulli0n.vbot.bot;

import dev.nulli0n.vbot.config.BotPluginConfig.AuthMode;
import dev.nulli0n.vbot.config.BotPluginConfig.BotDefinition;
import dev.nulli0n.vbot.config.BotPluginConfig.ProxyEndpoint;
import dev.nulli0n.vbot.config.BotPluginConfig.ResourcePackMode;
import dev.nulli0n.vbot.config.BotPluginConfig.RuntimeConfig;
import dev.nulli0n.vbot.protocol.ProtocolResolver;
import dev.nulli0n.vbot.protocol.ProtocolVersion;
import dev.nulli0n.vbot.protocol.TransportRegistry;
import dev.nulli0n.vbot.transport.AuthenticationUiChallenge;
import dev.nulli0n.vbot.transport.AuthenticationUiType;
import dev.nulli0n.vbot.transport.BotTransport;
import dev.nulli0n.vbot.transport.BotPosition;
import dev.nulli0n.vbot.transport.TransportConfig;
import dev.nulli0n.vbot.transport.TransportListener;
import dev.nulli0n.vbot.transport.TransportState;
import org.slf4j.Logger;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.Duration;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import java.util.regex.Pattern;

public final class BotSession implements BehaviorTarget {
    private final BotDefinition definition;
    private final ProxyEndpoint endpoint;
    private final RuntimeConfig runtime;
    private final ProtocolResolver protocolResolver;
    private final TransportRegistry transportRegistry;
    private final ConnectionRateLimiter connectionRateLimiter;
    private final ScheduledExecutorService executor;
    private final Logger logger;
    private final ReconnectPolicy reconnectPolicy;
    private final BotBehaviorRunner behavior;
    private final BotEventLog events;
    private final Consumer<BotEvent> eventSink;
    private final AtomicReference<BotState> state = new AtomicReference<>(BotState.STOPPED);
    private final AtomicBoolean manualStop = new AtomicBoolean(true);
    private final AtomicBoolean terminalFailure = new AtomicBoolean();
    private final AtomicInteger reconnectAttempts = new AtomicInteger();
    private final AtomicLong generation = new AtomicLong();
    private final AtomicBoolean loginSent = new AtomicBoolean();
    private final AtomicBoolean registerSent = new AtomicBoolean();
    private final AtomicBoolean authCompleted = new AtomicBoolean();
    private final AtomicBoolean preJoinAuthSubmitted = new AtomicBoolean();
    private final AtomicBoolean playInitialized = new AtomicBoolean();
    private final AtomicInteger playTransitionsThisConnection = new AtomicInteger();
    private final AtomicBoolean serverSwitchPending = new AtomicBoolean();
    private final AtomicBoolean serverSwitchTransitionSeen = new AtomicBoolean();
    private final AtomicInteger serverSwitchAttempts = new AtomicInteger();
    private final AtomicLong playEntries = new AtomicLong();
    private final AtomicLong disconnects = new AtomicLong();
    private final AtomicLong resourcePacksLoaded = new AtomicLong();
    private final AtomicLong authenticationUiPresentations = new AtomicLong();
    private final AtomicLong authenticationUiSubmissions = new AtomicLong();
    private final AtomicReference<String> lastAuthenticationUi = new AtomicReference<>("none");
    private final List<Pattern> loginPrompts;
    private final List<Pattern> registerPrompts;
    private final List<Pattern> successMessages;
    private final List<Pattern> failureMessages;

    private volatile BotTransport transport;
    private volatile ScheduledFuture<?> reconnectTask;
    private volatile ScheduledFuture<?> serverSwitchTask;
    private volatile ScheduledFuture<?> authenticationTimeoutTask;
    private volatile Instant connectedAt;
    private volatile Instant lastPlayAt;
    private volatile Instant lastDisconnectAt;
    private volatile ProtocolVersion activeProtocolVersion;
    private volatile String activeProtocol = "unresolved";
    private volatile String activeProtocolSource = "unresolved";
    private volatile String lastDisconnectReason = "never connected";
    private volatile String followTarget = "";

    public BotSession(BotDefinition definition, ProxyEndpoint endpoint, RuntimeConfig runtime,
                      ProtocolResolver protocolResolver, TransportRegistry transportRegistry,
                      ConnectionRateLimiter connectionRateLimiter,
                      ScheduledExecutorService executor, Logger logger) {
        this(definition, endpoint, runtime, protocolResolver, transportRegistry, connectionRateLimiter, executor,
            logger, ignored -> { });
    }

    public BotSession(BotDefinition definition, ProxyEndpoint endpoint, RuntimeConfig runtime,
                      ProtocolResolver protocolResolver, TransportRegistry transportRegistry,
                      ConnectionRateLimiter connectionRateLimiter,
                      ScheduledExecutorService executor, Logger logger, Consumer<BotEvent> eventSink) {
        this.definition = definition;
        this.endpoint = endpoint;
        this.runtime = runtime;
        this.protocolResolver = protocolResolver;
        this.transportRegistry = transportRegistry;
        this.connectionRateLimiter = connectionRateLimiter;
        this.executor = executor;
        this.logger = logger;
        this.eventSink = eventSink == null ? ignored -> { } : eventSink;
        this.events = new BotEventLog(definition.id());
        this.reconnectPolicy = new ReconnectPolicy(runtime.reconnect());
        this.behavior = new BotBehaviorRunner(this, definition.behavior(), executor, logger);
        this.loginPrompts = compile(definition.auth().loginPrompts());
        this.registerPrompts = compile(definition.auth().registerPrompts());
        this.successMessages = compile(definition.auth().successMessages());
        this.failureMessages = compile(definition.auth().failureMessages());
    }

    public BotDefinition definition() {
        return definition;
    }

    public BotSnapshot snapshot() {
        long onlineSeconds = connectedAt == null ? 0L : Math.max(0L, Duration.between(connectedAt, Instant.now()).toSeconds());
        return new BotSnapshot(definition.id(), definition.username(), activeProtocol, activeProtocolSource, state.get(),
            reconnectAttempts.get(), connectedAt, playEntries.get(), disconnects.get(),
            resourcePacksLoaded.get(), lastPlayAt, lastDisconnectAt, position(), lastAuthenticationUi.get(),
            authenticationUiPresentations.get(), authenticationUiSubmissions.get(), lastDisconnectReason,
            behavior.snapshot(), onlineSeconds, FailureCategory.classify(lastDisconnectReason), events.snapshot());
    }

    public synchronized void start() {
        BotState current = state.get();
        // Presence rules run periodically. Starting again while a connection
        // is pending or already active creates a second client with the same
        // username, which Velocity correctly rejects as a duplicate login.
        if (current != BotState.STOPPED && current != BotState.FAILED) {
            return;
        }
        manualStop.set(false);
        terminalFailure.set(false);
        event("START_REQUESTED", "operator or automatic startup");
        scheduleConnection(0);
    }

    public void stop() {
        manualStop.set(true);
        event("STOPPED", "operator request");
        generation.incrementAndGet();
        cancelReconnect();
        cancelServerSwitch();
        cancelAuthenticationTimeout();
        behavior.onUnavailable();
        BotTransport active = transport;
        transport = null;
        connectedAt = null;
        if (active != null) {
            state.set(BotState.STOPPING);
            active.disconnect("Bot stopped by operator");
        }
        state.set(BotState.STOPPED);
    }

    public void reconnectNow() {
        manualStop.set(false);
        terminalFailure.set(false);
        event("RECONNECT_REQUESTED", "operator request");
        cancelReconnect();
        cancelServerSwitch();
        behavior.onUnavailable();
        generation.incrementAndGet();
        BotTransport active = transport;
        transport = null;
        connectedAt = null;
        if (active != null) {
            active.disconnect("Bot reconnect requested");
        }
        state.set(BotState.RECONNECT_WAIT);
        scheduleConnection(200);
    }

    public boolean sendCommand(String command) {
        String normalized = normalizeCommand(command);
        BotTransport active = transport;
        return !normalized.isBlank() && active != null && state.get() == BotState.PLAY
            && active.sendCommand(normalized);
    }

    public boolean moveTo(double x, double y, double z) {
        BotTransport active = transport;
        return active != null && state.get() == BotState.PLAY && active.moveTo(x, y, z);
    }

    public boolean look(float yaw, float pitch) {
        BotTransport active = transport;
        return active != null && state.get() == BotState.PLAY && active.look(yaw, pitch);
    }

    public boolean swingMainHand() {
        BotTransport active = transport;
        return active != null && state.get() == BotState.PLAY && active.swingMainHand();
    }

    public boolean jump() {
        BotTransport active = transport;
        return active != null && state.get() == BotState.PLAY && active.jump();
    }

    public boolean setSneaking(boolean sneaking) {
        BotTransport active = transport;
        return active != null && state.get() == BotState.PLAY && active.setSneaking(sneaking);
    }

    public BotPosition position() {
        BotTransport active = transport;
        return active == null ? BotPosition.unknown() : active.position();
    }

    public boolean isPlayable() {
        BotTransport active = transport;
        return active != null && active.isConnected() && state.get() == BotState.PLAY;
    }

    public boolean isAuthenticationComplete() {
        return authCompleted.get();
    }

    public void startBehavior() {
        behavior.start();
    }

    public void pauseBehavior() {
        behavior.pause();
    }

    public boolean requestBehaviorServerSwitch(String server) {
        String normalized = server == null ? "" : server.trim();
        if (normalized.isBlank() || definition.serverSwitchCommand().isBlank()) {
            return false;
        }
        String command = definition.serverSwitchCommand().replace("{server}", normalized);
        return sendCommand(command);
    }

    public BehaviorSnapshot behaviorSnapshot() {
        return behavior.snapshot();
    }

    public String followTarget() {
        return followTarget;
    }

    public void setFollowTarget(String target) {
        followTarget = target == null ? "" : target.trim();
    }

    /** Records an operator-visible event produced by a proxy-side integration. */
    public void recordExternalEvent(String type, String detail) {
        event(type, detail);
    }

    /**
     * Stops the configured chat-command switch loop before Velocity moves this
     * connection through its own API. Otherwise the retry loop could pull the
     * bot back to the statically configured target after an operator switch.
     */
    public void prepareExternalServerSwitch() {
        cancelServerSwitch();
    }

    private void connectIfNeeded() {
        synchronized (this) {
            reconnectTask = null;
        }
        if (manualStop.get()) {
            state.set(BotState.STOPPED);
            return;
        }
        BotTransport active = transport;
        if (active != null && active.isConnected()) {
            return;
        }

        long currentGeneration = generation.incrementAndGet();
        resetConnectionState();
        state.set(BotState.CONNECTING);
        try {
            ProtocolVersion protocol = protocolResolver.resolve();
            activeProtocolVersion = protocol;
            activeProtocol = protocol.displayName() + " (" + protocol.protocolId() + ")";
            activeProtocolSource = protocolResolver.source();
            UUID uuid = UUID.nameUUIDFromBytes(("OfflinePlayer:" + definition.username())
                .getBytes(StandardCharsets.UTF_8));
            TransportConfig transportConfig = new TransportConfig(
                definition.username(), uuid, endpoint.address(), endpoint.port(),
                endpoint.virtualHost(), endpoint.virtualPort(), definition.renderDistance(),
                runtime.resourcePackMode() == ResourcePackMode.ACCEPT_WITHOUT_DOWNLOAD,
                runtime.resourcePackStepDelayMillis(), runtime.autoRespawn()
            );
            BotTransport created = transportRegistry.create(protocol, transportConfig,
                new SessionTransportListener(currentGeneration), executor);
            transport = created;
            logger.info("Bot {} ({}) connecting to {}:{} using protocol {} detected via {}",
                definition.id(), definition.username(), endpoint.address(), endpoint.port(), activeProtocol,
                activeProtocolSource);
            event("CONNECTING", activeProtocol + " via " + activeProtocolSource);
            created.connect();
        }
        catch (Exception exception) {
            lastDisconnectReason = exception.getMessage() == null ? exception.getClass().getSimpleName() : exception.getMessage();
            logger.warn("Bot {} could not start its connection: {}", definition.id(), lastDisconnectReason, exception);
            scheduleReconnect(currentGeneration);
        }
    }

    private void resetConnectionState() {
        cancelServerSwitch();
        cancelAuthenticationTimeout();
        connectedAt = null;
        loginSent.set(false);
        registerSent.set(false);
        authCompleted.set(false);
        preJoinAuthSubmitted.set(false);
        authenticationUiPresentations.set(0);
        authenticationUiSubmissions.set(0);
        lastAuthenticationUi.set("none");
        playInitialized.set(false);
        playTransitionsThisConnection.set(0);
        serverSwitchPending.set(false);
        serverSwitchTransitionSeen.set(false);
        serverSwitchAttempts.set(0);
    }

    private void onTransportState(long currentGeneration, TransportState transportState) {
        if (!isCurrent(currentGeneration)) {
            return;
        }
        switch (transportState) {
            case LOGIN -> {
                state.set(BotState.LOGIN);
                behavior.onUnavailable();
                if (definition.auth().mode() != AuthMode.NONE) {
                    // AuthMe's modern UI can be presented before the first
                    // PLAY transition, so the timeout must begin at LOGIN.
                    scheduleAuthenticationTimeout(currentGeneration);
                }
                if (connectedAt == null) {
                    connectedAt = Instant.now();
                }
                lastDisconnectReason = "connected";
                event("CONNECTED", activeProtocol);
            }
            case CONFIGURATION -> {
                state.set(BotState.CONFIGURATION);
                behavior.onUnavailable();
                if (serverSwitchPending.get()) {
                    serverSwitchTransitionSeen.set(true);
                }
            }
            case PLAY -> {
                state.set(BotState.PLAY);
                reconnectAttempts.set(0);
                playTransitionsThisConnection.incrementAndGet();
                if (playInitialized.compareAndSet(false, true)) {
                    playEntries.incrementAndGet();
                    lastPlayAt = Instant.now();
                    logger.info("Bot {} entered PLAY using {}", definition.id(), activeProtocol);
                    event("PLAY", activeProtocol);
                    if (preJoinAuthSubmitted.get()) {
                        logger.info("Bot {} completed authentication through a pre-join UI", definition.id());
                        completeAuthentication(currentGeneration);
                    }
                    else {
                        scheduleAuthentication(currentGeneration);
                    }
                }
                else if (serverSwitchPending.get() && isConfirmedServerTransition()) {
                    completeServerSwitch(currentGeneration);
                }
                else if (authCompleted.get()) {
                    behavior.onReady();
                }
            }
        }
    }

    private void scheduleAuthentication(long currentGeneration) {
        AuthMode mode = definition.auth().mode();
        if (mode == AuthMode.NONE) {
            completeAuthentication(currentGeneration);
            return;
        }
        scheduleAuthenticationTimeout(currentGeneration);
        executor.schedule(() -> {
            if (!isPlayable(currentGeneration) || authCompleted.get()) {
                return;
            }
            if (mode == AuthMode.REGISTER) {
                sendRegister();
                scheduleAuthCompletionIfNoSuccessPattern(currentGeneration);
            }
            else {
                // A prompt may arrive before this initial timer. Respect the
                // branch selected by that prompt instead of sending the other
                // AuthMe command afterwards.
                if (mode == AuthMode.AUTO && (loginSent.get() || registerSent.get())) {
                    return;
                }
                sendLogin();
                if (mode == AuthMode.LOGIN) {
                    scheduleAuthCompletionIfNoSuccessPattern(currentGeneration);
                }
                else {
                    executor.schedule(() -> {
                        if (isPlayable(currentGeneration) && !authCompleted.get()) {
                            sendRegister();
                            scheduleAuthCompletionIfNoSuccessPattern(currentGeneration);
                        }
                    }, definition.auth().fallbackRegisterDelayMillis(), TimeUnit.MILLISECONDS);
                }
            }
        }, definition.auth().loginDelayMillis(), TimeUnit.MILLISECONDS);
    }

    private void handleAuthMessage(long currentGeneration, String message) {
        if (!isCurrent(currentGeneration) || authCompleted.get() || definition.auth().mode() == AuthMode.NONE) {
            return;
        }
        if (matches(failureMessages, message)) {
            failAuthentication(currentGeneration, message);
        }
        else if (matches(successMessages, message)) {
            logger.info("Bot {} matched an authentication success message", definition.id());
            event("AUTHENTICATED", "success message");
            completeAuthentication(currentGeneration);
        }
        else if (matches(registerPrompts, message)) {
            logger.info("Bot {} matched a registration prompt", definition.id());
            event("AUTH_PROMPT", "registration");
            sendRegister();
            scheduleAuthCompletionIfNoSuccessPattern(currentGeneration);
        }
        else if (matches(loginPrompts, message)) {
            logger.info("Bot {} matched a login prompt", definition.id());
            event("AUTH_PROMPT", "login");
            sendLogin();
            scheduleAuthCompletionIfNoSuccessPattern(currentGeneration);
        }
    }

    private void failAuthentication(long currentGeneration, String message) {
        if (!isCurrent(currentGeneration) || !terminalFailure.compareAndSet(false, true)) {
            return;
        }
        FailureCategory category = FailureCategory.classify(message);
        lastDisconnectReason = "authentication failed: " + category.name();
        event("AUTH_FAILED", category.name());
        logger.warn("Bot {} stopped after authentication failure: {}", definition.id(), category);
        stopAfterAuthenticationFailure();
    }

    private void scheduleAuthenticationTimeout(long currentGeneration) {
        long timeout = definition.auth().timeoutMillis();
        if (timeout <= 0) {
            return;
        }
        if (authenticationTimeoutTask != null && !authenticationTimeoutTask.isDone()) {
            return;
        }
        authenticationTimeoutTask = executor.schedule(() -> {
            if (!isCurrent(currentGeneration) || authCompleted.get() || !terminalFailure.compareAndSet(false, true)) {
                return;
            }
            lastDisconnectReason = "authentication timed out after " + timeout + " ms";
            event("AUTH_TIMEOUT", Long.toString(timeout));
            logger.warn("Bot {} stopped after authentication timed out after {} ms", definition.id(), timeout);
            stopAfterAuthenticationFailure();
        }, timeout, TimeUnit.MILLISECONDS);
    }

    private void stopAfterAuthenticationFailure() {
        cancelAuthenticationTimeout();
        manualStop.set(true);
        cancelReconnect();
        cancelServerSwitch();
        behavior.onUnavailable();
        BotTransport active = transport;
        if (active != null) {
            active.disconnect("Authentication requires operator intervention");
        }
        state.set(BotState.FAILED);
    }

    private void handleAuthenticationUi(long currentGeneration, AuthenticationUiChallenge challenge) {
        if (!isCurrent(currentGeneration) || authCompleted.get() || definition.auth().mode() == AuthMode.NONE) {
            return;
        }
        AuthenticationUiType type = challenge.type();
        authenticationUiPresentations.incrementAndGet();
        lastAuthenticationUi.set(challenge.description());
        AuthMode mode = definition.auth().mode();
        boolean expected = mode == AuthMode.AUTO
            || (mode == AuthMode.LOGIN && type == AuthenticationUiType.LOGIN)
            || (mode == AuthMode.REGISTER && type == AuthenticationUiType.REGISTER);
        if (!expected) {
            logger.warn("Bot {} received an AuthMe {} UI while configured for auth mode {}",
                definition.id(), type, mode);
            return;
        }
        if (!preJoinAuthSubmitted.compareAndSet(false, true)) {
            return;
        }
        BotTransport active = transport;
        if (active == null || !active.submitAuthenticationUi(challenge, definition.password())) {
            preJoinAuthSubmitted.set(false);
            logger.warn("Bot {} could not submit the AuthMe pre-join {} UI", definition.id(), type);
        }
        else {
            authenticationUiSubmissions.incrementAndGet();
            logger.info("Bot {} submitted the AuthMe pre-join {} UI", definition.id(), type);
            event("AUTH_UI", type.name());
        }
    }

    private void sendLogin() {
        if (loginSent.compareAndSet(false, true)) {
            logger.info("Bot {} submitting its login command", definition.id());
            sendCommand(CommandTemplate.render(definition.auth().loginCommand(), definition));
        }
    }

    private void sendRegister() {
        if (registerSent.compareAndSet(false, true)) {
            logger.info("Bot {} submitting its registration command", definition.id());
            sendCommand(CommandTemplate.render(definition.auth().registerCommand(), definition));
        }
    }

    private void scheduleAuthCompletion(long currentGeneration) {
        executor.schedule(() -> completeAuthentication(currentGeneration),
            definition.auth().afterAuthDelayMillis(), TimeUnit.MILLISECONDS);
    }

    private void scheduleAuthCompletionIfNoSuccessPattern(long currentGeneration) {
        if (successMessages.isEmpty()) {
            scheduleAuthCompletion(currentGeneration);
        }
    }

    private void completeAuthentication(long currentGeneration) {
        if (!isPlayable(currentGeneration) || !authCompleted.compareAndSet(false, true)) {
            return;
        }
        cancelAuthenticationTimeout();
        if (!definition.targetServer().isBlank() && !definition.serverSwitchCommand().isBlank()) {
            if (playTransitionsThisConnection.get() > 1) {
                serverSwitchAttempts.set(0);
                serverSwitchPending.set(true);
                logger.info("Bot {} observed an authentication-plugin server transition before auth completion",
                    definition.id());
                completeServerSwitch(currentGeneration);
                return;
            }
            beginServerSwitch(currentGeneration);
            return;
        }
        scheduleAfterLoginCommands(currentGeneration, 0);
    }

    private void beginServerSwitch(long currentGeneration) {
        cancelServerSwitch();
        serverSwitchAttempts.set(0);
        serverSwitchTransitionSeen.set(false);
        serverSwitchPending.set(true);
        attemptServerSwitch(currentGeneration);
    }

    private void attemptServerSwitch(long currentGeneration) {
        if (!isCurrent(currentGeneration) || manualStop.get() || !serverSwitchPending.get()) {
            return;
        }
        if (!isPlayable(currentGeneration)) {
            serverSwitchTask = executor.schedule(() -> attemptServerSwitch(currentGeneration),
                250, TimeUnit.MILLISECONDS);
            return;
        }
        int attempt = serverSwitchAttempts.incrementAndGet();
        int maximumAttempts = definition.serverSwitchMaximumAttempts();
        if (maximumAttempts > 0 && attempt > maximumAttempts) {
            serverSwitchPending.set(false);
            lastDisconnectReason = "server switch to " + definition.targetServer() + " exhausted "
                + maximumAttempts + " attempts";
            logger.error("Bot {} could not switch to server {} after {} attempts; after-login commands were not run",
                definition.id(), definition.targetServer(), maximumAttempts);
            return;
        }
        String command = CommandTemplate.render(definition.serverSwitchCommand(), definition);
        if (sendCommand(command)) {
            logger.info("Bot {} requested server switch to {} (attempt {})",
                definition.id(), definition.targetServer(), attempt);
        }
        long retryDelay = Math.max(250L, definition.serverSwitchDelayMillis());
        serverSwitchTask = executor.schedule(() -> attemptServerSwitch(currentGeneration),
            retryDelay, TimeUnit.MILLISECONDS);
    }

    private boolean isConfirmedServerTransition() {
        return activeProtocolVersion == ProtocolVersion.MINECRAFT_1_16_5
            || serverSwitchTransitionSeen.get();
    }

    private void completeServerSwitch(long currentGeneration) {
        if (!isCurrent(currentGeneration) || !serverSwitchPending.compareAndSet(true, false)) {
            return;
        }
        cancelServerSwitchTaskOnly();
        logger.info("Bot {} confirmed server switch to {} after {} attempt(s)",
            definition.id(), definition.targetServer(), serverSwitchAttempts.get());
        event("SERVER_SWITCHED", definition.targetServer());
        scheduleAfterLoginCommands(currentGeneration, definition.serverSwitchDelayMillis());
    }

    private void scheduleAfterLoginCommands(long currentGeneration, long initialDelay) {
        long delay = initialDelay;
        for (String command : definition.afterLoginCommands()) {
            scheduleCommand(currentGeneration, CommandTemplate.render(command, definition), delay);
            delay += runtime.commandIntervalMillis();
        }
        behavior.onReady();
    }

    private void scheduleCommand(long currentGeneration, String command, long delay) {
        executor.schedule(() -> sendWhenPlayable(currentGeneration, command, 0), delay, TimeUnit.MILLISECONDS);
    }

    private void sendWhenPlayable(long currentGeneration, String command, int attempt) {
        if (!isCurrent(currentGeneration) || manualStop.get()) {
            return;
        }
        if (sendCommand(command)) {
            return;
        }
        if (attempt < 20) {
            executor.schedule(() -> sendWhenPlayable(currentGeneration, command, attempt + 1),
                250, TimeUnit.MILLISECONDS);
        }
        else {
            logger.warn("Bot {} could not execute a queued command because it never returned to PLAY", definition.id());
        }
    }

    private void onDisconnected(long currentGeneration, String reason, Throwable cause) {
        if (!isCurrent(currentGeneration)) {
            return;
        }
        connectedAt = null;
        disconnects.incrementAndGet();
        lastDisconnectAt = Instant.now();
        protocolResolver.invalidateAutomaticDetection();
        cancelServerSwitch();
        cancelAuthenticationTimeout();
        behavior.onUnavailable();
        // An operator-actionable authentication failure is more useful than
        // the synthetic disconnect reason used to close the transport.
        if (!terminalFailure.get()) {
            lastDisconnectReason = reason;
        }
        event("DISCONNECTED", reason);
        if (cause != null) {
            logger.warn("Bot {} disconnected: {}", definition.id(), reason, cause);
        }
        else {
            logger.info("Bot {} disconnected: {}", definition.id(), reason);
        }
        transport = null;
        if (terminalFailure.get()) {
            state.set(BotState.FAILED);
        }
        else if (manualStop.get()) {
            state.set(BotState.STOPPED);
        }
        else {
            scheduleReconnect(currentGeneration);
        }
    }

    private synchronized void scheduleReconnect(long currentGeneration) {
        if (!isCurrent(currentGeneration) || manualStop.get()) {
            return;
        }
        if (reconnectTask != null && !reconnectTask.isDone()) {
            return;
        }
        int attempt = reconnectAttempts.incrementAndGet();
        if (!reconnectPolicy.allows(attempt)) {
            state.set(BotState.FAILED);
            logger.error("Bot {} exhausted {} reconnect attempts", definition.id(), attempt - 1);
            return;
        }
        long policyDelay = reconnectPolicy.delayMillis(attempt, ThreadLocalRandom.current().nextDouble());
        long delay = connectionRateLimiter.reserveDelayMillis(policyDelay);
        state.set(BotState.RECONNECT_WAIT);
        if (delay > policyDelay) {
            logger.info("Bot {} reconnect attempt {} in {} ms ({} ms policy delay plus global connection spacing)",
                definition.id(), attempt, delay, policyDelay);
        }
        else {
            logger.info("Bot {} reconnect attempt {} in {} ms", definition.id(), attempt, delay);
        }
        reconnectTask = executor.schedule(this::connectIfNeeded, delay, TimeUnit.MILLISECONDS);
    }

    private synchronized void scheduleConnection(long minimumDelayMillis) {
        if (manualStop.get()) {
            return;
        }
        BotTransport active = transport;
        if (active != null && active.isConnected()) {
            return;
        }
        if (reconnectTask != null && !reconnectTask.isDone()) {
            return;
        }
        long delay = connectionRateLimiter.reserveDelayMillis(minimumDelayMillis);
        if (delay > 0) {
            state.set(BotState.RECONNECT_WAIT);
        }
        reconnectTask = executor.schedule(this::connectIfNeeded, delay, TimeUnit.MILLISECONDS);
    }

    private synchronized void cancelReconnect() {
        if (reconnectTask != null) {
            reconnectTask.cancel(false);
            reconnectTask = null;
        }
    }

    private synchronized void cancelServerSwitch() {
        serverSwitchPending.set(false);
        serverSwitchTransitionSeen.set(false);
        cancelServerSwitchTaskOnly();
    }

    private synchronized void cancelServerSwitchTaskOnly() {
        if (serverSwitchTask != null) {
            serverSwitchTask.cancel(false);
            serverSwitchTask = null;
        }
    }

    private synchronized void cancelAuthenticationTimeout() {
        if (authenticationTimeoutTask != null) {
            authenticationTimeoutTask.cancel(false);
            authenticationTimeoutTask = null;
        }
    }

    private boolean isPlayable(long currentGeneration) {
        BotTransport active = transport;
        return isCurrent(currentGeneration) && active != null && active.isConnected() && state.get() == BotState.PLAY;
    }

    private boolean isCurrent(long currentGeneration) {
        return generation.get() == currentGeneration;
    }

    private static String normalizeCommand(String command) {
        String normalized = command == null ? "" : command.trim();
        while (normalized.startsWith("/")) {
            normalized = normalized.substring(1);
        }
        return normalized;
    }

    private static List<Pattern> compile(List<String> expressions) {
        return expressions.stream().map(Pattern::compile).toList();
    }

    private static boolean matches(List<Pattern> patterns, String value) {
        return patterns.stream().anyMatch(pattern -> pattern.matcher(value).find());
    }

    private void event(String type, String detail) {
        events.add(type, detail);
        List<BotEvent> snapshot = events.snapshot();
        if (!snapshot.isEmpty()) {
            eventSink.accept(snapshot.getLast());
        }
    }

    private final class SessionTransportListener implements TransportListener {
        private final long currentGeneration;

        private SessionTransportListener(long currentGeneration) {
            this.currentGeneration = currentGeneration;
        }

        @Override
        public void onStateChanged(TransportState transportState) {
            onTransportState(currentGeneration, transportState);
        }

        @Override
        public void onSystemMessage(String message) {
            handleAuthMessage(currentGeneration, message);
        }

        @Override
        public void onAuthenticationUi(AuthenticationUiChallenge challenge) {
            handleAuthenticationUi(currentGeneration, challenge);
        }

        @Override
        public void onDisconnected(String reason, Throwable cause) {
            BotSession.this.onDisconnected(currentGeneration, reason, cause);
        }

        @Override
        public void onResourcePackStatus(String status) {
            if (isCurrent(currentGeneration)) {
                if (status.startsWith("SUCCESSFULLY_LOADED")) {
                    resourcePacksLoaded.incrementAndGet();
                }
                logger.info("Bot {} resource pack: {}", definition.id(), status);
            }
        }

        @Override
        public void onDiagnostic(String message) {
            logger.debug("Bot {}: {}", definition.id(), message);
        }
    }
}
