package dev.nulli0n.vbot.backend;

import com.velocitypowered.api.event.connection.PluginMessageEvent;
import com.velocitypowered.api.proxy.Player;
import com.velocitypowered.api.proxy.ProxyServer;
import com.velocitypowered.api.proxy.ServerConnection;
import com.velocitypowered.api.proxy.messages.MinecraftChannelIdentifier;
import com.velocitypowered.api.scheduler.ScheduledTask;
import dev.nulli0n.vbot.backend.protocol.BackendChannel;
import dev.nulli0n.vbot.backend.protocol.BackendGameMode;
import dev.nulli0n.vbot.backend.protocol.BackendInvulnerability;
import dev.nulli0n.vbot.backend.protocol.BackendOperation;
import dev.nulli0n.vbot.backend.protocol.BackendPolicy;
import dev.nulli0n.vbot.backend.protocol.BackendStatus;
import dev.nulli0n.vbot.backend.protocol.ControlRequest;
import dev.nulli0n.vbot.backend.protocol.ControlResponse;
import dev.nulli0n.vbot.backend.protocol.ManagedBoolean;
import dev.nulli0n.vbot.backend.protocol.ProtocolCodec;
import dev.nulli0n.vbot.backend.protocol.ProtocolException;
import dev.nulli0n.vbot.backend.protocol.ProtocolSecrets;
import dev.nulli0n.vbot.backend.protocol.RespawnMode;
import dev.nulli0n.vbot.backend.protocol.RespawnPoint;
import dev.nulli0n.vbot.bot.BotManager;
import dev.nulli0n.vbot.bot.BotSession;
import dev.nulli0n.vbot.config.BotPluginConfig;
import org.slf4j.Logger;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Supplier;

/**
 * Sends authenticated, server-authoritative player-state requests through the
 * bot's current Velocity backend connection.
 */
public final class VelocityBackendControlService implements BackendControlService, AutoCloseable {
    private static final MinecraftChannelIdentifier CHANNEL =
        MinecraftChannelIdentifier.from(BackendChannel.ID);
    private static final long RESPONSE_CLOCK_SKEW_MILLIS = 60_000L;
    private static final int MAXIMUM_PENDING_REQUESTS = 2_048;
    private static final String BACKEND_CHANGED_DETAIL =
        "Backend changed; acknowledgement is ambiguous.";
    private static final String BOT_REMOVED_DETAIL =
        "Bot was removed before the backend acknowledgement arrived.";

    private final ProxyServer proxy;
    private final Object plugin;
    private final Logger logger;
    private final Supplier<BotManager> managerSupplier;
    private final boolean enabled;
    private final byte[] secret;
    private final long timeoutMillis;
    private final ConcurrentMap<String, BackendPolicy> desiredPolicies = new ConcurrentHashMap<>();
    private final ConcurrentMap<String, BotSession> policyOwners = new ConcurrentHashMap<>();
    private final ConcurrentMap<UUID, PendingRequest> pending = new ConcurrentHashMap<>();
    private final ConcurrentMap<String, CompletableFuture<Void>> operationTails = new ConcurrentHashMap<>();
    private final ConcurrentMap<String, Long> reapplyGenerations = new ConcurrentHashMap<>();
    private final BackendCapabilityCache capabilityCache =
        new BackendCapabilityCache(MAXIMUM_PENDING_REQUESTS);
    private final AtomicLong generation = new AtomicLong();
    private final Object lifecycleLock = new Object();
    private final Object policyLock = new Object();
    private final Object operationLock = new Object();
    private volatile boolean started;
    private volatile boolean closed;

    public VelocityBackendControlService(ProxyServer proxy, Object plugin, Logger logger,
                                         BotPluginConfig config, Supplier<BotManager> managerSupplier) {
        this.proxy = proxy;
        this.plugin = plugin;
        this.logger = logger;
        this.managerSupplier = managerSupplier;
        BotPluginConfig.BackendControlConfig control = config.runtime().backendControl();
        this.enabled = control.enabled();
        this.secret = enabled ? ProtocolSecrets.decode(control.secret()) : new byte[0];
        this.timeoutMillis = control.timeoutMillis();
        config.bots().forEach((id, definition) ->
            desiredPolicies.put(normalize(id), configuredPolicy(definition.playerState())));
    }

    public void start() {
        synchronized (lifecycleLock) {
            if (closed || started || !enabled) {
                return;
            }
            started = true;
        }
        logger.info("Bots4Velo Paper backend control enabled on channel {}", BackendChannel.ID);
    }

    public boolean enabled() {
        return enabled && started && !closed;
    }

    @Override
    public CompletionStage<BackendControlResult> probe(String botId) {
        Optional<BotSession> session = findSession(botId);
        if (session.isEmpty()) {
            capabilityCache.remove(normalize(botId));
            return completedFailure(botId, BackendStatus.BAD_REQUEST, "Unknown bot.");
        }
        BotSession expectedSession = session.get();
        String canonicalId = expectedSession.definition().id();
        return enqueue(canonicalId, () -> probeSerialized(expectedSession));
    }

    @Override
    public CompletionStage<BackendControlResult> apply(String botId, BackendControlPatch patch) {
        if (patch == null) {
            return completedFailure(botId, BackendStatus.BAD_REQUEST, "A player-state change is required.");
        }
        Optional<BotSession> session = findSession(botId);
        if (session.isEmpty()) {
            capabilityCache.remove(normalize(botId));
            return completedFailure(botId, BackendStatus.BAD_REQUEST, "Unknown bot.");
        }
        BotSession expectedSession = session.get();
        String canonicalId = expectedSession.definition().id();
        return enqueue(canonicalId, () -> applySerialized(expectedSession, patch));
    }

    @Override
    public CompletionStage<BackendControlResult> respawn(String botId) {
        Optional<BotSession> session = findSession(botId);
        if (session.isEmpty()) {
            capabilityCache.remove(normalize(botId));
            return completedFailure(botId, BackendStatus.BAD_REQUEST, "Unknown bot.");
        }
        BotSession expectedSession = session.get();
        String canonicalId = expectedSession.definition().id();
        return enqueue(canonicalId, () -> send(expectedSession, BackendOperation.RESPAWN, null));
    }

    @Override
    public CompletionStage<BackendControlResult> recover(String botId) {
        Optional<BotSession> session = findSession(botId);
        if (session.isEmpty()) {
            capabilityCache.remove(normalize(botId));
            return completedFailure(botId, BackendStatus.BAD_REQUEST, "Unknown bot.");
        }
        BotSession expectedSession = session.get();
        String canonicalId = expectedSession.definition().id();
        return enqueue(canonicalId, () -> recoverSerialized(expectedSession));
    }

    private CompletionStage<BackendControlResult> applySerialized(
        BotSession expectedSession,
        BackendControlPatch patch
    ) {
        if (!isExpectedSession(expectedSession)) {
            return staleSessionFailure(expectedSession);
        }
        String botId = expectedSession.definition().id();
        BackendPolicy previous = desiredPolicy(expectedSession);
        BackendPolicy replacement = merge(previous, patch);
        return sendPolicy(expectedSession, replacement).thenApply(result -> {
            // A timeout is ambiguous: Paper may have applied the request and only
            // its ACK was lost. Retain the intent so a later switch/reconnect
            // converges both sides instead of silently restoring stale state.
            if (retainsDesiredPolicy(result.status())) {
                setDesiredPolicy(expectedSession, replacement);
            }
            return result;
        });
    }

    private CompletionStage<BackendControlResult> probeSerialized(BotSession expectedSession) {
        String botId = expectedSession.definition().id();
        TargetResolution resolution = resolveTarget(expectedSession);
        if (resolution.failure() != null) {
            return CompletableFuture.completedFuture(resolution.failure());
        }
        ConnectedTarget target = resolution.target();
        Optional<BackendCapabilityCache.Capabilities> cached = capabilityCache.get(
            normalize(botId), target.connection());
        if (cached.isPresent()) {
            return sendExact(target, probeOperation(cached.get()), null);
        }

        return negotiate(target).thenCompose(negotiation -> {
            if (negotiation.failure() != null) {
                return CompletableFuture.completedFuture(negotiation.failure());
            }
            if (!negotiation.capabilities().probeExtended()) {
                return CompletableFuture.completedFuture(negotiation.legacyProbe());
            }
            return sendExact(negotiation.target(), BackendOperation.PROBE_EXT, null);
        });
    }

    private CompletionStage<BackendControlResult> sendPolicy(
        BotSession expectedSession,
        BackendPolicy policy
    ) {
        String botId = expectedSession.definition().id();
        Optional<BackendOperation> legacyOperation = policyOperation(policy, null);
        if (legacyOperation.isPresent()) {
            return send(expectedSession, legacyOperation.get(), policy);
        }
        TargetResolution resolution = resolveTarget(expectedSession);
        if (resolution.failure() != null) {
            return CompletableFuture.completedFuture(resolution.failure());
        }
        return negotiate(resolution.target()).thenCompose(negotiation -> {
            if (negotiation.failure() != null) {
                return CompletableFuture.completedFuture(negotiation.failure());
            }
            Optional<BackendOperation> operation = policyOperation(policy, negotiation.capabilities());
            if (operation.isEmpty()) {
                return completedFailure(botId, BackendStatus.UNSUPPORTED,
                    "The connected Paper companion does not support extended player-state policies.");
            }
            return sendExact(negotiation.target(), operation.get(), policy);
        });
    }

    private CompletionStage<BackendControlResult> recoverSerialized(BotSession expectedSession) {
        String botId = expectedSession.definition().id();
        TargetResolution resolution = resolveTarget(expectedSession);
        if (resolution.failure() != null) {
            return CompletableFuture.completedFuture(resolution.failure());
        }
        return negotiate(resolution.target()).thenCompose(negotiation -> {
            if (negotiation.failure() != null) {
                return CompletableFuture.completedFuture(negotiation.failure());
            }
            if (!negotiation.capabilities().recover()) {
                return completedFailure(botId, BackendStatus.UNSUPPORTED,
                    "The connected Paper companion does not support recovery.");
            }
            return sendExact(negotiation.target(), BackendOperation.RECOVER, null);
        });
    }

    private CompletionStage<CapabilityNegotiation> negotiate(ConnectedTarget target) {
        String key = normalize(target.session().definition().id());
        Optional<BackendCapabilityCache.Capabilities> cached = capabilityCache.get(key, target.connection());
        if (cached.isPresent()) {
            return CompletableFuture.completedFuture(CapabilityNegotiation.success(
                target, cached.get(), null));
        }
        return sendExact(target, BackendOperation.PROBE, null).thenApply(result -> {
            if (!result.successful()) {
                return CapabilityNegotiation.failure(target, result);
            }
            if (!isCurrent(target)) {
                capabilityCache.remove(key);
                return CapabilityNegotiation.failure(target, BackendControlResult.failure(
                    target.session().definition().id(), BackendStatus.BOT_NOT_ON_SERVER,
                    "The bot changed backend while companion capabilities were being negotiated."));
            }
            BackendCapabilityCache.Capabilities capabilities =
                BackendCapabilityCache.Capabilities.parse(result.detail());
            capabilityCache.put(key, target.connection(), capabilities);
            return CapabilityNegotiation.success(target, capabilities, result);
        });
    }

    /** Returns true when the event belonged to the private control channel. */
    public boolean handlePluginMessage(PluginMessageEvent event) {
        if (!CHANNEL.equals(event.getIdentifier())) {
            return false;
        }
        if (!enabled() || !(event.getSource() instanceof ServerConnection connection)) {
            return true;
        }

        final ControlResponse response;
        try {
            response = ProtocolCodec.decodeResponse(event.getData(), secret);
        }
        catch (ProtocolException | IllegalArgumentException exception) {
            logger.warn("Rejected a Bots4Velo Paper control response from {}: {}",
                connection.getServerInfo().getName(), exception.getMessage());
            return true;
        }

        PendingRequest request = pending.get(response.requestId());
        if (request == null) {
            logger.debug("Ignored an unknown or expired backend-control response {}", response.requestId());
            return true;
        }
        UUID carrierUuid = connection.getPlayer().getUniqueId();
        long now = System.currentTimeMillis();
        boolean timestampValid = response.timestampMillis() >= now - RESPONSE_CLOCK_SKEW_MILLIS
            && response.timestampMillis() <= now + RESPONSE_CLOCK_SKEW_MILLIS;
        boolean matches = event.getSource() == request.connection()
            && event.getTarget() == request.player()
            && connection == request.connection()
            && isExpectedSession(request.session())
            && request.player().getCurrentServer().orElse(null) == request.connection()
            && request.targetUuid().equals(carrierUuid)
            && request.targetUuid().equals(response.targetUuid())
            && request.operation() == response.operation()
            && Arrays.equals(request.nonce(), response.requestNonce())
            && timestampValid;
        if (!matches) {
            logger.warn("Rejected a mismatched backend-control response {} from {}",
                response.requestId(), connection.getServerInfo().getName());
            return true;
        }
        if (!pending.remove(response.requestId(), request)) {
            return true;
        }
        request.cancelTasks();
        BackendControlResult result = new BackendControlResult(request.botId(), response.status(),
            response.detail(), response.actualState());
        logger.info("Paper backend control {} for bot {} on {}: {}",
            response.operation(), request.botId(), connection.getServerInfo().getName(), response.status());
        request.session().recordExternalEvent(
            "BACKEND_CONTROL", response.operation() + ":" + response.status());
        request.future().complete(result);
        return true;
    }

    /** Schedules desired state after login/authentication and every backend switch. */
    public void handleServerPostConnect(Player player) {
        if (!enabled() || player == null) {
            return;
        }
        findSessionByUsername(player.getUsername()).ifPresent(session -> {
            if (proxy.getPlayer(session.definition().username()).orElse(null) != player) {
                return;
            }
            String key = normalize(session.definition().id());
            ServerConnection currentConnection = player.getCurrentServer().orElse(null);
            cancelPendingForBot(key, currentConnection, BackendStatus.TIMEOUT, BACKEND_CHANGED_DETAIL);
            capabilityCache.remove(key);
            desiredPolicy(session);
            long currentGeneration = generation.incrementAndGet();
            reapplyGenerations.put(key, currentGeneration);
            long delay = session.definition().playerState().applyDelayMillis();
            long authBudget = Math.max(30_000L, session.definition().auth().timeoutMillis() + 5_000L);
            long deadlineNanos = System.nanoTime()
                + java.util.concurrent.TimeUnit.MILLISECONDS.toNanos(authBudget + delay);
            scheduleReadyApply(session, currentGeneration, deadlineNanos, delay);
        });
    }

    private void scheduleReadyApply(BotSession expectedSession, long expectedGeneration,
                                    long deadlineNanos, long delayMillis) {
        proxy.getScheduler().buildTask(plugin,
            () -> readyApply(expectedSession, expectedGeneration, deadlineNanos))
            .delay(Duration.ofMillis(Math.max(0L, delayMillis))).schedule();
    }

    private void readyApply(BotSession expectedSession, long expectedGeneration, long deadlineNanos) {
        String botId = expectedSession.definition().id();
        String key = normalize(botId);
        if (closed || !reapplyGenerations.getOrDefault(key, -1L).equals(expectedGeneration)) {
            return;
        }
        if (!isExpectedSession(expectedSession)) {
            reapplyGenerations.remove(key, expectedGeneration);
            return;
        }
        Optional<Player> player = proxy.getPlayer(expectedSession.definition().username());
        boolean ready = expectedSession.isPlayable() && expectedSession.isAuthenticationComplete()
            && player.flatMap(Player::getCurrentServer).isPresent();
        if (!ready) {
            if (System.nanoTime() < deadlineNanos) {
                scheduleReadyApply(expectedSession, expectedGeneration, deadlineNanos, 250L);
            }
            else {
                logger.warn("Bot {} never became ready for configured Paper player state", botId);
                reapplyGenerations.remove(key, expectedGeneration);
            }
            return;
        }

        enqueue(botId, () -> {
            if (!isExpectedSession(expectedSession)) {
                return staleSessionFailure(expectedSession);
            }
            return sendPolicy(expectedSession, desiredPolicy(expectedSession));
        }).whenComplete((result, throwable) -> {
            reapplyGenerations.remove(key, expectedGeneration);
            if (throwable != null) {
                logger.warn("Could not reapply configured Paper player state for bot {}", botId, throwable);
            }
            else if (result != null && !result.successful()) {
                logger.warn("Could not reapply configured Paper player state for bot {}: {} ({})",
                    botId, result.status(), result.detail());
            }
            else {
                logger.info("Reapplied configured Paper player state for bot {}", botId);
            }
        });
    }

    private CompletionStage<BackendControlResult> send(BotSession expectedSession, BackendOperation operation,
                                                        BackendPolicy policy) {
        TargetResolution resolution = resolveTarget(expectedSession);
        if (resolution.failure() != null) {
            return CompletableFuture.completedFuture(resolution.failure());
        }
        return sendExact(resolution.target(), operation, policy);
    }

    private TargetResolution resolveTarget(BotSession expectedSession) {
        String safeBotId = expectedSession.definition().id();
        if (!enabled()) {
            return TargetResolution.failure(BackendControlResult.failure(safeBotId, BackendStatus.PLUGIN_MISSING,
                "Paper backend control is disabled. Enable runtime.backend-control and install Bots4VeloPaper."));
        }
        if (!isExpectedSession(expectedSession)) {
            capabilityCache.remove(normalize(safeBotId));
            return TargetResolution.failure(BackendControlResult.failure(
                safeBotId, BackendStatus.BOT_NOT_ON_SERVER,
                "The bot session was removed or replaced before the request could run."));
        }
        BotSession session = expectedSession;
        if (!session.isPlayable() || !session.isAuthenticationComplete()) {
            return TargetResolution.failure(BackendControlResult.failure(session.definition().id(),
                BackendStatus.BOT_NOT_ON_SERVER, "The bot is not in PLAY or authentication is still pending."));
        }
        Optional<Player> player = proxy.getPlayer(session.definition().username());
        Optional<ServerConnection> connection = player.flatMap(Player::getCurrentServer);
        if (player.isEmpty() || connection.isEmpty()) {
            return TargetResolution.failure(BackendControlResult.failure(session.definition().id(),
                BackendStatus.BOT_NOT_ON_SERVER, "The bot is not connected to a Velocity backend."));
        }
        return TargetResolution.success(new ConnectedTarget(session, player.get(), connection.get()));
    }

    private CompletionStage<BackendControlResult> sendExact(ConnectedTarget target, BackendOperation operation,
                                                             BackendPolicy policy) {
        BotSession session = target.session();
        Player player = target.player();
        ServerConnection connection = target.connection();
        if (!isCurrent(target)) {
            capabilityCache.remove(normalize(session.definition().id()));
            return completedFailure(session.definition().id(), BackendStatus.BOT_NOT_ON_SERVER,
                "The bot changed backend before the request could be sent.");
        }

        ControlRequest request = ControlRequest.create(player.getUniqueId(), operation, policy);
        byte[] payload;
        try {
            payload = ProtocolCodec.encodeRequest(request, secret);
        }
        catch (RuntimeException exception) {
            return completedFailure(session.definition().id(), BackendStatus.BAD_REQUEST,
                "Could not encode the backend request: " + safeMessage(exception));
        }

        CompletableFuture<BackendControlResult> future = new CompletableFuture<>();
        PendingRequest pendingRequest = new PendingRequest(session, request,
            player, connection, payload, future);
        synchronized (lifecycleLock) {
            if (!enabled()) {
                future.complete(BackendControlResult.failure(session.definition().id(), BackendStatus.APPLY_FAILED,
                    "Backend control was reloaded or stopped before the request was sent."));
                return future;
            }
            if (pending.size() >= MAXIMUM_PENDING_REQUESTS) {
                future.complete(BackendControlResult.failure(session.definition().id(), BackendStatus.APPLY_FAILED,
                    "Too many backend-control requests are pending."));
                return future;
            }
            if (!isCurrent(target)) {
                capabilityCache.remove(normalize(session.definition().id()));
                future.complete(BackendControlResult.failure(session.definition().id(),
                    BackendStatus.BOT_NOT_ON_SERVER, "The bot changed backend before the request was sent."));
                return future;
            }
            pending.put(request.requestId(), pendingRequest);
            try {
                boolean sent = connection.sendPluginMessage(CHANNEL, payload);
                if (!sent) {
                    pending.remove(request.requestId(), pendingRequest);
                    future.complete(BackendControlResult.failure(session.definition().id(),
                        BackendStatus.PLUGIN_MISSING,
                        "The backend did not register the Bots4Velo Paper control channel."));
                    return future;
                }

                long retryDelay = Math.max(100L, Math.min(1_000L, timeoutMillis / 2L));
                ScheduledTask retry = proxy.getScheduler().buildTask(plugin,
                    () -> retry(pendingRequest)).delay(Duration.ofMillis(retryDelay)).schedule();
                pendingRequest.retry(retry);
                ScheduledTask timeout = proxy.getScheduler().buildTask(plugin, () -> {
                    if (pending.remove(request.requestId(), pendingRequest)) {
                        pendingRequest.cancelTasks();
                        if (isExpectedSession(session)) {
                            session.recordExternalEvent("BACKEND_CONTROL",
                                operation + ":" + BackendStatus.TIMEOUT);
                        }
                        String timeoutDetail = "No signed Paper acknowledgement arrived within "
                            + timeoutMillis + " ms after an idempotent retry.";
                        if (operation == BackendOperation.APPLY_POLICY
                            || operation == BackendOperation.APPLY_POLICY_EXT) {
                            timeoutDetail += " Desired state was retained for convergence.";
                        }
                        future.complete(BackendControlResult.failure(session.definition().id(), BackendStatus.TIMEOUT,
                            timeoutDetail));
                    }
                }).delay(Duration.ofMillis(timeoutMillis)).schedule();
                pendingRequest.timeout(timeout);
            }
            catch (RuntimeException exception) {
                pending.remove(request.requestId(), pendingRequest);
                pendingRequest.cancelTasks();
                future.complete(BackendControlResult.failure(session.definition().id(), BackendStatus.APPLY_FAILED,
                    "Could not send or schedule the backend request: " + safeMessage(exception)));
            }
        }
        return future;
    }

    private void retry(PendingRequest request) {
        synchronized (lifecycleLock) {
            if (closed || pending.get(request.requestId()) != request
                || !isExpectedSession(request.session())
                || request.player().getCurrentServer().orElse(null) != request.connection()) {
                return;
            }
            try {
                request.connection().sendPluginMessage(CHANNEL, request.payload());
            }
            catch (RuntimeException exception) {
                logger.debug("Could not retry backend-control request {}: {}",
                    request.requestId(), safeMessage(exception));
            }
        }
    }

    private boolean isCurrent(ConnectedTarget target) {
        return enabled() && isExpectedSession(target.session())
            && target.session().isPlayable() && target.session().isAuthenticationComplete()
            && proxy.getPlayer(target.session().definition().username()).orElse(null) == target.player()
            && target.player().getCurrentServer().orElse(null) == target.connection();
    }

    private boolean isExpectedSession(BotSession expectedSession) {
        BotManager manager = managerSupplier.get();
        return manager != null
            && sameSession(expectedSession,
                manager.find(expectedSession.definition().id()).orElse(null));
    }

    static boolean sameSession(Object expectedSession, Object currentSession) {
        return expectedSession == currentSession;
    }

    private CompletionStage<BackendControlResult> staleSessionFailure(BotSession expectedSession) {
        return completedFailure(expectedSession.definition().id(), BackendStatus.BOT_NOT_ON_SERVER,
            "The bot session was removed or replaced before the operation could run.");
    }

    private Optional<BotSession> findSession(String botId) {
        if (botId == null || botId.isBlank()) {
            return Optional.empty();
        }
        BotManager manager = managerSupplier.get();
        return manager == null ? Optional.empty() : manager.find(botId);
    }

    private Optional<BotSession> findSessionByUsername(String username) {
        BotManager manager = managerSupplier.get();
        if (manager == null) {
            return Optional.empty();
        }
        return manager.sessions().stream()
            .filter(session -> session.definition().username().equalsIgnoreCase(username))
            .findFirst();
    }

    private BackendPolicy desiredPolicy(BotSession session) {
        String key = normalize(session.definition().id());
        synchronized (policyLock) {
            BotSession owner = policyOwners.put(key, session);
            if (owner != null && owner != session) {
                capabilityCache.remove(key);
                desiredPolicies.put(key, configuredPolicy(session.definition().playerState()));
            }
            return desiredPolicies.computeIfAbsent(key,
                ignored -> configuredPolicy(session.definition().playerState()));
        }
    }

    private void setDesiredPolicy(BotSession session, BackendPolicy policy) {
        String key = normalize(session.definition().id());
        synchronized (policyLock) {
            if (policyOwners.get(key) == session) {
                desiredPolicies.put(key, policy);
            }
        }
    }

    CompletionStage<BackendControlResult> enqueue(
        String botId, Supplier<CompletionStage<BackendControlResult>> operation) {
        String key = normalize(botId);
        CompletableFuture<BackendControlResult> result;
        CompletableFuture<Void> tail;
        synchronized (operationLock) {
            CompletableFuture<Void> previous = operationTails.getOrDefault(key,
                CompletableFuture.completedFuture(null));
            result = previous.handle((ignored, failure) -> null).thenCompose(ignored -> {
                try {
                    CompletionStage<BackendControlResult> stage = operation.get();
                    if (stage == null) {
                        return completedFailure(botId, BackendStatus.APPLY_FAILED,
                            "Backend operation returned no future.");
                    }
                    return stage;
                }
                catch (RuntimeException exception) {
                    return completedFailure(botId, BackendStatus.APPLY_FAILED, safeMessage(exception));
                }
            }).toCompletableFuture();
            tail = result.handle((ignored, failure) -> null);
            operationTails.put(key, tail);
            CompletableFuture<Void> registeredTail = tail;
            tail.whenComplete((ignored, failure) -> operationTails.remove(key, registeredTail));
        }
        return result;
    }

    static BackendPolicy merge(BackendPolicy current, BackendControlPatch patch) {
        BackendInvulnerability invulnerability = current.invulnerability();
        BackendGameMode gameMode = current.gameMode();
        RespawnPoint respawnPoint = current.respawnPoint();
        ManagedBoolean sleepingIgnored = current.sleepingIgnored();
        ManagedBoolean affectsSpawning = current.affectsSpawning();
        ManagedBoolean pickupItems = current.pickupItems();
        ManagedBoolean collidable = current.collidable();
        if (patch.invulnerabilityPresent()) {
            invulnerability = switch (patch.invulnerability()) {
                case KEEP -> BackendInvulnerability.UNCHANGED;
                case ENABLED -> BackendInvulnerability.ENABLED;
                case DISABLED -> BackendInvulnerability.DISABLED;
            };
        }
        if (patch.gameModePresent()) {
            gameMode = patch.gameMode();
        }
        if (patch.respawnPointPresent()) {
            respawnPoint = patch.respawnPoint();
        }
        if (patch.sleepingIgnoredPresent()) {
            sleepingIgnored = patch.sleepingIgnored();
        }
        if (patch.affectsSpawningPresent()) {
            affectsSpawning = patch.affectsSpawning();
        }
        if (patch.pickupItemsPresent()) {
            pickupItems = patch.pickupItems();
        }
        if (patch.collidablePresent()) {
            collidable = patch.collidable();
        }
        return new BackendPolicy(invulnerability, gameMode, respawnPoint,
            sleepingIgnored, affectsSpawning, pickupItems, collidable);
    }

    static BackendPolicy configuredPolicy(BotPluginConfig.PlayerStateConfig state) {
        BackendInvulnerability invulnerability = switch (state.invulnerability()) {
            case KEEP -> BackendInvulnerability.UNCHANGED;
            case ENABLED -> BackendInvulnerability.ENABLED;
            case DISABLED -> BackendInvulnerability.DISABLED;
        };
        BackendGameMode gameMode = switch (state.gameMode()) {
            case KEEP -> BackendGameMode.UNCHANGED;
            case SURVIVAL -> BackendGameMode.SURVIVAL;
            case CREATIVE -> BackendGameMode.CREATIVE;
            case ADVENTURE -> BackendGameMode.ADVENTURE;
            case SPECTATOR -> BackendGameMode.SPECTATOR;
        };
        BotPluginConfig.RespawnPointConfig configuredPoint = state.respawnPoint();
        RespawnPoint point = switch (configuredPoint.mode()) {
            case UNCHANGED -> RespawnPoint.unchanged();
            case CURRENT -> RespawnPoint.current();
            case FIXED -> RespawnPoint.fixed(configuredPoint.world(), configuredPoint.x(), configuredPoint.y(),
                configuredPoint.z(), configuredPoint.yaw(), 0.0F);
            case WORLD_SPAWN -> RespawnPoint.worldSpawn(configuredPoint.world());
            case CLEAR -> RespawnPoint.clear();
        };
        return new BackendPolicy(invulnerability, gameMode, point,
            managedBoolean(state.sleepingIgnored()), managedBoolean(state.affectsSpawning()),
            managedBoolean(state.pickupItems()), managedBoolean(state.collidable()));
    }

    static boolean isUnchanged(BackendPolicy policy) {
        return policy.invulnerability() == BackendInvulnerability.UNCHANGED
            && policy.gameMode() == BackendGameMode.UNCHANGED
            && policy.respawnPoint().mode() == RespawnMode.UNCHANGED
            && !hasManagedExtendedState(policy);
    }

    static boolean hasManagedExtendedState(BackendPolicy policy) {
        return policy.sleepingIgnored() != ManagedBoolean.UNCHANGED
            || policy.affectsSpawning() != ManagedBoolean.UNCHANGED
            || policy.pickupItems() != ManagedBoolean.UNCHANGED
            || policy.collidable() != ManagedBoolean.UNCHANGED;
    }

    static BackendOperation probeOperation(BackendCapabilityCache.Capabilities capabilities) {
        return capabilities.probeExtended() ? BackendOperation.PROBE_EXT : BackendOperation.PROBE;
    }

    /**
     * Selects the only wire operation that can faithfully carry this policy.
     * An empty result deliberately rejects an extended policy for a legacy
     * companion instead of silently dropping its additional fields.
     */
    static Optional<BackendOperation> policyOperation(
        BackendPolicy policy,
        BackendCapabilityCache.Capabilities capabilities
    ) {
        if (!hasManagedExtendedState(policy)) {
            return Optional.of(BackendOperation.APPLY_POLICY);
        }
        if (capabilities != null && capabilities.applyPolicyExtended()) {
            return Optional.of(BackendOperation.APPLY_POLICY_EXT);
        }
        return Optional.empty();
    }

    private static ManagedBoolean managedBoolean(BotPluginConfig.ManagedFlag flag) {
        return switch (flag) {
            case KEEP -> ManagedBoolean.UNCHANGED;
            case ENABLED -> ManagedBoolean.ENABLED;
            case DISABLED -> ManagedBoolean.DISABLED;
        };
    }

    /** Clears connection-scoped state when a managed bot is removed at runtime. */
    public void removeBot(String botId) {
        String key = normalize(botId);
        cancelPendingForBot(key, null, BackendStatus.BOT_NOT_ON_SERVER, BOT_REMOVED_DETAIL);
        capabilityCache.remove(key);
        synchronized (policyLock) {
            desiredPolicies.remove(key);
            policyOwners.remove(key);
        }
        reapplyGenerations.remove(key);
    }

    private void cancelPendingForBot(String botId, ServerConnection retainedConnection,
                                     BackendStatus status, String detail) {
        String key = normalize(botId);
        List<PendingRequest> cancelled = new ArrayList<>();
        synchronized (lifecycleLock) {
            pending.forEach((requestId, request) -> {
                if (shouldCancelPending(key, request.botId(), retainedConnection, request.connection())
                    && pending.remove(requestId, request)) {
                    request.cancelTasks();
                    cancelled.add(request);
                }
            });
        }
        for (PendingRequest request : cancelled) {
            if (isExpectedSession(request.session())) {
                request.session().recordExternalEvent("BACKEND_CONTROL",
                    request.operation() + ":" + status);
            }
            request.future().complete(BackendControlResult.failure(request.botId(), status, detail));
        }
    }

    static boolean shouldCancelPending(String normalizedBotId, String pendingBotId,
                                       Object retainedConnection, Object pendingConnection) {
        return normalize(pendingBotId).equals(normalize(normalizedBotId))
            && (retainedConnection == null || pendingConnection != retainedConnection);
    }

    static boolean retainsDesiredPolicy(BackendStatus status) {
        return status == BackendStatus.OK || status == BackendStatus.TIMEOUT;
    }

    private static CompletionStage<BackendControlResult> completedFailure(String botId,
                                                                           BackendStatus status,
                                                                           String detail) {
        return CompletableFuture.completedFuture(BackendControlResult.failure(
            botId == null ? "" : botId, status, detail));
    }

    private static String normalize(String value) {
        return value == null ? "" : value.trim().toLowerCase(Locale.ROOT);
    }

    private static String safeMessage(Throwable throwable) {
        return throwable.getMessage() == null ? throwable.getClass().getSimpleName() : throwable.getMessage();
    }

    @Override
    public void close() {
        synchronized (lifecycleLock) {
            if (closed) {
                return;
            }
            closed = true;
            started = false;
            generation.incrementAndGet();
            reapplyGenerations.clear();
            capabilityCache.clear();
            pending.forEach((id, request) -> {
                if (pending.remove(id, request)) {
                    request.cancelTasks();
                    request.future().complete(BackendControlResult.failure(request.botId(),
                        BackendStatus.APPLY_FAILED, "Backend control was reloaded or stopped."));
                }
            });
            Arrays.fill(secret, (byte) 0);
        }
    }

    /**
     * A target snapshot bound to the exact Velocity objects used to send a
     * request. Keeping all three references together prevents a capability
     * result learned on one backend connection from being reused after a
     * server switch or session replacement.
     */
    private record ConnectedTarget(
        BotSession session,
        Player player,
        ServerConnection connection
    ) {
    }

    private record TargetResolution(
        ConnectedTarget target,
        BackendControlResult failure
    ) {
        private static TargetResolution success(ConnectedTarget target) {
            return new TargetResolution(target, null);
        }

        private static TargetResolution failure(BackendControlResult failure) {
            return new TargetResolution(null, failure);
        }
    }

    private record CapabilityNegotiation(
        ConnectedTarget target,
        BackendCapabilityCache.Capabilities capabilities,
        BackendControlResult legacyProbe,
        BackendControlResult failure
    ) {
        private static CapabilityNegotiation success(
            ConnectedTarget target,
            BackendCapabilityCache.Capabilities capabilities,
            BackendControlResult legacyProbe
        ) {
            return new CapabilityNegotiation(target, capabilities, legacyProbe, null);
        }

        private static CapabilityNegotiation failure(
            ConnectedTarget target,
            BackendControlResult failure
        ) {
            return new CapabilityNegotiation(target, null, null, failure);
        }
    }

    private static final class PendingRequest {
        private final BotSession session;
        private final String botId;
        private final UUID targetUuid;
        private final UUID requestId;
        private final byte[] nonce;
        private final BackendOperation operation;
        private final Player player;
        private final ServerConnection connection;
        private final byte[] payload;
        private final CompletableFuture<BackendControlResult> future;
        private volatile ScheduledTask timeout;
        private volatile ScheduledTask retry;

        private PendingRequest(BotSession session, ControlRequest request, Player player,
                               ServerConnection connection, byte[] payload,
                               CompletableFuture<BackendControlResult> future) {
            this.session = session;
            this.botId = session.definition().id();
            this.targetUuid = request.targetUuid();
            this.requestId = request.requestId();
            this.nonce = request.nonce();
            this.operation = request.operation();
            this.player = player;
            this.connection = connection;
            this.payload = payload.clone();
            this.future = future;
        }

        private BotSession session() {
            return session;
        }

        private String botId() {
            return botId;
        }

        private UUID targetUuid() {
            return targetUuid;
        }

        private UUID requestId() {
            return requestId;
        }

        private byte[] nonce() {
            return nonce;
        }

        private BackendOperation operation() {
            return operation;
        }

        private Player player() {
            return player;
        }

        private ServerConnection connection() {
            return connection;
        }

        private byte[] payload() {
            return payload;
        }

        private CompletableFuture<BackendControlResult> future() {
            return future;
        }

        private void timeout(ScheduledTask task) {
            timeout = task;
            if (future.isDone()) {
                task.cancel();
            }
        }

        private void retry(ScheduledTask task) {
            retry = task;
            if (future.isDone()) {
                task.cancel();
            }
        }

        private void cancelTasks() {
            ScheduledTask task = timeout;
            if (task != null) {
                task.cancel();
            }
            task = retry;
            if (task != null) {
                task.cancel();
            }
        }
    }
}
