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
import java.util.Arrays;
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
        return send(botId, BackendOperation.PROBE, null);
    }

    @Override
    public CompletionStage<BackendControlResult> apply(String botId, BackendControlPatch patch) {
        if (patch == null) {
            return completedFailure(botId, BackendStatus.BAD_REQUEST, "A player-state change is required.");
        }
        Optional<BotSession> session = findSession(botId);
        if (session.isEmpty()) {
            return completedFailure(botId, BackendStatus.BAD_REQUEST, "Unknown bot.");
        }
        String canonicalId = session.get().definition().id();
        return enqueue(canonicalId, () -> applySerialized(canonicalId, patch));
    }

    @Override
    public CompletionStage<BackendControlResult> respawn(String botId) {
        Optional<BotSession> session = findSession(botId);
        if (session.isEmpty()) {
            return completedFailure(botId, BackendStatus.BAD_REQUEST, "Unknown bot.");
        }
        String canonicalId = session.get().definition().id();
        return enqueue(canonicalId, () -> send(canonicalId, BackendOperation.RESPAWN, null));
    }

    private CompletionStage<BackendControlResult> applySerialized(String botId, BackendControlPatch patch) {
        Optional<BotSession> session = findSession(botId);
        if (session.isEmpty()) {
            return completedFailure(botId, BackendStatus.BAD_REQUEST, "Unknown bot.");
        }
        BackendPolicy previous = desiredPolicy(session.get());
        BackendPolicy replacement = merge(previous, patch);
        return send(botId, BackendOperation.APPLY_POLICY, replacement).thenApply(result -> {
            // A timeout is ambiguous: Paper may have applied the request and only
            // its ACK was lost. Retain the intent so a later switch/reconnect
            // converges both sides instead of silently restoring stale state.
            if (result.successful() || result.status() == BackendStatus.TIMEOUT) {
                setDesiredPolicy(session.get(), replacement);
            }
            return result;
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
        findSession(request.botId()).ifPresent(session -> session.recordExternalEvent(
            "BACKEND_CONTROL", response.operation() + ":" + response.status()));
        request.future().complete(result);
        return true;
    }

    /** Schedules desired state after login/authentication and every backend switch. */
    public void handleServerPostConnect(Player player) {
        if (!enabled() || player == null) {
            return;
        }
        findSessionByUsername(player.getUsername()).ifPresent(session -> {
            String key = normalize(session.definition().id());
            desiredPolicy(session);
            long currentGeneration = generation.incrementAndGet();
            reapplyGenerations.put(key, currentGeneration);
            long delay = session.definition().playerState().applyDelayMillis();
            long authBudget = Math.max(30_000L, session.definition().auth().timeoutMillis() + 5_000L);
            long deadlineNanos = System.nanoTime()
                + java.util.concurrent.TimeUnit.MILLISECONDS.toNanos(authBudget + delay);
            scheduleReadyApply(session.definition().id(), currentGeneration, deadlineNanos, delay);
        });
    }

    private void scheduleReadyApply(String botId, long expectedGeneration, long deadlineNanos, long delayMillis) {
        proxy.getScheduler().buildTask(plugin,
            () -> readyApply(botId, expectedGeneration, deadlineNanos))
            .delay(Duration.ofMillis(Math.max(0L, delayMillis))).schedule();
    }

    private void readyApply(String botId, long expectedGeneration, long deadlineNanos) {
        if (closed || !reapplyGenerations.getOrDefault(normalize(botId), -1L).equals(expectedGeneration)) {
            return;
        }
        Optional<BotSession> session = findSession(botId);
        Optional<Player> player = session.flatMap(found -> proxy.getPlayer(found.definition().username()));
        boolean ready = session.map(found -> found.isPlayable() && found.isAuthenticationComplete()).orElse(false)
            && player.flatMap(Player::getCurrentServer).isPresent();
        if (!ready) {
            if (System.nanoTime() < deadlineNanos) {
                scheduleReadyApply(botId, expectedGeneration, deadlineNanos, 250L);
            }
            else {
                logger.warn("Bot {} never became ready for configured Paper player state", botId);
                reapplyGenerations.remove(normalize(botId), expectedGeneration);
            }
            return;
        }

        enqueue(botId, () -> {
            BackendPolicy latestPolicy = findSession(botId).map(this::desiredPolicy)
                .orElse(BackendPolicy.unchanged());
            return send(botId, BackendOperation.APPLY_POLICY, latestPolicy);
        }).whenComplete((result, throwable) -> {
            reapplyGenerations.remove(normalize(botId), expectedGeneration);
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

    private CompletionStage<BackendControlResult> send(String requestedBotId, BackendOperation operation,
                                                        BackendPolicy policy) {
        String safeBotId = requestedBotId == null ? "" : requestedBotId.trim();
        if (!enabled()) {
            return completedFailure(safeBotId, BackendStatus.PLUGIN_MISSING,
                "Paper backend control is disabled. Enable runtime.backend-control and install Bots4VeloPaper.");
        }
        Optional<BotSession> found = findSession(safeBotId);
        if (found.isEmpty()) {
            return completedFailure(safeBotId, BackendStatus.BAD_REQUEST, "Unknown bot.");
        }
        BotSession session = found.get();
        if (!session.isPlayable() || !session.isAuthenticationComplete()) {
            return completedFailure(session.definition().id(), BackendStatus.BOT_NOT_ON_SERVER,
                "The bot is not in PLAY or authentication is still pending.");
        }
        Optional<Player> player = proxy.getPlayer(session.definition().username());
        Optional<ServerConnection> connection = player.flatMap(Player::getCurrentServer);
        if (player.isEmpty() || connection.isEmpty()) {
            return completedFailure(session.definition().id(), BackendStatus.BOT_NOT_ON_SERVER,
                "The bot is not connected to a Velocity backend.");
        }

        ControlRequest request = ControlRequest.create(player.get().getUniqueId(), operation, policy);
        byte[] payload;
        try {
            payload = ProtocolCodec.encodeRequest(request, secret);
        }
        catch (RuntimeException exception) {
            return completedFailure(session.definition().id(), BackendStatus.BAD_REQUEST,
                "Could not encode the backend request: " + safeMessage(exception));
        }

        CompletableFuture<BackendControlResult> future = new CompletableFuture<>();
        PendingRequest pendingRequest = new PendingRequest(session.definition().id(), request,
            player.get(), connection.get(), payload, future);
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
            pending.put(request.requestId(), pendingRequest);
            try {
                boolean sent = connection.get().sendPluginMessage(CHANNEL, payload);
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
                        findSession(session.definition().id()).ifPresent(foundSession ->
                            foundSession.recordExternalEvent("BACKEND_CONTROL",
                                operation + ":" + BackendStatus.TIMEOUT));
                        String timeoutDetail = "No signed Paper acknowledgement arrived within "
                            + timeoutMillis + " ms after an idempotent retry.";
                        if (operation == BackendOperation.APPLY_POLICY) {
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
        return new BackendPolicy(invulnerability, gameMode, respawnPoint);
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
        return new BackendPolicy(invulnerability, gameMode, point);
    }

    static boolean isUnchanged(BackendPolicy policy) {
        return policy.invulnerability() == BackendInvulnerability.UNCHANGED
            && policy.gameMode() == BackendGameMode.UNCHANGED
            && policy.respawnPoint().mode() == RespawnMode.UNCHANGED;
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

    private static final class PendingRequest {
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

        private PendingRequest(String botId, ControlRequest request, Player player,
                               ServerConnection connection, byte[] payload,
                               CompletableFuture<BackendControlResult> future) {
            this.botId = botId;
            this.targetUuid = request.targetUuid();
            this.requestId = request.requestId();
            this.nonce = request.nonce();
            this.operation = request.operation();
            this.player = player;
            this.connection = connection;
            this.payload = payload.clone();
            this.future = future;
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
