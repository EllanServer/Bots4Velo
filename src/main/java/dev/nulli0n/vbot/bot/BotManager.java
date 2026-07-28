package dev.nulli0n.vbot.bot;

import dev.nulli0n.vbot.config.BotPluginConfig;
import dev.nulli0n.vbot.config.BotPluginConfig.BotDefinition;
import dev.nulli0n.vbot.protocol.ProtocolResolver;
import dev.nulli0n.vbot.protocol.ProtocolDetectionService;
import dev.nulli0n.vbot.protocol.StatusProtocolDetector;
import dev.nulli0n.vbot.protocol.TransportRegistry;
import org.slf4j.Logger;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

public final class BotManager implements AutoCloseable {
    private final BotPluginConfig config;
    private final Logger logger;
    private final ScheduledExecutorService executor;
    private final ProtocolDetectionService protocolDetectionService;
    private final TransportRegistry transportRegistry;
    private final ConnectionRateLimiter connectionRateLimiter;
    private final Map<String, BotSession> sessions = new ConcurrentHashMap<>();
    private final Map<String, ScheduledFuture<?>> pendingActivations = new ConcurrentHashMap<>();
    private final Object activationLock = new Object();
    private long nextActivationNanos;

    public BotManager(BotPluginConfig config, Logger logger) {
        this(config, logger, (definition, endpoint) -> new StatusProtocolDetector().detect(endpoint));
    }

    public BotManager(BotPluginConfig config, Logger logger,
                      ProtocolDetectionService protocolDetectionService) {
        this.config = config;
        this.logger = logger;
        this.protocolDetectionService = protocolDetectionService;
        AtomicInteger threadId = new AtomicInteger();
        int threads = Math.max(2, Math.min(8, config.bots().size() + 1));
        this.executor = Executors.newScheduledThreadPool(threads, runnable -> {
            Thread thread = new Thread(runnable, "vbot-worker-" + threadId.incrementAndGet());
            thread.setDaemon(true);
            return thread;
        });
        this.transportRegistry = new TransportRegistry();
        this.connectionRateLimiter = new ConnectionRateLimiter(config.runtime().spawnIntervalMillis());
        config.bots().forEach((key, definition) -> sessions.put(key,
            createSession(definition)));
    }

    public void startEnabled() {
        for (BotSession session : sortedSessions()) {
            if (session.definition().enabled()) {
                scheduleActivation(session, config.runtime().autoStartDelayMillis(), session::start);
            }
        }
    }

    public Optional<BotSession> find(String id) {
        return Optional.ofNullable(sessions.get(id.toLowerCase(Locale.ROOT)));
    }

    public List<BotSnapshot> snapshots() {
        return sortedSessions().stream().map(BotSession::snapshot).toList();
    }

    /**
     * Resolves a stable, local bot selector. Server selectors are completed by
     * the Velocity-facing plugin because only it can observe current backend
     * connections. Supported local selectors are an id, {@code all},
     * {@code @group:<name>}, {@code @tag:<name>} and the compact
     * {@code @<name>} form (group or tag).
     */
    public List<BotSession> select(String selector) {
        String normalized = selector == null ? "" : selector.trim().toLowerCase(Locale.ROOT);
        if (normalized.isEmpty()) {
            return List.of();
        }
        if (normalized.equals("all") || normalized.equals("@all") || normalized.equals("*")) {
            return sortedSessions();
        }
        if (!normalized.startsWith("@")) {
            return find(normalized).map(List::of).orElseGet(List::of);
        }
        String expression = normalized.substring(1);
        if (expression.startsWith("group:")) {
            return byLabel(expression.substring("group:".length()), true);
        }
        if (expression.startsWith("tag:")) {
            return byLabel(expression.substring("tag:".length()), false);
        }
        if (expression.startsWith("server:")) {
            return sortedSessions();
        }
        return sortedSessions().stream()
            .filter(session -> session.definition().groups().contains(expression)
                || session.definition().tags().contains(expression))
            .toList();
    }

    public List<String> groups() {
        return sortedSessions().stream().flatMap(session -> session.definition().groups().stream())
            .distinct().sorted(String.CASE_INSENSITIVE_ORDER).toList();
    }

    public List<String> tags() {
        return sortedSessions().stream().flatMap(session -> session.definition().tags().stream())
            .distinct().sorted(String.CASE_INSENSITIVE_ORDER).toList();
    }

    public boolean start(String id) {
        return find(id).map(session -> {
            scheduleActivation(session, 0, session::start);
            return true;
        }).orElse(false);
    }

    public boolean stop(String id) {
        return find(id).map(session -> {
            cancelActivation(session.definition().id());
            session.stop();
            return true;
        }).orElse(false);
    }

    public boolean reconnect(String id) {
        return find(id).map(session -> {
            scheduleActivation(session, 0, session::reconnectNow);
            return true;
        }).orElse(false);
    }

    public CreateResult create(BotDefinition definition) {
        if (sessions.size() >= config.runtime().maximumBots()) {
            return CreateResult.LIMIT_REACHED;
        }
        String key = definition.id().toLowerCase(Locale.ROOT);
        BotSession session = createSession(definition);
        if (sessions.putIfAbsent(key, session) != null) {
            return CreateResult.ALREADY_EXISTS;
        }
        if (definition.enabled()) {
            scheduleActivation(session, 0, session::start);
        }
        return CreateResult.CREATED;
    }

    public boolean remove(String id) {
        String key = id.toLowerCase(Locale.ROOT);
        BotSession removed = sessions.remove(key);
        if (removed == null) {
            return false;
        }
        cancelActivation(key);
        removed.stop();
        return true;
    }

    public int maximumBots() {
        return config.runtime().maximumBots();
    }

    public boolean command(String id, String command) {
        return find(id).map(session -> session.sendCommand(command)).orElse(false);
    }

    public boolean moveTo(String id, double x, double y, double z) {
        return find(id).map(session -> session.moveTo(x, y, z)).orElse(false);
    }

    public boolean look(String id, float yaw, float pitch) {
        return find(id).map(session -> session.look(yaw, pitch)).orElse(false);
    }

    private List<BotSession> sortedSessions() {
        List<BotSession> result = new ArrayList<>(sessions.values());
        result.sort(Comparator.comparing(session -> session.definition().id(), String.CASE_INSENSITIVE_ORDER));
        return result;
    }

    private List<BotSession> byLabel(String label, boolean group) {
        if (label.isBlank()) {
            return List.of();
        }
        return sortedSessions().stream()
            .filter(session -> (group ? session.definition().groups() : session.definition().tags()).contains(label))
            .toList();
    }

    private BotSession createSession(BotDefinition definition) {
        ProtocolResolver resolver = new ProtocolResolver(config.proxy(), definition, protocolDetectionService);
        return new BotSession(definition, config.proxy(), config.runtime(), resolver,
            transportRegistry, connectionRateLimiter, executor, logger);
    }

    private void scheduleActivation(BotSession session, long minimumDelayMillis, Runnable action) {
        String key = session.definition().id().toLowerCase(Locale.ROOT);
        synchronized (activationLock) {
            ScheduledFuture<?> existing = pendingActivations.get(key);
            if (existing != null && !existing.isDone()) {
                return;
            }
            long now = System.nanoTime();
            long earliest = now + TimeUnit.MILLISECONDS.toNanos(Math.max(0, minimumDelayMillis));
            long scheduledAt = Math.max(earliest, nextActivationNanos);
            nextActivationNanos = scheduledAt
                + TimeUnit.MILLISECONDS.toNanos(config.runtime().spawnIntervalMillis());
            long delayNanos = Math.max(0, scheduledAt - now);
            ScheduledFuture<?> future = executor.schedule(() -> {
                synchronized (activationLock) {
                    pendingActivations.remove(key);
                }
                if (sessions.get(key) == session) {
                    action.run();
                }
            }, delayNanos, TimeUnit.NANOSECONDS);
            pendingActivations.put(key, future);
        }
    }

    private void cancelActivation(String id) {
        String key = id.toLowerCase(Locale.ROOT);
        synchronized (activationLock) {
            ScheduledFuture<?> pending = pendingActivations.remove(key);
            if (pending != null) {
                pending.cancel(false);
            }
        }
    }

    @Override
    public void close() {
        synchronized (activationLock) {
            pendingActivations.values().forEach(future -> future.cancel(false));
            pendingActivations.clear();
        }
        sessions.values().forEach(BotSession::stop);
        executor.shutdown();
        try {
            if (!executor.awaitTermination(5, TimeUnit.SECONDS)) {
                logger.warn("Bot worker pool did not stop within five seconds");
                executor.shutdownNow();
            }
        }
        catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            executor.shutdownNow();
        }
    }

    public enum CreateResult {
        CREATED,
        ALREADY_EXISTS,
        LIMIT_REACHED
    }
}
