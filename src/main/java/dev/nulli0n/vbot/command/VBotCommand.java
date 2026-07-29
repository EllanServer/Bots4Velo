package dev.nulli0n.vbot.command;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.velocitypowered.api.command.CommandSource;
import com.velocitypowered.api.command.SimpleCommand;
import com.velocitypowered.api.proxy.Player;
import dev.nulli0n.vbot.Bots4VeloPlugin;
import dev.nulli0n.vbot.Bots4VeloPlugin.BotServerSwitchResult;
import dev.nulli0n.vbot.backend.BackendControlService;
import dev.nulli0n.vbot.bot.BotSnapshot;
import dev.nulli0n.vbot.message.PluginMessages;
import dev.nulli0n.vbot.transport.BotPosition;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.event.ClickEvent;
import net.kyori.adventure.text.event.HoverEvent;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;

import java.time.Duration;
import java.time.Instant;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.function.Function;

public final class VBotCommand implements SimpleCommand {
    private static final String ADMIN_PERMISSION = "bots4velo.admin";
    private static final String VIEW_PERMISSION = "bots4velo.view";
    private static final String CONTROL_PERMISSION = "bots4velo.control";
    private static final String CREATE_PERMISSION = "bots4velo.create";
    private static final String RELOAD_PERMISSION = "bots4velo.reload";
    private static final Gson GSON = new GsonBuilder().disableHtmlEscaping().create();
    private static final List<String> ACTIONS = List.of(
        "help", "list", "status", "monitor", "history", "doctor", "servers", "server", "movehere",
        "position", "move", "look", "behavior", "afk", "recover", "invulnerable", "gamemode", "spawnpoint", "respawn",
        "create", "remove", "start", "stop", "reconnect", "command", "language", "lang", "reload");
    private static final List<String> BOT_ID_ACTIONS = List.of(
        "status", "monitor", "history", "server", "movehere", "position", "move", "look", "remove",
        "start", "stop", "reconnect", "command", "behavior", "invulnerable", "gamemode", "spawnpoint",
        "respawn", "afk", "recover");

    private final Bots4VeloPlugin plugin;
    private final PlayerStateCommandHandler playerStateCommands;

    public VBotCommand(Bots4VeloPlugin plugin) {
        this(plugin, BackendControlService.unavailable());
    }

    public VBotCommand(Bots4VeloPlugin plugin, BackendControlService backendControlService) {
        this.plugin = plugin;
        this.playerStateCommands = new PlayerStateCommandHandler(backendControlService, selector ->
            plugin.selectBots(selector).stream().map(session -> session.definition().id()).toList(), plugin::messages);
    }

    @Override
    public void execute(Invocation invocation) {
        CommandSource source = invocation.source();
        String[] args = invocation.arguments();
        if (args.length == 0) {
            if (!canUse(source, VIEW_PERMISSION)) {
                source.sendMessage(CommandUi.feedback(plugin.messages(), NamedTextColor.RED, "no-permission",
                    "You do not have permission for /vbot %s.", "help"));
                return;
            }
            help(source, args);
            return;
        }
        String action = args[0].toLowerCase(Locale.ROOT);
        if (!hasPermission(source, action, args)) {
            source.sendMessage(CommandUi.feedback(plugin.messages(), NamedTextColor.RED, "no-permission",
                "You do not have permission for /vbot %s.", action));
            return;
        }
        try {
            switch (action) {
                case "help" -> help(source, args);
                case "list" -> list(source);
                case "status" -> status(source, args);
                case "monitor" -> monitor(source, args);
                case "history" -> history(source, args);
                case "doctor" -> doctor(source, args);
                case "servers" -> servers(source, args);
                case "server" -> server(source, args);
                case "movehere" -> moveHere(source, args);
                case "position" -> position(source, args);
                case "move" -> move(source, args);
                case "look" -> look(source, args);
                case "behavior" -> behavior(source, args);
                case "afk", "recover", "invulnerable", "gamemode", "spawnpoint", "respawn" ->
                    playerState(source, action, args);
                case "create" -> create(source, args);
                case "remove" -> remove(source, args);
                case "start" -> change(source, args, "start", plugin.manager()::start);
                case "stop" -> change(source, args, "stop", plugin.manager()::stop);
                case "reconnect" -> change(source, args, "reconnect", plugin.manager()::reconnect);
                case "command" -> command(source, args);
                case "language", "lang" -> language(source, args);
                case "reload" -> {
                    Bots4VeloPlugin.ReloadResult result = plugin.reload();
                    source.sendMessage(CommandUi.feedback(plugin.messages(), NamedTextColor.GREEN,
                        "reload-success",
                        "Configuration validated and reloaded (%s bots, %s managed); enabled bots were queued.",
                        result.configuredBots(), result.managedBots()));
                }
                default -> help(source, new String[]{"help"});
            }
        }
        catch (Exception exception) {
            plugin.logger().error("/vbot command failed", exception);
            source.sendMessage(CommandUi.feedback(plugin.messages(), NamedTextColor.RED,
                "operation-failed", "Operation failed: %s", safeDetail(exception)));
        }
    }

    private void list(CommandSource source) {
        List<BotSnapshot> snapshots = plugin.manager().snapshots();
        source.sendMessage(CommandUi.feedback(plugin.messages(), NamedTextColor.GOLD,
            "list-title", "Bots (%s):", snapshots.size()));
        snapshots.forEach(snapshot -> {
            Component identity = Component.text(snapshot.id() + " / " + snapshot.username(), NamedTextColor.AQUA)
                .clickEvent(ClickEvent.runCommand("/vbot status " + snapshot.id()))
                .hoverEvent(HoverEvent.showText(Component.text(plugin.messages().text("list-click-status",
                    "Open status for %s", snapshot.id()), NamedTextColor.YELLOW)));
            source.sendMessage(Component.text(" > ", NamedTextColor.DARK_GRAY)
                .append(identity)
                .append(Component.text(" [" + snapshot.state() + "]", stateColor(snapshot)))
                .append(Component.text(" @ " + plugin.currentServer(snapshot.id()).orElse("-")
                    + " [" + snapshot.protocolVersion() + "]" + labels(snapshot.id()), NamedTextColor.GRAY)));
        });
        if (!plugin.manager().groups().isEmpty() || !plugin.manager().tags().isEmpty()) {
            source.sendMessage(Component.text(plugin.messages().text("list-groups",
                "Groups: %s | Tags: %s", String.join(", ", plugin.manager().groups()),
                String.join(", ", plugin.manager().tags())), NamedTextColor.DARK_GRAY));
        }
    }

    private void status(CommandSource source, String[] args) {
        if (args.length != 2) {
            feedback(source, NamedTextColor.YELLOW, "usage-status", "Usage: /vbot status <id>");
            return;
        }
        plugin.manager().find(args[1]).ifPresentOrElse(session -> {
            BotSnapshot snapshot = session.snapshot();
            source.sendMessage(CommandUi.feedback(NamedTextColor.GOLD,
                snapshot.id() + " / " + snapshot.username()));
            source.sendMessage(Component.text(plugin.messages().text("status-state",
                "State: %s | Server: %s | Reconnect attempts: %s", snapshot.state(),
                plugin.currentServer(snapshot.id()).orElse("-"), snapshot.reconnectAttempts()), stateColor(snapshot)));
            source.sendMessage(Component.text(plugin.messages().text("status-protocol",
                "Protocol: %s | Source: %s", snapshot.protocolVersion(), snapshot.protocolSource()),
                NamedTextColor.GRAY));
            source.sendMessage(Component.text(plugin.messages().text("status-last-disconnect",
                "Last disconnect: %s", snapshot.lastDisconnectReason()), NamedTextColor.GRAY));
            source.sendMessage(Component.text(plugin.messages().text("status-counters",
                "PLAY entries: %s | Disconnects: %s | Resource packs: %s", snapshot.playEntries(),
                snapshot.disconnects(), snapshot.resourcePacksLoaded()), NamedTextColor.GRAY));
            source.sendMessage(Component.text(plugin.messages().text("status-times",
                "Connected: %s | Last PLAY: %s | Last disconnect: %s", time(snapshot.connectedAt()),
                time(snapshot.lastPlayAt()), time(snapshot.lastDisconnectAt())), NamedTextColor.GRAY));
            source.sendMessage(Component.text(plugin.messages().text("status-position",
                "Position: %s", formatPosition(snapshot.position())), NamedTextColor.GRAY));
            source.sendMessage(Component.text(plugin.messages().text("status-auth-ui",
                "Auth UI: %s | Presented: %s | Submitted: %s", snapshot.authenticationUi(),
                snapshot.authenticationUiPresentations(), snapshot.authenticationUiSubmissions()), NamedTextColor.GRAY));
            var desired = session.definition().playerState();
            source.sendMessage(Component.text(plugin.messages().text("status-paper-configured",
                "Configured Paper: invulnerable=%s | gamemode=%s | spawnpoint=%s | afk=%s | control=%s",
                desired.invulnerability(), desired.gameMode(), desired.respawnPoint().mode(), desired.afkPreset(),
                plugin.backendControlEnabled()
                    ? plugin.messages().text("value-enabled", "enabled")
                    : plugin.messages().text("value-disabled", "disabled")), NamedTextColor.GRAY));
            source.sendMessage(statusActions(source, snapshot.id()));
            if (plugin.backendControlEnabled()) {
                plugin.backendControl().probe(snapshot.id()).whenComplete((result, failure) -> {
                    if (failure != null || result == null || !result.successful()
                        || !result.actualState().present()) {
                        String detail = failure != null ? safeDetail(failure)
                            : result == null ? "empty acknowledgement"
                            : result.status() + (result.detail().isBlank() ? "" : " - " + result.detail());
                        feedback(source, NamedTextColor.RED, "status-paper-unavailable",
                            "Actual Paper state unavailable: %s", detail);
                        return;
                    }
                    var actual = result.actualState();
                    source.sendMessage(Component.text(plugin.messages().text("status-paper-actual",
                        "Actual Paper: invulnerable=%s | gamemode=%s | spawnpoint=%s%s%s",
                        actual.invulnerable(), actual.gameMode(), actual.respawnPoint().mode(),
                        actual.respawnPoint().world().isBlank() ? "" : "@" + actual.respawnPoint().world(),
                        actual.extendedPresent()
                            ? plugin.messages().text("status-paper-extended",
                                " | sleepIgnored=%s | affectsSpawning=%s | pickup=%s | collidable=%s",
                                actual.sleepingIgnored(), actual.affectsSpawning(), actual.pickupItems(),
                                actual.collidable())
                            : plugin.messages().text("status-paper-extended-unavailable",
                                " | extendedAfk=unavailable")), NamedTextColor.GREEN));
                });
            }
        }, () -> unknown(source, args[1]));
    }

    private void monitor(CommandSource source, String[] args) {
        if (args.length > 2) {
            feedback(source, NamedTextColor.YELLOW, "usage-monitor", "Usage: /vbot monitor [id]");
            return;
        }
        List<BotSnapshot> snapshots;
        if (args.length == 2) {
            snapshots = plugin.manager().find(args[1]).map(session -> List.of(session.snapshot())).orElse(List.of());
            if (snapshots.isEmpty()) {
                unknown(source, args[1]);
                return;
            }
        }
        else {
            snapshots = plugin.manager().snapshots();
        }
        source.sendMessage(Component.text(GSON.toJson(snapshots.stream().map(this::monitorMap).toList())));
    }

    private void doctor(CommandSource source, String[] args) {
        if (args.length > 2) {
            feedback(source, NamedTextColor.YELLOW, "usage-doctor", "Usage: /vbot doctor [id|selector]");
            return;
        }
        try {
            plugin.validateConfiguration();
            feedback(source, NamedTextColor.GREEN, "config-ok",
                "Config: OK (validation passed without replacing live bots).");
        }
        catch (Exception exception) {
            feedback(source, NamedTextColor.RED, "config-failed", "Config: FAILED - %s", safeDetail(exception));
            return;
        }
        boolean tab = plugin.proxy().getPluginManager().getPlugin("tab").isPresent();
        boolean scoreboard = plugin.proxy().getPluginManager().getPlugin("velocity-scoreboard-api").isPresent();
        source.sendMessage(Component.text(plugin.messages().text("doctor-environment",
            "Velocity backends: %s | TAB: %s | Scoreboard API: %s", plugin.serverNames().size(),
            localizedPresence(tab), localizedPresence(scoreboard)), NamedTextColor.GRAY));
        source.sendMessage(Component.text(plugin.messages().text("doctor-paper-control",
            "Paper backend control: %s", plugin.backendControlEnabled()
                ? plugin.messages().text("doctor-paper-probing", "enabled (probing selected bots)")
                : plugin.messages().text("value-disabled", "disabled")),
            plugin.backendControlEnabled() ? NamedTextColor.GREEN : NamedTextColor.YELLOW));
        List<dev.nulli0n.vbot.bot.BotSession> sessions = args.length == 2
            ? plugin.selectBots(args[1]) : plugin.selectBots("all");
        if (sessions.isEmpty()) {
            feedback(source, NamedTextColor.YELLOW, "doctor-no-matches",
                "No bots matched the requested selector.");
            return;
        }
        for (dev.nulli0n.vbot.bot.BotSession session : sessions) {
            BotSnapshot snapshot = session.snapshot();
            var definition = session.definition();
            boolean targetKnown = definition.targetServer().isBlank() || plugin.serverNames().stream()
                .anyMatch(server -> server.equalsIgnoreCase(definition.targetServer()));
            String auth = definition.auth().mode() == dev.nulli0n.vbot.config.BotPluginConfig.AuthMode.NONE
                ? plugin.messages().text("doctor-auth-disabled", "disabled")
                : (definition.password().isBlank()
                    ? plugin.messages().text("doctor-auth-missing-password", "MISSING PASSWORD")
                    : plugin.messages().text("doctor-auth-configured", "configured"));
            String protocol = snapshot.protocolVersion().equals("unresolved")
                ? (definition.protocolOverride() == null
                    ? plugin.messages().text("doctor-protocol-auto-pending", "auto/pending")
                    : plugin.messages().text("doctor-protocol-manual-pending", "manual/pending"))
                : snapshot.protocolVersion() + " via " + snapshot.protocolSource();
            boolean authReady = definition.auth().mode() == dev.nulli0n.vbot.config.BotPluginConfig.AuthMode.NONE
                || !definition.password().isBlank();
            NamedTextColor color = targetKnown && authReady ? NamedTextColor.GREEN : NamedTextColor.RED;
            source.sendMessage(Component.text(plugin.messages().text("doctor-bot",
                "%s: state=%s, protocol=%s, auth=%s, target=%s, packs=%s, auth-ui=%s/%s (last=%s)",
                definition.id(), snapshot.state(), protocol, auth,
                definition.targetServer().isBlank()
                    ? plugin.messages().text("doctor-target-none", "none")
                    : plugin.messages().text(targetKnown ? "doctor-target-ok" : "doctor-target-missing",
                        targetKnown ? "%s OK" : "%s MISSING", definition.targetServer()),
                snapshot.resourcePacksLoaded(),
                snapshot.authenticationUiPresentations(), snapshot.authenticationUiSubmissions(),
                snapshot.authenticationUi()), color));
            if (plugin.backendControlEnabled()) {
                plugin.backendControl().probe(definition.id()).whenComplete((result, failure) -> {
                    if (failure != null || result == null) {
                        feedback(source, NamedTextColor.RED, "doctor-paper-failed",
                            "%s: Paper control probe failed - %s", definition.id(),
                            failure == null
                                ? plugin.messages().text("backend-empty-acknowledgement", "empty acknowledgement")
                                : safeDetail(failure));
                        return;
                    }
                    NamedTextColor probeColor = result.successful() ? NamedTextColor.GREEN : NamedTextColor.RED;
                    source.sendMessage(Component.text(plugin.messages().text("doctor-paper-result",
                        "%s: Paper control=%s%s", definition.id(), result.status(),
                        result.detail().isBlank() ? "" : " - " + result.detail()), probeColor));
                });
            }
        }
    }

    private void history(CommandSource source, String[] args) {
        if (args.length != 2) {
            feedback(source, NamedTextColor.YELLOW, "usage-history", "Usage: /vbot history <id>");
            return;
        }
        plugin.manager().find(args[1]).ifPresentOrElse(session -> {
            BotSnapshot snapshot = session.snapshot();
            feedback(source, NamedTextColor.GOLD, "history-title", "Recent events for %s (%s/50):",
                snapshot.id(), snapshot.recentEvents().size());
            snapshot.recentEvents().forEach(event -> source.sendMessage(Component.text(
                event.at() + " " + event.type() + " - " + event.detail(), NamedTextColor.GRAY)));
        }, () -> unknown(source, args[1]));
    }

    private void position(CommandSource source, String[] args) {
        if (args.length != 2) {
            feedback(source, NamedTextColor.YELLOW, "usage-position", "Usage: /vbot position <id>");
            return;
        }
        plugin.manager().find(args[1]).ifPresentOrElse(session -> source.sendMessage(Component.text(
            formatPosition(session.position()), NamedTextColor.GRAY)), () -> unknown(source, args[1]));
    }

    private void move(CommandSource source, String[] args) {
        if (args.length != 5) {
            feedback(source, NamedTextColor.YELLOW, "usage-move", "Usage: /vbot move <id> <x> <y> <z>");
            return;
        }
        if (plugin.manager().find(args[1]).isEmpty()) {
            unknown(source, args[1]);
            return;
        }
        double x = finiteDouble(args[2], "x");
        double y = finiteDouble(args[3], "y");
        double z = finiteDouble(args[4], "z");
        if (plugin.manager().moveTo(args[1], x, y, z)) {
            feedback(source, NamedTextColor.GREEN, "move-sent",
                "Movement packet sent; the final position depends on the server response.");
        }
        else {
            feedback(source, NamedTextColor.RED, "position-not-ready",
                "The bot is not in PLAY or has not received its initial server position.");
        }
    }

    private void look(CommandSource source, String[] args) {
        if (args.length != 4) {
            feedback(source, NamedTextColor.YELLOW, "usage-look", "Usage: /vbot look <id> <yaw> <pitch>");
            return;
        }
        if (plugin.manager().find(args[1]).isEmpty()) {
            unknown(source, args[1]);
            return;
        }
        float yaw = finiteFloat(args[2], "yaw");
        float pitch = finiteFloat(args[3], "pitch");
        if (pitch < -90.0F || pitch > 90.0F) {
            throw new IllegalArgumentException(plugin.messages().text("input-pitch",
                "pitch must be between -90 and 90"));
        }
        if (plugin.manager().look(args[1], yaw, pitch)) {
            feedback(source, NamedTextColor.GREEN, "look-sent", "Look packet sent.");
        }
        else {
            feedback(source, NamedTextColor.RED, "position-not-ready",
                "The bot is not in PLAY or has not received its initial server position.");
        }
    }

    private void command(CommandSource source, String[] args) {
        if (args.length < 3) {
            feedback(source, NamedTextColor.YELLOW, "usage-command",
                "Usage: /vbot command <id|selector> <command...>");
            return;
        }
        String command = String.join(" ", Arrays.copyOfRange(args, 2, args.length));
        applySelection(source, args[1], "command", id -> plugin.manager().command(id, command));
    }

    private void behavior(CommandSource source, String[] args) {
        if (args.length < 3 || args.length > 4) {
            feedback(source, NamedTextColor.YELLOW, "usage-behavior",
                "Usage: /vbot behavior <id|selector> <start|pause|status|follow|unfollow> [player]");
            return;
        }
        List<dev.nulli0n.vbot.bot.BotSession> targets = plugin.selectBots(args[1]);
        if (targets.isEmpty()) {
            noMatches(source, args[1]);
            return;
        }
        switch (args[2].toLowerCase(Locale.ROOT)) {
            case "start", "resume" -> {
                targets.forEach(dev.nulli0n.vbot.bot.BotSession::startBehavior);
                feedback(source, NamedTextColor.GREEN, "behavior-started",
                    "Behavior started for %s bot(s).", targets.size());
            }
            case "pause", "stop" -> {
                targets.forEach(dev.nulli0n.vbot.bot.BotSession::pauseBehavior);
                feedback(source, NamedTextColor.YELLOW, "behavior-paused",
                    "Behavior paused for %s bot(s).", targets.size());
            }
            case "status" -> targets.forEach(session -> {
                var status = session.behaviorSnapshot();
                source.sendMessage(Component.text(plugin.messages().text("behavior-status",
                    "%s: %s requested=%s running=%s paused=%s cycles=%s last=%s follow=%s",
                    session.definition().id(), status.mode(), status.requested(), status.running(), status.paused(),
                    status.cycles(), status.lastAction(),
                    status.followTarget().isBlank() ? "-" : status.followTarget()), NamedTextColor.GRAY));
            });
            case "follow" -> {
                if (args.length != 4) {
                    feedback(source, NamedTextColor.YELLOW, "usage-behavior-follow",
                        "Usage: /vbot behavior <id|selector> follow <player>");
                    return;
                }
                int started = 0;
                for (dev.nulli0n.vbot.bot.BotSession target : targets) {
                    if (plugin.startFollowing(target.definition().id(), args[3]).successful()) {
                        started++;
                    }
                }
                feedback(source, started == targets.size() ? NamedTextColor.GREEN : NamedTextColor.YELLOW,
                    "behavior-following", "Following requested for %s/%s bot(s).", started, targets.size());
            }
            case "unfollow" -> {
                targets.forEach(target -> plugin.stopFollowing(target.definition().id()));
                feedback(source, NamedTextColor.YELLOW, "behavior-unfollowed",
                    "Follow stopped for %s bot(s).", targets.size());
            }
            default -> feedback(source, NamedTextColor.RED, "behavior-invalid-action",
                "Behavior action must be start, pause, status, follow or unfollow.");
        }
    }

    private void servers(CommandSource source, String[] args) {
        if (args.length != 1) {
            feedback(source, NamedTextColor.YELLOW, "usage-servers", "Usage: /vbot servers");
            return;
        }
        List<String> names = plugin.serverNames();
        feedback(source, NamedTextColor.GOLD, "servers-title", "Velocity servers (%s):", names.size());
        for (String name : names) {
            long bots = plugin.manager().snapshots().stream()
                .filter(snapshot -> plugin.currentServer(snapshot.id())
                    .map(current -> current.equalsIgnoreCase(name)).orElse(false))
                .count();
            source.sendMessage(Component.text(plugin.messages().text("servers-entry", "- %s (bots: %s)", name, bots),
                NamedTextColor.GRAY));
        }
    }

    private void server(CommandSource source, String[] args) {
        if (args.length != 3) {
            feedback(source, NamedTextColor.YELLOW, "usage-server",
                "Usage: /vbot server <id|selector> <server>");
            return;
        }
        List<dev.nulli0n.vbot.bot.BotSession> sessions = plugin.selectBots(args[1]);
        if (sessions.isEmpty()) {
            noMatches(source, args[1]);
            return;
        }
        List<CompletableFuture<BotServerSwitchResult>> switches = sessions.stream()
            .map(session -> plugin.switchBotServer(session.definition().id(), args[2])).toList();
        CompletableFuture.allOf(switches.toArray(CompletableFuture[]::new)).thenRun(() -> {
            int succeeded = 0;
            for (CompletableFuture<BotServerSwitchResult> future : switches) {
                BotServerSwitchResult result = future.join();
                if (result.successful()) {
                    succeeded++;
                }
                else {
                    reportSwitch(source, result);
                }
            }
            feedback(source, succeeded == switches.size() ? NamedTextColor.GREEN : NamedTextColor.YELLOW,
                "server-switch-summary", "Server switch requested for %s/%s bot(s).", succeeded, switches.size());
        });
    }

    private void moveHere(CommandSource source, String[] args) {
        if (args.length != 2) {
            feedback(source, NamedTextColor.YELLOW, "usage-movehere",
                "Usage: /vbot movehere <id|selector>");
            return;
        }
        if (!(source instanceof Player player)) {
            feedback(source, NamedTextColor.RED, "movehere-player-only",
                "movehere must be run by an in-game player.");
            return;
        }
        String targetServer = player.getCurrentServer()
            .map(connection -> connection.getServerInfo().getName())
            .orElse("");
        if (targetServer.isEmpty()) {
            feedback(source, NamedTextColor.RED, "movehere-no-backend",
                "You are not connected to a backend server.");
            return;
        }

        List<dev.nulli0n.vbot.bot.BotSession> targets = plugin.selectBots(args[1]);
        if (targets.isEmpty()) {
            noMatches(source, args[1]);
            return;
        }
        feedback(source, NamedTextColor.GREEN, "movehere-summary",
            "movehere requested for %s bot(s).", targets.size());
        for (dev.nulli0n.vbot.bot.BotSession target : targets) {
            plugin.switchBotServer(target.definition().id(), targetServer).thenAccept(result -> {
                if (!result.successful()) {
                    reportSwitch(source, result);
                    return;
                }
                long delay = result.status() == Bots4VeloPlugin.BotServerSwitchStatus.SWITCHED ? 750L : 0L;
                plugin.proxy().getScheduler().buildTask(plugin, () -> finishMoveHere(player, result))
                    .delay(Duration.ofMillis(delay)).schedule();
            });
        }
    }

    private void finishMoveHere(Player player, BotServerSwitchResult result) {
        boolean playerStillThere = player.getCurrentServer()
            .map(connection -> connection.getServerInfo().getName().equalsIgnoreCase(result.server()))
            .orElse(false);
        boolean botArrived = plugin.currentServer(result.botId())
            .map(server -> server.equalsIgnoreCase(result.server()))
            .orElse(false);
        if (!playerStillThere || !botArrived) {
            feedback(player, NamedTextColor.RED, "movehere-cancelled",
                "movehere cancelled: you or the bot left %s during the server switch.", result.server());
            return;
        }
        player.spoofChatInput("/minecraft:tp " + result.username() + " " + player.getUsername());
        feedback(player, NamedTextColor.GREEN, "movehere-arrived",
            "Bot %s reached %s and is being teleported to you.", result.botId(), result.server());
    }

    private void reportSwitch(CommandSource source, BotServerSwitchResult result) {
        switch (result.status()) {
            case SWITCHED -> feedback(source, NamedTextColor.GREEN, "switch-success",
                "Bot %s switched to %s.", result.botId(), result.server());
            case ALREADY_CONNECTED -> feedback(source, NamedTextColor.YELLOW, "switch-already",
                "Bot %s is already on %s.", result.botId(), result.server());
            case BOT_NOT_FOUND -> unknown(source, result.botId());
            case BOT_NOT_READY -> feedback(source, NamedTextColor.RED, "switch-not-ready",
                "The bot is not ready: %s", result.detail());
            case AUTHENTICATION_PENDING -> feedback(source, NamedTextColor.RED, "switch-auth-pending",
                "The bot is still authenticating; try again after login succeeds.");
            case SERVER_NOT_FOUND -> feedback(source, NamedTextColor.RED, "switch-server-missing",
                "Unknown Velocity server: %s", result.server());
            case FAILED -> feedback(source, NamedTextColor.RED, "switch-failed",
                "Server switch failed: %s", result.detail());
        }
    }

    private void create(CommandSource source, String[] args) throws Exception {
        if (args.length < 4 || args.length > 5) {
            feedback(source, NamedTextColor.YELLOW, "usage-create",
                "Usage: /vbot create <id> <username> <password|-> [target-server|-]");
            return;
        }
        String password = args[3].equals("-") ? "" : args[3];
        String targetServer = args.length == 5 && !args[4].equals("-") ? args[4] : "";
        switch (plugin.createManagedBot(args[1], args[2], password, targetServer)) {
            case CREATED -> feedback(source, NamedTextColor.GREEN, "create-success",
                "Bot saved and queued under the startup rate limit: %s", args[1]);
            case ALREADY_EXISTS -> feedback(source, NamedTextColor.RED, "create-exists",
                "Bot ID already exists: %s", args[1]);
            case LIMIT_REACHED -> feedback(source, NamedTextColor.RED, "create-limit",
                "The runtime.maximum-bots limit has been reached.");
        }
    }

    private void remove(CommandSource source, String[] args) throws Exception {
        if (args.length != 2) {
            feedback(source, NamedTextColor.YELLOW, "usage-remove", "Usage: /vbot remove <id>");
            return;
        }
        switch (plugin.removeManagedBot(args[1])) {
            case REMOVED -> feedback(source, NamedTextColor.GREEN, "remove-success",
                "Bot stopped and removed from managed-bots.yml: %s", args[1]);
            case STATIC_BOT -> feedback(source, NamedTextColor.RED, "remove-static",
                "This bot is defined in config.yml; edit it and run /vbot reload.");
            case NOT_FOUND -> unknown(source, args[1]);
        }
    }

    private void change(CommandSource source, String[] args, String verb, BotAction action) {
        if (args.length != 2) {
            feedback(source, NamedTextColor.YELLOW, "usage-selection",
                "Usage: /vbot %s <id|selector>", args[0]);
            return;
        }
        applySelection(source, args[1], verb, action::apply);
    }

    private void playerState(CommandSource source, String action, String[] args) {
        playerStateCommands.execute(action, args).thenAccept(reply -> {
            NamedTextColor color = switch (reply.severity()) {
                case SUCCESS -> NamedTextColor.GREEN;
                case WARNING -> NamedTextColor.YELLOW;
                case ERROR -> NamedTextColor.RED;
                case USAGE -> NamedTextColor.YELLOW;
            };
            for (int index = 0; index < reply.lines().size(); index++) {
                String line = reply.lines().get(index);
                source.sendMessage(index == 0
                    ? CommandUi.feedback(color, line)
                    : Component.text("  " + line, color));
            }
        }).exceptionally(failure -> {
            plugin.logger().error("/vbot {} failed while collecting backend acknowledgements", action, failure);
            source.sendMessage(CommandUi.feedback(plugin.messages(), NamedTextColor.RED,
                "backend-control-failed", "Backend control failed while collecting acknowledgements."));
            return null;
        });
    }

    private void language(CommandSource source, String[] args) throws java.io.IOException {
        if (args.length > 2) {
            source.sendMessage(CommandUi.feedback(plugin.messages(), NamedTextColor.YELLOW,
                "language-usage", "Usage: /vbot language [locale]"));
            return;
        }
        if (args.length == 2) {
            try {
                PluginMessages selected = plugin.changeLanguage(args[1]);
                source.sendMessage(CommandUi.feedback(selected, NamedTextColor.GREEN,
                    "language-changed", "Language changed to %s.", selected.language()));
            }
            catch (PluginMessages.UnknownLanguageException exception) {
                PluginMessages active = plugin.messages();
                source.sendMessage(CommandUi.feedback(active, NamedTextColor.RED,
                    "language-invalid", "Unknown language '%s'. Available: %s",
                    args[1], String.join(", ", active.availableLanguages())));
            }
            return;
        }

        PluginMessages active = plugin.messages();
        source.sendMessage(CommandUi.feedback(active, NamedTextColor.AQUA,
            "language-current", "Current language: %s", active.language()));
        Component choices = Component.text(active.text("language-available", "Available languages: "),
            NamedTextColor.GRAY);
        boolean first = true;
        for (String language : active.availableLanguages()) {
            if (!first) {
                choices = choices.append(Component.text("  ", NamedTextColor.DARK_GRAY));
            }
            first = false;
            if (language.equals(active.language())) {
                choices = choices.append(Component.text("[" + language + "]", NamedTextColor.GOLD,
                    TextDecoration.BOLD));
            }
            else {
                choices = choices.append(Component.text("[" + language + "]", NamedTextColor.AQUA)
                    .clickEvent(ClickEvent.suggestCommand("/vbot language " + language))
                    .hoverEvent(HoverEvent.showText(Component.text(active.text("language-click",
                        "Click to prepare language switch to %s", language), NamedTextColor.YELLOW))));
            }
        }
        source.sendMessage(choices);
    }

    private void help(CommandSource source, String[] args) {
        int page = 1;
        if (args.length > 2) {
            source.sendMessage(CommandUi.feedback(plugin.messages(), NamedTextColor.YELLOW,
                "help-usage", "Usage: /vbot help [1|2|3|4]"));
            return;
        }
        if (args.length == 2) {
            try {
                page = Integer.parseInt(args[1]);
            }
            catch (NumberFormatException exception) {
                invalidHelpPage(source);
                return;
            }
        }
        List<Component> lines = HelpRenderer.render(plugin.messages(), page,
            permission -> source.hasPermission(ADMIN_PERMISSION) || source.hasPermission(permission));
        if (lines.isEmpty()) {
            invalidHelpPage(source);
            return;
        }
        lines.forEach(source::sendMessage);
    }

    static List<String> helpLines(int page) {
        return HelpRenderer.plainLines(page);
    }

    private void invalidHelpPage(CommandSource source) {
        source.sendMessage(CommandUi.feedback(plugin.messages(), NamedTextColor.RED,
            "help-invalid-page", "The help page must be between 1 and %s.", HelpRenderer.PAGE_COUNT));
    }

    private void unknown(CommandSource source, String id) {
        source.sendMessage(CommandUi.feedback(plugin.messages(), NamedTextColor.RED,
            "unknown-bot", "Unknown bot: %s", id));
    }

    private void noMatches(CommandSource source, String selector) {
        source.sendMessage(CommandUi.feedback(plugin.messages(), NamedTextColor.RED,
            "no-matches", "No bots matched selector: %s", selector));
    }

    private void feedback(CommandSource source, NamedTextColor color, String key,
                          String englishFallback, Object... arguments) {
        source.sendMessage(CommandUi.feedback(plugin.messages(), color, key, englishFallback, arguments));
    }

    private NamedTextColor stateColor(BotSnapshot snapshot) {
        return switch (snapshot.state()) {
            case PLAY -> NamedTextColor.GREEN;
            case FAILED -> NamedTextColor.RED;
            case CONNECTING, LOGIN, CONFIGURATION, RECONNECT_WAIT -> NamedTextColor.YELLOW;
            case STOPPED, STOPPING -> NamedTextColor.GRAY;
        };
    }

    private Component statusActions(CommandSource source, String botId) {
        Component actions = statusButton("status-action-history", "History", "/vbot history " + botId, true)
            .append(Component.space())
            .append(statusButton("status-action-doctor", "Doctor", "/vbot doctor " + botId, true));
        if (canUse(source, CONTROL_PERMISSION)) {
            actions = actions.append(Component.space())
                .append(statusButton("status-action-reconnect", "Reconnect", "/vbot reconnect " + botId, false))
                .append(Component.space())
                .append(statusButton("status-action-movehere", "Move here", "/vbot movehere " + botId, false));
        }
        return actions;
    }

    private Component statusButton(String key, String englishFallback, String command, boolean runImmediately) {
        Component button = Component.text("[" + plugin.messages().text(key, englishFallback) + "]",
            runImmediately ? NamedTextColor.AQUA : NamedTextColor.YELLOW);
        button = runImmediately
            ? button.clickEvent(ClickEvent.runCommand(command))
            : button.clickEvent(ClickEvent.suggestCommand(command));
        String hoverKey = runImmediately ? "status-action-hover-run" : "status-action-hover-prepare";
        String hoverFallback = runImmediately ? "Run %s" : "Insert %s in chat";
        return button.hoverEvent(HoverEvent.showText(Component.text(plugin.messages().text(hoverKey,
            hoverFallback, command), NamedTextColor.GRAY)));
    }

    private void applySelection(CommandSource source, String selector, String verb, Function<String, Boolean> action) {
        List<dev.nulli0n.vbot.bot.BotSession> targets = plugin.selectBots(selector);
        if (targets.isEmpty()) {
            noMatches(source, selector);
            return;
        }
        int succeeded = 0;
        for (dev.nulli0n.vbot.bot.BotSession target : targets) {
            if (action.apply(target.definition().id())) {
                succeeded++;
            }
        }
        source.sendMessage(CommandUi.feedback(plugin.messages(),
            succeeded == targets.size() ? NamedTextColor.GREEN : NamedTextColor.YELLOW,
            "selection-result", "Requested %s for %s/%s bot(s).", localizedAction(verb), succeeded,
            targets.size()));
    }

    private String labels(String id) {
        return plugin.manager().find(id).map(session -> {
            List<String> labels = new java.util.ArrayList<>();
            session.definition().groups().forEach(group -> labels.add("group:" + group));
            session.definition().tags().forEach(tag -> labels.add("tag:" + tag));
            return labels.isEmpty() ? "" : " {" + String.join(",", labels) + "}";
        }).orElse("");
    }

    private boolean hasPermission(CommandSource source, String action, String[] args) {
        if (source.hasPermission(ADMIN_PERMISSION)) {
            return true;
        }
        return source.hasPermission(permissionFor(action, args));
    }

    static String permissionFor(String action, String[] args) {
        if ((action.equals("language") || action.equals("lang")) && args.length <= 1) {
            return VIEW_PERMISSION;
        }
        return action.equals("afk") && args.length >= 3 && args[2].equalsIgnoreCase("status")
            ? VIEW_PERMISSION : permissionFor(action);
    }

    static String permissionFor(String action) {
        return switch (action) {
            case "help", "list", "status", "monitor", "history", "doctor", "servers", "position" -> VIEW_PERMISSION;
            case "create", "remove" -> CREATE_PERMISSION;
            case "reload", "language", "lang" -> RELOAD_PERMISSION;
            default -> CONTROL_PERMISSION;
        };
    }

    private static String safeDetail(Throwable exception) {
        String detail = exception.getMessage();
        return detail == null || detail.isBlank() ? exception.getClass().getSimpleName() : detail;
    }

    private static String time(Instant instant) {
        return instant == null ? "-" : instant.toString();
    }

    private Map<String, Object> monitorMap(BotSnapshot snapshot) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("id", snapshot.id());
        result.put("username", snapshot.username());
        result.put("protocol", snapshot.protocolVersion());
        result.put("protocolSource", snapshot.protocolSource());
        result.put("state", snapshot.state().name());
        result.put("server", plugin.currentServer(snapshot.id()).orElse(null));
        result.put("reconnectAttempts", snapshot.reconnectAttempts());
        result.put("connectedAt", instantOrNull(snapshot.connectedAt()));
        result.put("playEntries", snapshot.playEntries());
        result.put("disconnects", snapshot.disconnects());
        result.put("resourcePacksLoaded", snapshot.resourcePacksLoaded());
        result.put("lastPlayAt", instantOrNull(snapshot.lastPlayAt()));
        result.put("lastDisconnectAt", instantOrNull(snapshot.lastDisconnectAt()));
        result.put("position", positionMap(snapshot.position()));
        result.put("authenticationUi", snapshot.authenticationUi());
        result.put("authenticationUiPresentations", snapshot.authenticationUiPresentations());
        result.put("authenticationUiSubmissions", snapshot.authenticationUiSubmissions());
        result.put("lastDisconnectReason", snapshot.lastDisconnectReason());
        result.put("onlineSeconds", snapshot.onlineSeconds());
        result.put("failureCategory", snapshot.failureCategory().name());
        result.put("recentEvents", snapshot.recentEvents().stream().map(event -> Map.of(
            "at", event.at().toString(), "type", event.type(), "detail", event.detail())).toList());
        result.put("behavior", behaviorMap(snapshot.behavior()));
        plugin.manager().find(snapshot.id()).ifPresent(session -> {
            var state = session.definition().playerState();
            Map<String, Object> configured = new LinkedHashMap<>();
            configured.put("invulnerable", state.invulnerability().name());
            configured.put("gameMode", state.gameMode().name());
            configured.put("afkPreset", state.afkPreset().name());
            configured.put("sleepingIgnored", state.sleepingIgnored().name());
            configured.put("affectsSpawning", state.affectsSpawning().name());
            configured.put("pickupItems", state.pickupItems().name());
            configured.put("collidable", state.collidable().name());
            configured.put("applyDelayMillis", state.applyDelayMillis());
            configured.put("respawnMode", state.respawnPoint().mode().name());
            configured.put("respawnWorld", state.respawnPoint().world());
            result.put("configuredPlayerState", configured);
        });
        result.put("backendControlEnabled", plugin.backendControlEnabled());
        return result;
    }

    private static Map<String, Object> positionMap(BotPosition position) {
        if (!position.known()) {
            return Map.of("known", false);
        }
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("known", true);
        result.put("x", position.x());
        result.put("y", position.y());
        result.put("z", position.z());
        result.put("yaw", position.yaw());
        result.put("pitch", position.pitch());
        return result;
    }

    private static Map<String, Object> behaviorMap(dev.nulli0n.vbot.bot.BehaviorSnapshot behavior) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("mode", behavior.mode().name());
        result.put("requested", behavior.requested());
        result.put("running", behavior.running());
        result.put("paused", behavior.paused());
        result.put("cycles", behavior.cycles());
        result.put("lastAction", behavior.lastAction());
        result.put("lastActionAt", instantOrNull(behavior.lastActionAt()));
        result.put("followTarget", behavior.followTarget().isBlank() ? null : behavior.followTarget());
        return result;
    }

    private String formatPosition(BotPosition position) {
        if (!position.known()) {
            return plugin.messages().text("value-unknown", "unknown");
        }
        return String.format(Locale.ROOT, "x=%.3f y=%.3f z=%.3f yaw=%.2f pitch=%.2f",
            position.x(), position.y(), position.z(), position.yaw(), position.pitch());
    }

    private static String instantOrNull(Instant instant) {
        return instant == null ? null : instant.toString();
    }

    private double finiteDouble(String raw, String name) {
        double value;
        try {
            value = Double.parseDouble(raw);
        }
        catch (NumberFormatException exception) {
            throw new IllegalArgumentException(plugin.messages().text("input-finite-number",
                "%s must be a finite number.", name));
        }
        if (!Double.isFinite(value)) {
            throw new IllegalArgumentException(plugin.messages().text("input-finite-number",
                "%s must be a finite number.", name));
        }
        return value;
    }

    private float finiteFloat(String raw, String name) {
        float value;
        try {
            value = Float.parseFloat(raw);
        }
        catch (NumberFormatException exception) {
            throw new IllegalArgumentException(plugin.messages().text("input-finite-number",
                "%s must be a finite number.", name));
        }
        if (!Float.isFinite(value)) {
            throw new IllegalArgumentException(plugin.messages().text("input-finite-number",
                "%s must be a finite number.", name));
        }
        return value;
    }

    private String localizedPresence(boolean present) {
        return present
            ? plugin.messages().text("value-detected", "detected")
            : plugin.messages().text("value-not-detected", "not detected");
    }

    private String localizedAction(String action) {
        return switch (action) {
            case "start" -> plugin.messages().text("action-start", "start");
            case "stop" -> plugin.messages().text("action-stop", "stop");
            case "reconnect" -> plugin.messages().text("action-reconnect", "reconnect");
            case "command" -> plugin.messages().text("action-command", "command");
            default -> action;
        };
    }

    @Override
    public List<String> suggest(Invocation invocation) {
        String[] args = invocation.arguments();
        if (args.length <= 1) {
            String prefix = args.length == 0 ? "" : args[0].toLowerCase(Locale.ROOT);
            return ACTIONS.stream()
                .filter(action -> action.startsWith(prefix))
                .filter(action -> canSuggestAction(invocation.source(), action))
                .toList();
        }
        if (!canSuggestArguments(invocation.source(), args)) {
            return List.of();
        }
        if (args.length == 2 && BOT_ID_ACTIONS.contains(args[0].toLowerCase(Locale.ROOT))) {
            String prefix = args[1].toLowerCase(Locale.ROOT);
            return selectorSuggestions().stream()
                .filter(id -> id.toLowerCase(Locale.ROOT).startsWith(prefix)).toList();
        }
        if (args.length == 2 && args[0].equalsIgnoreCase("help")) {
            return List.of("1", "2", "3", "4").stream()
                .filter(page -> page.startsWith(args[1])).toList();
        }
        if (args.length == 2 && (args[0].equalsIgnoreCase("language") || args[0].equalsIgnoreCase("lang"))) {
            if (!canUse(invocation.source(), RELOAD_PERMISSION)) {
                return List.of();
            }
            String prefix = args[1].toLowerCase(Locale.ROOT);
            return plugin.messages().availableLanguages().stream()
                .filter(language -> language.toLowerCase(Locale.ROOT).startsWith(prefix))
                .toList();
        }
        if (args.length == 3 && args[0].equalsIgnoreCase("server")) {
            String prefix = args[2].toLowerCase(Locale.ROOT);
            return plugin.serverNames().stream()
                .filter(server -> server.toLowerCase(Locale.ROOT).startsWith(prefix)).toList();
        }
        if (args.length == 5 && args[0].equalsIgnoreCase("create")) {
            String prefix = args[4].toLowerCase(Locale.ROOT);
            return plugin.serverNames().stream()
                .filter(server -> server.toLowerCase(Locale.ROOT).startsWith(prefix)).toList();
        }
        if (args.length == 3 && args[0].equalsIgnoreCase("behavior")) {
            return List.of("start", "pause", "status", "follow", "unfollow").stream()
                .filter(value -> value.startsWith(args[2].toLowerCase(Locale.ROOT))).toList();
        }
        if (args.length == 3 && args[0].equalsIgnoreCase("invulnerable")) {
            return matching(args[2], "on", "off", "keep");
        }
        if (args.length == 3 && args[0].equalsIgnoreCase("gamemode")) {
            return matching(args[2], "survival", "creative", "adventure", "spectator", "unchanged");
        }
        if (args.length == 3 && args[0].equalsIgnoreCase("spawnpoint")) {
            return matching(args[2], "current", "worldspawn", "clear", "set");
        }
        if (args.length == 3 && args[0].equalsIgnoreCase("afk")) {
            return afkActionSuggestions(args[2],
                canUse(invocation.source(), VIEW_PERMISSION),
                canUse(invocation.source(), CONTROL_PERMISSION));
        }
        if (args.length == 4 && args[0].equalsIgnoreCase("afk")
            && args[2].equalsIgnoreCase("preset")) {
            if (!canUse(invocation.source(), CONTROL_PERMISSION)) {
                return List.of();
            }
            return matching(args[3], "safe", "farm", "normal");
        }
        if (args.length == 4 && args[0].equalsIgnoreCase("afk")
            && args[2].equalsIgnoreCase("set")) {
            if (!canUse(invocation.source(), CONTROL_PERMISSION)) {
                return List.of();
            }
            return matching(args[3], "sleep-ignored", "affects-spawning", "pickup", "collision");
        }
        if (args.length == 5 && args[0].equalsIgnoreCase("afk")
            && args[2].equalsIgnoreCase("set")) {
            if (!canUse(invocation.source(), CONTROL_PERMISSION)) {
                return List.of();
            }
            return matching(args[4], "on", "off", "keep");
        }
        return List.of();
    }

    static List<String> afkActionSuggestions(String prefix, boolean canView, boolean canControl) {
        List<String> values = new java.util.ArrayList<>();
        if (canView) {
            values.add("status");
        }
        if (canControl) {
            values.addAll(List.of("preset", "set", "unmanage"));
        }
        String normalized = prefix.toLowerCase(Locale.ROOT);
        return values.stream().filter(value -> value.startsWith(normalized)).toList();
    }

    private static boolean canUse(CommandSource source, String permission) {
        return source.hasPermission(ADMIN_PERMISSION) || source.hasPermission(permission);
    }

    private static boolean canSuggestAction(CommandSource source, String action) {
        if (action.equals("afk")) {
            return canUse(source, VIEW_PERMISSION) || canUse(source, CONTROL_PERMISSION);
        }
        if (action.equals("language") || action.equals("lang")) {
            return canUse(source, VIEW_PERMISSION) || canUse(source, RELOAD_PERMISSION);
        }
        return canUse(source, permissionFor(action));
    }

    private static boolean canSuggestArguments(CommandSource source, String[] args) {
        String action = args[0].toLowerCase(Locale.ROOT);
        if (!ACTIONS.contains(action)) {
            return false;
        }
        if (action.equals("afk") && args.length <= 3) {
            return canUse(source, VIEW_PERMISSION) || canUse(source, CONTROL_PERMISSION);
        }
        return canUse(source, permissionFor(action, args));
    }

    private static List<String> matching(String prefix, String... values) {
        String normalized = prefix.toLowerCase(Locale.ROOT);
        return Arrays.stream(values).filter(value -> value.startsWith(normalized)).toList();
    }

    private List<String> selectorSuggestions() {
        List<String> values = new java.util.ArrayList<>();
        values.add("all");
        plugin.manager().snapshots().stream().map(BotSnapshot::id).forEach(values::add);
        plugin.manager().groups().forEach(group -> values.add("@group:" + group));
        plugin.manager().tags().forEach(tag -> values.add("@tag:" + tag));
        plugin.serverNames().forEach(server -> values.add("@server:" + server));
        return values;
    }

    private interface BotAction {
        boolean apply(String id);
    }
}
