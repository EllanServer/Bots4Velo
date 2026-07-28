package dev.nulli0n.vbot;

import com.google.inject.Inject;
import com.velocitypowered.api.command.CommandManager;
import com.velocitypowered.api.command.CommandMeta;
import com.velocitypowered.api.event.Subscribe;
import com.velocitypowered.api.event.proxy.ProxyInitializeEvent;
import com.velocitypowered.api.event.proxy.ProxyShutdownEvent;
import com.velocitypowered.api.plugin.Plugin;
import com.velocitypowered.api.plugin.Dependency;
import com.velocitypowered.api.plugin.annotation.DataDirectory;
import com.velocitypowered.api.proxy.ConnectionRequestBuilder;
import com.velocitypowered.api.proxy.Player;
import com.velocitypowered.api.proxy.ProxyServer;
import com.velocitypowered.api.proxy.server.RegisteredServer;
import com.velocitypowered.api.scheduler.ScheduledTask;
import dev.nulli0n.vbot.bot.BotManager;
import dev.nulli0n.vbot.bot.BotSession;
import dev.nulli0n.vbot.bot.BotSnapshot;
import dev.nulli0n.vbot.bot.BotEvent;
import dev.nulli0n.vbot.api.Bots4VeloApi;
import dev.nulli0n.vbot.api.Bots4VeloApiProvider;
import dev.nulli0n.vbot.command.VBotCommand;
import dev.nulli0n.vbot.config.BotPluginConfig;
import dev.nulli0n.vbot.config.ConfigLoader;
import dev.nulli0n.vbot.config.ManagedBotStore;
import dev.nulli0n.vbot.message.PluginMessages;
import dev.nulli0n.vbot.observe.PrometheusExporter;
import dev.nulli0n.vbot.observe.WebhookNotifier;
import dev.nulli0n.vbot.tab.TabIntegration;
import dev.nulli0n.vbot.protocol.VelocityBackendProtocolDetector;
import dev.nulli0n.vbot.schedule.DailySchedule;
import org.slf4j.Logger;

import java.io.IOException;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.time.Duration;
import java.util.function.Consumer;

@Plugin(
    id = "bots4velo",
    name = "Bots4Velo",
    version = BuildConstants.VERSION,
    description = "Embedded multi-version headless Minecraft clients for Velocity",
    authors = {"OpenAI Codex"},
    dependencies = {@Dependency(id = "tab", optional = true)}
)
public final class Bots4VeloPlugin implements Bots4VeloApi {
    private final ProxyServer proxy;
    private final Logger logger;
    private final Path dataDirectory;
    private volatile BotPluginConfig config;
    private volatile BotManager manager;
    private volatile ManagedBotStore managedBotStore;
    private volatile PluginMessages messages;
    private final ConcurrentMap<String, ScheduledTask> followTasks = new ConcurrentHashMap<>();
    private final ConcurrentMap<String, ScheduledTask> scheduledActions = new ConcurrentHashMap<>();
    private final ConcurrentMap<String, ScheduledTask> presenceTasks = new ConcurrentHashMap<>();
    private volatile PrometheusExporter prometheusExporter;
    private volatile TabIntegration tabIntegration;
    private volatile ScheduledTask tabTask;

    @Inject
    public Bots4VeloPlugin(ProxyServer proxy, Logger logger, @DataDirectory Path dataDirectory) {
        this.proxy = proxy;
        this.logger = logger;
        this.dataDirectory = dataDirectory;
    }

    @Subscribe
    public void onInitialize(ProxyInitializeEvent event) {
        try {
            messages = PluginMessages.load(dataDirectory, logger);
            managedBotStore = ManagedBotStore.load(dataDirectory);
            config = ConfigLoader.load(dataDirectory, managedBotStore.definitions());
            manager = new BotManager(config, logger, new VelocityBackendProtocolDetector(proxy));
            registerOptionalIntegrations(manager, config);
            registerCommand();
            manager.startEnabled();
            startConfiguredFollows(config);
            startConfiguredSchedules(config);
            startPresenceRules(config);
            startPrometheus(manager, config);
            startTabIntegration(manager);
            Bots4VeloApiProvider.register(this);
            logger.info("bots4velo initialized with {} configured bot(s)", config.bots().size());
        }
        catch (Exception exception) {
            logger.error("bots4velo initialization failed", exception);
        }
    }

    private void registerCommand() {
        CommandManager commandManager = proxy.getCommandManager();
        CommandMeta meta = commandManager.metaBuilder("vbot").plugin(this).build();
        commandManager.register(meta, new VBotCommand(this));
    }

    /**
     * Parses and constructs a replacement manager before touching the live
     * manager. A malformed configuration therefore leaves live bots running.
     */
    public synchronized ReloadResult reload() throws IOException {
        PluginMessages replacementMessages = PluginMessages.load(dataDirectory, logger);
        ManagedBotStore replacementStore = ManagedBotStore.load(dataDirectory);
        BotPluginConfig replacementConfig = ConfigLoader.load(dataDirectory, replacementStore.definitions());
        BotManager replacement = new BotManager(replacementConfig, logger,
            new VelocityBackendProtocolDetector(proxy));
        registerOptionalIntegrations(replacement, replacementConfig);
        try {
            BotManager previous = manager;
            managedBotStore = replacementStore;
            messages = replacementMessages;
            config = replacementConfig;
            manager = replacement;
            if (previous != null) {
                previous.close();
            }
            replacement.startEnabled();
            followTasks.values().forEach(ScheduledTask::cancel);
            followTasks.clear();
            startConfiguredFollows(replacementConfig);
            scheduledActions.values().forEach(ScheduledTask::cancel);
            scheduledActions.clear();
            startConfiguredSchedules(replacementConfig);
            presenceTasks.values().forEach(ScheduledTask::cancel);
            presenceTasks.clear();
            startPresenceRules(replacementConfig);
            stopPrometheus();
            startPrometheus(replacement, replacementConfig);
            stopTabIntegration();
            startTabIntegration(replacement);
            return new ReloadResult(replacementConfig.bots().size(), replacementStore.definitions().size());
        }
        catch (RuntimeException exception) {
            replacement.close();
            throw exception;
        }
    }

    public BotPluginConfig validateConfiguration() throws IOException {
        ManagedBotStore store = ManagedBotStore.load(dataDirectory);
        return ConfigLoader.load(dataDirectory, store.definitions());
    }

    public synchronized ManagedCreateResult createManagedBot(String id, String username, String password,
                                                               String targetServer) throws IOException {
        BotManager active = manager();
        if (active.find(id).isPresent()) {
            return ManagedCreateResult.ALREADY_EXISTS;
        }
        if (active.snapshots().size() >= active.maximumBots()) {
            return ManagedCreateResult.LIMIT_REACHED;
        }

        BotPluginConfig.BotDefinition definition = ManagedBotStore.createDefinition(
            id, username, password, targetServer);
        ManagedBotStore store = managedBotStore;
        store.add(definition);
        BotManager.CreateResult result = active.create(definition);
        if (result != BotManager.CreateResult.CREATED) {
            store.remove(definition.id());
            return result == BotManager.CreateResult.LIMIT_REACHED
                ? ManagedCreateResult.LIMIT_REACHED : ManagedCreateResult.ALREADY_EXISTS;
        }
        return ManagedCreateResult.CREATED;
    }

    public synchronized ManagedRemoveResult removeManagedBot(String id) throws IOException {
        BotManager active = manager();
        ManagedBotStore store = managedBotStore;
        if (!store.contains(id)) {
            return active.find(id).isPresent()
                ? ManagedRemoveResult.STATIC_BOT : ManagedRemoveResult.NOT_FOUND;
        }
        store.remove(id);
        stopFollowing(id);
        active.remove(id);
        return ManagedRemoveResult.REMOVED;
    }

    @Subscribe
    public void onShutdown(ProxyShutdownEvent event) {
        Bots4VeloApiProvider.clear(this);
        followTasks.values().forEach(ScheduledTask::cancel);
        followTasks.clear();
        scheduledActions.values().forEach(ScheduledTask::cancel);
        scheduledActions.clear();
        presenceTasks.values().forEach(ScheduledTask::cancel);
        presenceTasks.clear();
        stopPrometheus();
        stopTabIntegration();
        BotManager active = manager;
        if (active != null) {
            active.close();
        }
    }

    public BotManager manager() {
        BotManager active = manager;
        if (active == null) {
            throw new IllegalStateException("Bot manager is not initialized");
        }
        return active;
    }

    @Override
    public List<BotSnapshot> bots() {
        return manager().snapshots();
    }

    @Override
    public Optional<BotSnapshot> bot(String id) {
        return manager().find(id).map(BotSession::snapshot);
    }

    @Override
    public boolean start(String id) {
        return manager().start(id);
    }

    @Override
    public boolean stop(String id) {
        return manager().stop(id);
    }

    @Override
    public boolean reconnect(String id) {
        return manager().reconnect(id);
    }

    @Override
    public void addEventListener(Consumer<BotEvent> listener) {
        manager().addEventListener(listener);
    }

    @Override
    public void removeEventListener(Consumer<BotEvent> listener) {
        manager().removeEventListener(listener);
    }

    public Logger logger() {
        return logger;
    }

    public PluginMessages messages() {
        PluginMessages active = messages;
        if (active == null) {
            throw new IllegalStateException("Messages are not initialized");
        }
        return active;
    }

    public ProxyServer proxy() {
        return proxy;
    }

    public List<String> serverNames() {
        return proxy.getAllServers().stream()
            .map(server -> server.getServerInfo().getName())
            .sorted(String.CASE_INSENSITIVE_ORDER)
            .toList();
    }

    public List<BotSession> selectBots(String selector) {
        List<BotSession> candidates = manager().select(selector);
        String normalized = selector == null ? "" : selector.trim();
        if (!normalized.regionMatches(true, 0, "@server:", 0, "@server:".length())) {
            return candidates;
        }
        String server = normalized.substring("@server:".length()).trim();
        if (server.isBlank()) {
            return List.of();
        }
        return candidates.stream().filter(session -> currentServer(session.definition().id())
            .map(current -> current.equalsIgnoreCase(server)).orElse(false)).toList();
    }

    public Optional<String> currentServer(String botId) {
        return manager().find(botId)
            .flatMap(session -> proxy.getPlayer(session.definition().username()))
            .flatMap(Player::getCurrentServer)
            .map(connection -> connection.getServerInfo().getName());
    }

    public CompletableFuture<BotServerSwitchResult> switchBotServer(String botId, String serverName) {
        Optional<BotSession> found = manager().find(botId);
        if (found.isEmpty()) {
            return CompletableFuture.completedFuture(new BotServerSwitchResult(
                BotServerSwitchStatus.BOT_NOT_FOUND, botId, "", serverName, "unknown bot"));
        }

        BotSession session = found.get();
        Optional<RegisteredServer> destination = findServer(serverName);
        if (destination.isEmpty()) {
            return CompletableFuture.completedFuture(new BotServerSwitchResult(
                BotServerSwitchStatus.SERVER_NOT_FOUND, session.definition().id(),
                session.definition().username(), serverName, "unknown Velocity server"));
        }
        String canonicalServerName = destination.get().getServerInfo().getName();
        if (!session.isPlayable()) {
            return CompletableFuture.completedFuture(new BotServerSwitchResult(
                BotServerSwitchStatus.BOT_NOT_READY, session.definition().id(),
                session.definition().username(), canonicalServerName, "bot is not in PLAY"));
        }
        if (!session.isAuthenticationComplete()) {
            return CompletableFuture.completedFuture(new BotServerSwitchResult(
                BotServerSwitchStatus.AUTHENTICATION_PENDING, session.definition().id(),
                session.definition().username(), canonicalServerName,
                "authentication has not completed"));
        }

        Optional<Player> onlineBot = proxy.getPlayer(session.definition().username());
        if (onlineBot.isEmpty()) {
            return CompletableFuture.completedFuture(new BotServerSwitchResult(
                BotServerSwitchStatus.BOT_NOT_READY, session.definition().id(),
                session.definition().username(), canonicalServerName, "bot is not visible to Velocity"));
        }

        session.prepareExternalServerSwitch();
        Player player = onlineBot.get();
        boolean alreadyConnected = player.getCurrentServer()
            .map(connection -> connection.getServerInfo().getName().equalsIgnoreCase(canonicalServerName))
            .orElse(false);
        if (alreadyConnected) {
            return CompletableFuture.completedFuture(new BotServerSwitchResult(
                BotServerSwitchStatus.ALREADY_CONNECTED, session.definition().id(),
                session.definition().username(), canonicalServerName, "already connected"));
        }

        return player.createConnectionRequest(destination.get()).connect().handle((result, throwable) -> {
            if (throwable != null) {
                logger.warn("Bot {} could not switch to server {}", session.definition().id(),
                    canonicalServerName, throwable);
                String detail = throwable.getMessage() == null
                    ? throwable.getClass().getSimpleName() : throwable.getMessage();
                return new BotServerSwitchResult(BotServerSwitchStatus.FAILED,
                    session.definition().id(), session.definition().username(), canonicalServerName, detail);
            }
            ConnectionRequestBuilder.Status status = result.getStatus();
            if (status == ConnectionRequestBuilder.Status.SUCCESS) {
                return new BotServerSwitchResult(BotServerSwitchStatus.SWITCHED,
                    session.definition().id(), session.definition().username(), canonicalServerName, status.name());
            }
            if (status == ConnectionRequestBuilder.Status.ALREADY_CONNECTED) {
                return new BotServerSwitchResult(BotServerSwitchStatus.ALREADY_CONNECTED,
                    session.definition().id(), session.definition().username(), canonicalServerName, status.name());
            }
            return new BotServerSwitchResult(BotServerSwitchStatus.FAILED,
                session.definition().id(), session.definition().username(), canonicalServerName, status.name());
        });
    }

    public FollowResult startFollowing(String botId, String playerName) {
        Optional<BotSession> found = manager().find(botId);
        if (found.isEmpty()) {
            return new FollowResult(false, "unknown bot");
        }
        String target = playerName == null ? "" : playerName.trim();
        if (target.isBlank() || target.equalsIgnoreCase(found.get().definition().username())) {
            return new FollowResult(false, "a different online player name is required");
        }
        stopFollowing(botId);
        found.get().setFollowTarget(target);
        ScheduledTask task = proxy.getScheduler().buildTask(this, () -> followTick(found.get().definition().id(), target))
            .repeat(Duration.ofSeconds(3)).schedule();
        followTasks.put(found.get().definition().id().toLowerCase(java.util.Locale.ROOT), task);
        followTick(found.get().definition().id(), target);
        return new FollowResult(true, "following " + target);
    }

    public boolean stopFollowing(String botId) {
        Optional<BotSession> found = manager().find(botId);
        found.ifPresent(session -> session.setFollowTarget(""));
        ScheduledTask task = followTasks.remove(botId == null ? "" : botId.trim().toLowerCase(java.util.Locale.ROOT));
        if (task != null) {
            task.cancel();
        }
        return found.isPresent();
    }

    private void followTick(String botId, String playerName) {
        Optional<BotSession> session = manager().find(botId);
        Optional<Player> target = proxy.getPlayer(playerName);
        if (session.isEmpty() || target.isEmpty() || !session.get().isPlayable()
            || !session.get().isAuthenticationComplete()) {
            return;
        }
        Optional<String> server = target.get().getCurrentServer().map(connection -> connection.getServerInfo().getName());
        if (server.isEmpty()) {
            return;
        }
        currentServer(botId).filter(current -> current.equalsIgnoreCase(server.get())).ifPresentOrElse(current ->
            target.get().spoofChatInput("/minecraft:tp " + session.get().definition().username() + " "
                + target.get().getUsername()), () -> switchBotServer(botId, server.get()).thenAccept(result -> {
                if (result.successful()) {
                    proxy.getScheduler().buildTask(this, () -> {
                        if (target.get().getCurrentServer().map(connection -> connection.getServerInfo().getName()
                            .equalsIgnoreCase(result.server())).orElse(false)) {
                            target.get().spoofChatInput("/minecraft:tp " + result.username() + " "
                                + target.get().getUsername());
                        }
                    }).delay(Duration.ofMillis(750)).schedule();
                }
            }));
    }

    private void startConfiguredFollows(BotPluginConfig candidate) {
        candidate.bots().values().stream()
            .filter(definition -> definition.enabled()
                && definition.behavior().mode() == BotPluginConfig.BehaviorMode.FOLLOW
                && !definition.behavior().followPlayer().isBlank())
            .forEach(definition -> startFollowing(definition.id(), definition.behavior().followPlayer()));
    }

    private void startConfiguredSchedules(BotPluginConfig candidate) {
        for (BotPluginConfig.ScheduledAction action : candidate.runtime().schedules()) {
            if (action.runsDailyAtConfiguredTime()) {
                scheduleNextDailyAction(action);
                continue;
            }
            ScheduleTiming timing = scheduleTiming(action);
            ScheduledTask task = proxy.getScheduler().buildTask(this, () -> runScheduledAction(action))
                .delay(timing.initialDelay())
                .repeat(timing.interval()).schedule();
            scheduledActions.put(action.id().toLowerCase(java.util.Locale.ROOT), task);
        }
    }

    private ScheduleTiming scheduleTiming(BotPluginConfig.ScheduledAction action) {
        return new ScheduleTiming(Duration.ofMillis(action.initialDelayMillis()),
            Duration.ofMillis(action.intervalMillis()));
    }

    private void scheduleNextDailyAction(BotPluginConfig.ScheduledAction action) {
        Duration delay = DailySchedule.delayUntilNext(action.at(), action.timezone(), Instant.now());
        ScheduledTask task = proxy.getScheduler().buildTask(this, () -> {
            runScheduledAction(action);
            // Calculate the next local wall-clock occurrence after every run,
            // so a daylight-saving transition does not shift the configured time.
            scheduleNextDailyAction(action);
        }).delay(delay).schedule();
        scheduledActions.put(action.id().toLowerCase(java.util.Locale.ROOT), task);
    }

    private void runScheduledAction(BotPluginConfig.ScheduledAction action) {
        List<BotSession> targets = selectBots(action.selector());
        if (targets.isEmpty()) {
            logger.debug("Scheduled action {} matched no bots", action.id());
            return;
        }
        for (BotSession session : targets) {
            switch (action.action()) {
                case "start" -> manager().start(session.definition().id());
                case "stop" -> manager().stop(session.definition().id());
                case "reconnect" -> manager().reconnect(session.definition().id());
                case "server" -> switchBotServer(session.definition().id(), action.server());
                default -> logger.warn("Ignoring unknown scheduled action {}", action.action());
            }
        }
    }

    private void registerOptionalIntegrations(BotManager candidate, BotPluginConfig candidateConfig) {
        if (!candidateConfig.runtime().webhookUrl().isBlank()) {
            try {
                candidate.addEventListener(new WebhookNotifier(candidateConfig.runtime().webhookUrl(), logger));
            }
            catch (IllegalArgumentException exception) {
                logger.warn("Ignoring invalid runtime.webhook-url", exception);
            }
        }
    }

    private void startPrometheus(BotManager candidate, BotPluginConfig candidateConfig) {
        int port = candidateConfig.runtime().prometheusPort();
        if (port <= 0) {
            return;
        }
        try {
            prometheusExporter = new PrometheusExporter(candidateConfig.runtime().prometheusAddress(), port,
                candidate::snapshots, logger);
        }
        catch (IOException exception) {
            logger.error("Could not start Prometheus metrics on {}:{}",
                candidateConfig.runtime().prometheusAddress(), port, exception);
        }
    }

    private void stopPrometheus() {
        PrometheusExporter active = prometheusExporter;
        prometheusExporter = null;
        if (active != null) {
            active.close();
        }
    }

    private void startTabIntegration(BotManager candidate) {
        if (proxy.getPluginManager().getPlugin("tab").isEmpty()) {
            return;
        }
        try {
            TabIntegration integration = new TabIntegration(candidate, logger);
            tabIntegration = integration;
            tabTask = proxy.getScheduler().buildTask(this, integration::apply)
                .repeat(Duration.ofSeconds(1)).schedule();
            integration.apply();
            logger.info("TAB integration enabled; use {} in TAB formatting", TabIntegration.GROUP_PLACEHOLDER);
        }
        catch (RuntimeException | LinkageError exception) {
            logger.warn("TAB is installed but its API could not be initialized", exception);
        }
    }

    private void stopTabIntegration() {
        ScheduledTask task = tabTask;
        tabTask = null;
        if (task != null) {
            task.cancel();
        }
        TabIntegration active = tabIntegration;
        tabIntegration = null;
        if (active != null) {
            active.close();
        }
    }

    private void startPresenceRules(BotPluginConfig candidate) {
        for (BotPluginConfig.PresenceRule rule : candidate.runtime().presenceRules()) {
            ScheduledTask task = proxy.getScheduler().buildTask(this, () -> applyPresenceRule(rule))
                .repeat(Duration.ofMillis(rule.intervalMillis())).schedule();
            presenceTasks.put(rule.id().toLowerCase(java.util.Locale.ROOT), task);
            applyPresenceRule(rule);
        }
    }

    private void applyPresenceRule(BotPluginConfig.PresenceRule rule) {
        Optional<RegisteredServer> backend = findServer(rule.server());
        if (backend.isEmpty()) {
            logger.warn("Presence rule {} refers to unknown server {}", rule.id(), rule.server());
            return;
        }
        // A registered Velocity backend can still be down for maintenance.
        // Wait for a successful ping instead of repeatedly moving bots into a
        // failed connection. The next scheduled tick resumes assignments, and
        // BotManager's shared connection limiter batches their re-entry.
        backend.get().ping().whenComplete((ignored, failure) -> {
            if (failure != null) {
                logger.debug("Presence rule {} is waiting for backend {} to recover", rule.id(), rule.server());
                return;
            }
            applyPresenceRuleToHealthyBackend(rule, backend.get());
        });
    }

    private void applyPresenceRuleToHealthyBackend(BotPluginConfig.PresenceRule rule, RegisteredServer backend) {
        java.util.Set<String> botNames = manager().snapshots().stream()
            .map(snapshot -> snapshot.username().toLowerCase(java.util.Locale.ROOT)).collect(java.util.stream.Collectors.toSet());
        long humans = backend.getPlayersConnected().stream()
            .filter(player -> !botNames.contains(player.getUsername().toLowerCase(java.util.Locale.ROOT))).count();
        int desired = humans <= rule.maximumHumans() ? rule.minimumBots() : 0;
        List<BotSession> candidates = selectBots(rule.selector());
        for (int index = 0; index < candidates.size(); index++) {
            BotSession session = candidates.get(index);
            if (index < desired) {
                manager().start(session.definition().id());
                switchBotServer(session.definition().id(), backend.getServerInfo().getName());
            }
            else {
                manager().stop(session.definition().id());
            }
        }
    }

    private Optional<RegisteredServer> findServer(String name) {
        String wanted = name == null ? "" : name.trim();
        if (wanted.isEmpty()) {
            return Optional.empty();
        }
        Optional<RegisteredServer> exact = proxy.getServer(wanted);
        if (exact.isPresent()) {
            return exact;
        }
        return proxy.getAllServers().stream()
            .filter(server -> server.getServerInfo().getName().equalsIgnoreCase(wanted))
            .findFirst();
    }

    public enum BotServerSwitchStatus {
        SWITCHED,
        ALREADY_CONNECTED,
        BOT_NOT_FOUND,
        BOT_NOT_READY,
        AUTHENTICATION_PENDING,
        SERVER_NOT_FOUND,
        FAILED
    }

    public record BotServerSwitchResult(
        BotServerSwitchStatus status,
        String botId,
        String username,
        String server,
        String detail
    ) {
        public boolean successful() {
            return status == BotServerSwitchStatus.SWITCHED
                || status == BotServerSwitchStatus.ALREADY_CONNECTED;
        }
    }

    public enum ManagedCreateResult {
        CREATED,
        ALREADY_EXISTS,
        LIMIT_REACHED
    }

    public record ReloadResult(int configuredBots, int managedBots) {
    }

    private record ScheduleTiming(Duration initialDelay, Duration interval) {
    }

    public record FollowResult(boolean successful, String detail) {
    }

    public enum ManagedRemoveResult {
        REMOVED,
        STATIC_BOT,
        NOT_FOUND
    }
}
