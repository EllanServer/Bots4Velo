package dev.nulli0n.vbot;

import com.google.inject.Inject;
import com.velocitypowered.api.command.CommandManager;
import com.velocitypowered.api.command.CommandMeta;
import com.velocitypowered.api.event.Subscribe;
import com.velocitypowered.api.event.proxy.ProxyInitializeEvent;
import com.velocitypowered.api.event.proxy.ProxyShutdownEvent;
import com.velocitypowered.api.plugin.Plugin;
import com.velocitypowered.api.plugin.annotation.DataDirectory;
import com.velocitypowered.api.proxy.ConnectionRequestBuilder;
import com.velocitypowered.api.proxy.Player;
import com.velocitypowered.api.proxy.ProxyServer;
import com.velocitypowered.api.proxy.server.RegisteredServer;
import dev.nulli0n.vbot.bot.BotManager;
import dev.nulli0n.vbot.bot.BotSession;
import dev.nulli0n.vbot.command.VBotCommand;
import dev.nulli0n.vbot.config.BotPluginConfig;
import dev.nulli0n.vbot.config.ConfigLoader;
import dev.nulli0n.vbot.config.ManagedBotStore;
import dev.nulli0n.vbot.message.PluginMessages;
import dev.nulli0n.vbot.protocol.VelocityBackendProtocolDetector;
import org.slf4j.Logger;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;

@Plugin(
    id = "bots4velo",
    name = "Bots4Velo",
    version = BuildConstants.VERSION,
    description = "Embedded multi-version headless Minecraft clients for Velocity",
    authors = {"OpenAI Codex"}
)
public final class Bots4VeloPlugin {
    private final ProxyServer proxy;
    private final Logger logger;
    private final Path dataDirectory;
    private volatile BotPluginConfig config;
    private volatile BotManager manager;
    private volatile ManagedBotStore managedBotStore;
    private volatile PluginMessages messages;

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
            registerCommand();
            manager.startEnabled();
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
        active.remove(id);
        return ManagedRemoveResult.REMOVED;
    }

    @Subscribe
    public void onShutdown(ProxyShutdownEvent event) {
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

    public enum ManagedRemoveResult {
        REMOVED,
        STATIC_BOT,
        NOT_FOUND
    }
}
