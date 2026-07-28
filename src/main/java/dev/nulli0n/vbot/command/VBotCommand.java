package dev.nulli0n.vbot.command;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.velocitypowered.api.command.CommandSource;
import com.velocitypowered.api.command.SimpleCommand;
import com.velocitypowered.api.proxy.Player;
import dev.nulli0n.vbot.Bots4VeloPlugin;
import dev.nulli0n.vbot.Bots4VeloPlugin.BotServerSwitchResult;
import dev.nulli0n.vbot.bot.BotSnapshot;
import dev.nulli0n.vbot.transport.BotPosition;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;

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
        "position", "move", "look", "behavior", "create", "remove", "start", "stop", "reconnect", "command", "reload");
    private static final List<String> BOT_ID_ACTIONS = List.of(
        "status", "monitor", "history", "server", "movehere", "position", "move", "look", "remove",
        "start", "stop", "reconnect", "command", "behavior");

    private final Bots4VeloPlugin plugin;

    public VBotCommand(Bots4VeloPlugin plugin) {
        this.plugin = plugin;
    }

    @Override
    public void execute(Invocation invocation) {
        CommandSource source = invocation.source();
        String[] args = invocation.arguments();
        if (args.length == 0) {
            help(source, args);
            return;
        }
        String action = args[0].toLowerCase(Locale.ROOT);
        if (!hasPermission(source, action)) {
            source.sendMessage(Component.text(plugin.messages().text("no-permission",
                "You do not have permission for /vbot %s.", action),
                NamedTextColor.RED));
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
                case "create" -> create(source, args);
                case "remove" -> remove(source, args);
                case "start" -> change(source, args, "start", plugin.manager()::start);
                case "stop" -> change(source, args, "stop", plugin.manager()::stop);
                case "reconnect" -> change(source, args, "reconnect", plugin.manager()::reconnect);
                case "command" -> command(source, args);
                case "reload" -> {
                    Bots4VeloPlugin.ReloadResult result = plugin.reload();
                    source.sendMessage(Component.text(
                        "Configuration validated and reloaded (" + result.configuredBots() + " bots, "
                            + result.managedBots() + " managed); enabled bots have been queued for startup.",
                        NamedTextColor.GREEN));
                }
                default -> help(source, new String[]{"help"});
            }
        }
        catch (Exception exception) {
            plugin.logger().error("/vbot command failed", exception);
            source.sendMessage(Component.text("Operation failed: " + exception.getMessage(), NamedTextColor.RED));
        }
    }

    private void list(CommandSource source) {
        List<BotSnapshot> snapshots = plugin.manager().snapshots();
        source.sendMessage(Component.text("Bots (" + snapshots.size() + "):", NamedTextColor.GOLD));
        snapshots.forEach(snapshot -> source.sendMessage(Component.text(
            "- " + snapshot.id() + " / " + snapshot.username() + ": " + snapshot.state()
                + " @ " + plugin.currentServer(snapshot.id()).orElse("-")
                + " [" + snapshot.protocolVersion() + "]"
                + labels(snapshot.id()), NamedTextColor.GRAY)));
        if (!plugin.manager().groups().isEmpty() || !plugin.manager().tags().isEmpty()) {
            source.sendMessage(Component.text("Groups: " + String.join(", ", plugin.manager().groups())
                + " | Tags: " + String.join(", ", plugin.manager().tags()), NamedTextColor.DARK_GRAY));
        }
    }

    private void status(CommandSource source, String[] args) {
        if (args.length != 2) {
            source.sendMessage(Component.text("Usage: /vbot status <id>", NamedTextColor.YELLOW));
            return;
        }
        plugin.manager().find(args[1]).ifPresentOrElse(session -> {
            BotSnapshot snapshot = session.snapshot();
            source.sendMessage(Component.text(snapshot.id() + " / " + snapshot.username(), NamedTextColor.GOLD));
            source.sendMessage(Component.text("State: " + snapshot.state()
                + " | Server: " + plugin.currentServer(snapshot.id()).orElse("-")
                + " | Reconnect attempts: " + snapshot.reconnectAttempts(), NamedTextColor.GRAY));
            source.sendMessage(Component.text("Protocol: " + snapshot.protocolVersion()
                + " | Source: " + snapshot.protocolSource(), NamedTextColor.GRAY));
            source.sendMessage(Component.text(
                "Last disconnect: " + snapshot.lastDisconnectReason(), NamedTextColor.GRAY));
            source.sendMessage(Component.text("PLAY entries: " + snapshot.playEntries()
                + " | Disconnects: " + snapshot.disconnects()
                + " | Resource packs loaded: " + snapshot.resourcePacksLoaded(), NamedTextColor.GRAY));
            source.sendMessage(Component.text("Connected at: " + time(snapshot.connectedAt())
                + " | Last PLAY: " + time(snapshot.lastPlayAt())
                + " | Last disconnect at: " + time(snapshot.lastDisconnectAt()), NamedTextColor.GRAY));
            source.sendMessage(Component.text(
                "Position: " + formatPosition(snapshot.position()), NamedTextColor.GRAY));
            source.sendMessage(Component.text("Auth UI: " + snapshot.authenticationUi()
                + " | Presented: " + snapshot.authenticationUiPresentations()
                + " | Submitted: " + snapshot.authenticationUiSubmissions(), NamedTextColor.GRAY));
        }, () -> unknown(source, args[1]));
    }

    private void monitor(CommandSource source, String[] args) {
        if (args.length > 2) {
            source.sendMessage(Component.text("Usage: /vbot monitor [id]", NamedTextColor.YELLOW));
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
            source.sendMessage(Component.text("Usage: /vbot doctor [id|selector]", NamedTextColor.YELLOW));
            return;
        }
        try {
            plugin.validateConfiguration();
            source.sendMessage(Component.text(plugin.messages().text("config-ok",
                "Config: OK (validation passed without replacing live bots)."),
                NamedTextColor.GREEN));
        }
        catch (Exception exception) {
            source.sendMessage(Component.text("Config: FAILED - " + safeDetail(exception), NamedTextColor.RED));
            return;
        }
        boolean tab = plugin.proxy().getPluginManager().getPlugin("tab").isPresent();
        boolean scoreboard = plugin.proxy().getPluginManager().getPlugin("velocity-scoreboard-api").isPresent();
        source.sendMessage(Component.text("Velocity backends: " + plugin.serverNames().size()
            + " | TAB: " + (tab ? "detected" : "not detected")
            + " | Scoreboard API: " + (scoreboard ? "detected" : "not detected"), NamedTextColor.GRAY));
        List<dev.nulli0n.vbot.bot.BotSession> sessions = args.length == 2
            ? plugin.selectBots(args[1]) : plugin.selectBots("all");
        if (sessions.isEmpty()) {
            source.sendMessage(Component.text("No bots matched the requested selector.", NamedTextColor.YELLOW));
            return;
        }
        for (dev.nulli0n.vbot.bot.BotSession session : sessions) {
            BotSnapshot snapshot = session.snapshot();
            var definition = session.definition();
            boolean targetKnown = definition.targetServer().isBlank() || plugin.serverNames().stream()
                .anyMatch(server -> server.equalsIgnoreCase(definition.targetServer()));
            String auth = definition.auth().mode() == dev.nulli0n.vbot.config.BotPluginConfig.AuthMode.NONE
                ? "disabled" : (definition.password().isBlank() ? "MISSING PASSWORD" : "configured");
            String protocol = snapshot.protocolVersion().equals("unresolved")
                ? (definition.protocolOverride() == null ? "auto/pending" : "manual/pending")
                : snapshot.protocolVersion() + " via " + snapshot.protocolSource();
            NamedTextColor color = targetKnown && !auth.startsWith("MISSING") ? NamedTextColor.GREEN : NamedTextColor.RED;
            source.sendMessage(Component.text(definition.id() + ": state=" + snapshot.state()
                + ", protocol=" + protocol + ", auth=" + auth + ", target="
                + (definition.targetServer().isBlank() ? "none" : definition.targetServer()
                    + (targetKnown ? " OK" : " MISSING")) + ", packs=" + snapshot.resourcePacksLoaded()
                + ", auth-ui=" + snapshot.authenticationUiPresentations() + "/"
                + snapshot.authenticationUiSubmissions(), color));
        }
    }

    private void history(CommandSource source, String[] args) {
        if (args.length != 2) {
            source.sendMessage(Component.text("Usage: /vbot history <id>", NamedTextColor.YELLOW));
            return;
        }
        plugin.manager().find(args[1]).ifPresentOrElse(session -> {
            BotSnapshot snapshot = session.snapshot();
            source.sendMessage(Component.text("Recent events for " + snapshot.id() + " ("
                + snapshot.recentEvents().size() + "/50):", NamedTextColor.GOLD));
            snapshot.recentEvents().forEach(event -> source.sendMessage(Component.text(
                event.at() + " " + event.type() + " - " + event.detail(), NamedTextColor.GRAY)));
        }, () -> unknown(source, args[1]));
    }

    private void position(CommandSource source, String[] args) {
        if (args.length != 2) {
            source.sendMessage(Component.text("Usage: /vbot position <id>", NamedTextColor.YELLOW));
            return;
        }
        plugin.manager().find(args[1]).ifPresentOrElse(session -> source.sendMessage(Component.text(
            formatPosition(session.position()), NamedTextColor.GRAY)), () -> unknown(source, args[1]));
    }

    private void move(CommandSource source, String[] args) {
        if (args.length != 5) {
            source.sendMessage(Component.text("Usage: /vbot move <id> <x> <y> <z>", NamedTextColor.YELLOW));
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
            source.sendMessage(Component.text(
                "Movement packet sent; the final position depends on the server response.",
                NamedTextColor.GREEN));
        }
        else {
            source.sendMessage(Component.text(
                "The bot is not in PLAY or has not received its initial server position.",
                NamedTextColor.RED));
        }
    }

    private void look(CommandSource source, String[] args) {
        if (args.length != 4) {
            source.sendMessage(Component.text("Usage: /vbot look <id> <yaw> <pitch>", NamedTextColor.YELLOW));
            return;
        }
        if (plugin.manager().find(args[1]).isEmpty()) {
            unknown(source, args[1]);
            return;
        }
        float yaw = finiteFloat(args[2], "yaw");
        float pitch = finiteFloat(args[3], "pitch");
        if (pitch < -90.0F || pitch > 90.0F) {
            throw new IllegalArgumentException("pitch must be between -90 and 90");
        }
        if (plugin.manager().look(args[1], yaw, pitch)) {
            source.sendMessage(Component.text("Look packet sent.", NamedTextColor.GREEN));
        }
        else {
            source.sendMessage(Component.text(
                "The bot is not in PLAY or has not received its initial server position.",
                NamedTextColor.RED));
        }
    }

    private void command(CommandSource source, String[] args) {
        if (args.length < 3) {
            source.sendMessage(Component.text("Usage: /vbot command <id|selector> <command...>", NamedTextColor.YELLOW));
            return;
        }
        String command = String.join(" ", Arrays.copyOfRange(args, 2, args.length));
        applySelection(source, args[1], "command", id -> plugin.manager().command(id, command));
    }

    private void behavior(CommandSource source, String[] args) {
        if (args.length < 3 || args.length > 4) {
            source.sendMessage(Component.text("Usage: /vbot behavior <id|selector> <start|pause|status|follow|unfollow> [player]",
                NamedTextColor.YELLOW));
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
                source.sendMessage(Component.text("Behavior started for " + targets.size() + " bot(s).",
                    NamedTextColor.GREEN));
            }
            case "pause", "stop" -> {
                targets.forEach(dev.nulli0n.vbot.bot.BotSession::pauseBehavior);
                source.sendMessage(Component.text("Behavior paused for " + targets.size() + " bot(s).",
                    NamedTextColor.YELLOW));
            }
            case "status" -> targets.forEach(session -> {
                var status = session.behaviorSnapshot();
                source.sendMessage(Component.text(session.definition().id() + ": " + status.mode()
                    + " requested=" + status.requested() + " running=" + status.running()
                    + " paused=" + status.paused() + " cycles=" + status.cycles()
                    + " last=" + status.lastAction() + " follow="
                    + (status.followTarget().isBlank() ? "-" : status.followTarget()), NamedTextColor.GRAY));
            });
            case "follow" -> {
                if (args.length != 4) {
                    source.sendMessage(Component.text("Usage: /vbot behavior <id|selector> follow <player>",
                        NamedTextColor.YELLOW));
                    return;
                }
                int started = 0;
                for (dev.nulli0n.vbot.bot.BotSession target : targets) {
                    if (plugin.startFollowing(target.definition().id(), args[3]).successful()) {
                        started++;
                    }
                }
                source.sendMessage(Component.text("Following requested for " + started + "/" + targets.size()
                    + " bot(s).", started == targets.size() ? NamedTextColor.GREEN : NamedTextColor.YELLOW));
            }
            case "unfollow" -> {
                targets.forEach(target -> plugin.stopFollowing(target.definition().id()));
                source.sendMessage(Component.text("Follow stopped for " + targets.size() + " bot(s).",
                    NamedTextColor.YELLOW));
            }
            default -> source.sendMessage(Component.text(
                "Behavior action must be start, pause, status, follow or unfollow.", NamedTextColor.RED));
        }
    }

    private void servers(CommandSource source, String[] args) {
        if (args.length != 1) {
            source.sendMessage(Component.text("Usage: /vbot servers", NamedTextColor.YELLOW));
            return;
        }
        List<String> names = plugin.serverNames();
        source.sendMessage(Component.text("Velocity servers (" + names.size() + "):", NamedTextColor.GOLD));
        for (String name : names) {
            long bots = plugin.manager().snapshots().stream()
                .filter(snapshot -> plugin.currentServer(snapshot.id())
                    .map(current -> current.equalsIgnoreCase(name)).orElse(false))
                .count();
            source.sendMessage(Component.text("- " + name + " (bots: " + bots + ")", NamedTextColor.GRAY));
        }
    }

    private void server(CommandSource source, String[] args) {
        if (args.length != 3) {
            source.sendMessage(Component.text("Usage: /vbot server <id|selector> <server>", NamedTextColor.YELLOW));
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
            source.sendMessage(Component.text("Server switch requested for " + succeeded + "/" + switches.size()
                + " bot(s).", succeeded == switches.size() ? NamedTextColor.GREEN : NamedTextColor.YELLOW));
        });
    }

    private void moveHere(CommandSource source, String[] args) {
        if (args.length != 2) {
            source.sendMessage(Component.text("Usage: /vbot movehere <id|selector>", NamedTextColor.YELLOW));
            return;
        }
        if (!(source instanceof Player player)) {
            source.sendMessage(Component.text(
                "movehere must be run by an in-game player.", NamedTextColor.RED));
            return;
        }
        String targetServer = player.getCurrentServer()
            .map(connection -> connection.getServerInfo().getName())
            .orElse("");
        if (targetServer.isEmpty()) {
            source.sendMessage(Component.text(
                "You are not connected to a backend server.", NamedTextColor.RED));
            return;
        }

        List<dev.nulli0n.vbot.bot.BotSession> targets = plugin.selectBots(args[1]);
        if (targets.isEmpty()) {
            noMatches(source, args[1]);
            return;
        }
        source.sendMessage(Component.text("movehere requested for " + targets.size() + " bot(s).", NamedTextColor.GREEN));
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
            player.sendMessage(Component.text(
                "movehere cancelled: you or the bot left " + result.server()
                    + " during the server switch.", NamedTextColor.RED));
            return;
        }
        player.spoofChatInput("/minecraft:tp " + result.username() + " " + player.getUsername());
        player.sendMessage(Component.text(
            "Bot " + result.botId() + " reached " + result.server()
                + " and is being teleported to you.",
            NamedTextColor.GREEN));
    }

    private void reportSwitch(CommandSource source, BotServerSwitchResult result) {
        switch (result.status()) {
            case SWITCHED -> source.sendMessage(Component.text(
                "Bot " + result.botId() + " switched to " + result.server() + ".", NamedTextColor.GREEN));
            case ALREADY_CONNECTED -> source.sendMessage(Component.text(
                "Bot " + result.botId() + " is already on " + result.server() + ".", NamedTextColor.YELLOW));
            case BOT_NOT_FOUND -> unknown(source, result.botId());
            case BOT_NOT_READY -> source.sendMessage(Component.text(
                "The bot is not ready: " + result.detail(), NamedTextColor.RED));
            case AUTHENTICATION_PENDING -> source.sendMessage(Component.text(
                "The bot is still authenticating; try again after login succeeds.", NamedTextColor.RED));
            case SERVER_NOT_FOUND -> source.sendMessage(Component.text(
                "Unknown Velocity server: " + result.server(), NamedTextColor.RED));
            case FAILED -> source.sendMessage(Component.text(
                "Server switch failed: " + result.detail(), NamedTextColor.RED));
        }
    }

    private void create(CommandSource source, String[] args) throws Exception {
        if (args.length < 4 || args.length > 5) {
            source.sendMessage(Component.text(
                "Usage: /vbot create <id> <username> <password|-> [target-server|-]",
                NamedTextColor.YELLOW));
            return;
        }
        String password = args[3].equals("-") ? "" : args[3];
        String targetServer = args.length == 5 && !args[4].equals("-") ? args[4] : "";
        switch (plugin.createManagedBot(args[1], args[2], password, targetServer)) {
            case CREATED -> source.sendMessage(Component.text(
                "Bot saved and queued under the startup rate limit: " + args[1], NamedTextColor.GREEN));
            case ALREADY_EXISTS -> source.sendMessage(Component.text(
                "Bot ID already exists: " + args[1], NamedTextColor.RED));
            case LIMIT_REACHED -> source.sendMessage(Component.text(
                "The runtime.maximum-bots limit has been reached.", NamedTextColor.RED));
        }
    }

    private void remove(CommandSource source, String[] args) throws Exception {
        if (args.length != 2) {
            source.sendMessage(Component.text("Usage: /vbot remove <id>", NamedTextColor.YELLOW));
            return;
        }
        switch (plugin.removeManagedBot(args[1])) {
            case REMOVED -> source.sendMessage(Component.text(
                "Bot stopped and removed from managed-bots.yml: " + args[1], NamedTextColor.GREEN));
            case STATIC_BOT -> source.sendMessage(Component.text(
                "This bot is defined in config.yml; edit it and run /vbot reload.", NamedTextColor.RED));
            case NOT_FOUND -> unknown(source, args[1]);
        }
    }

    private void change(CommandSource source, String[] args, String verb, BotAction action) {
        if (args.length != 2) {
            source.sendMessage(Component.text("Usage: /vbot " + args[0] + " <id|selector>", NamedTextColor.YELLOW));
            return;
        }
        applySelection(source, args[1], verb, action::apply);
    }

    private void help(CommandSource source, String[] args) {
        int page = 1;
        if (args.length > 2) {
            source.sendMessage(Component.text("Usage: /vbot help [1|2]", NamedTextColor.YELLOW));
            return;
        }
        if (args.length == 2) {
            try {
                page = Integer.parseInt(args[1]);
            }
            catch (NumberFormatException exception) {
                source.sendMessage(Component.text("The help page must be 1 or 2.", NamedTextColor.RED));
                return;
            }
        }
        List<String> lines = helpLines(page);
        if (lines.isEmpty()) {
            source.sendMessage(Component.text("The help page must be 1 or 2.", NamedTextColor.RED));
            return;
        }
        source.sendMessage(Component.text(plugin.messages().text("help-title", "Bots4Velo Help (%s/2)", page),
            NamedTextColor.GOLD));
        for (String line : lines) {
            source.sendMessage(Component.text(line, NamedTextColor.YELLOW));
        }
        source.sendMessage(Component.text(plugin.messages().text("other-page", "Other page: /vbot help %s",
            page == 1 ? 2 : 1), NamedTextColor.GRAY));
    }

    static List<String> helpLines(int page) {
        return switch (page) {
            case 1 -> List.of(
                "/vbot list - List bots, states and labels",
                "/vbot status <id> - Show detailed status",
                "/vbot history <id> - Show recent events",
                "/vbot doctor [id|selector] - Diagnose setup",
                "/vbot servers - List Velocity backends",
                "/vbot server <id|selector> <server> - Switch server",
                "/vbot movehere <id|selector> - Bring bots across servers",
                "/vbot position <id> - Show protocol position",
                "/vbot move <id> <x> <y> <z> - Move",
                "/vbot look <id> <yaw> <pitch> - Look");
            case 2 -> List.of(
                "/vbot start|stop|reconnect <id|selector> - Control",
                "/vbot command <id|selector> <command...> - Run command",
                "/vbot behavior <id|selector> <start|pause|status>",
                "/vbot behavior <id|selector> follow <player>",
                "/vbot create <id> <name> <password|-> [server|-]",
                "/vbot remove <id> - Remove a managed bot",
                "/vbot monitor [id] - Output monitoring JSON",
                "/vbot reload - Reload configuration");
            default -> List.of();
        };
    }

    private void unknown(CommandSource source, String id) {
        source.sendMessage(Component.text("Unknown bot: " + id, NamedTextColor.RED));
    }

    private void noMatches(CommandSource source, String selector) {
        source.sendMessage(Component.text(plugin.messages().text("no-matches", "No bots matched selector: %s", selector),
            NamedTextColor.RED));
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
        source.sendMessage(Component.text("Requested " + verb + " for " + succeeded + "/" + targets.size()
            + " bot(s).", succeeded == targets.size() ? NamedTextColor.GREEN : NamedTextColor.YELLOW));
    }

    private String labels(String id) {
        return plugin.manager().find(id).map(session -> {
            List<String> labels = new java.util.ArrayList<>();
            session.definition().groups().forEach(group -> labels.add("group:" + group));
            session.definition().tags().forEach(tag -> labels.add("tag:" + tag));
            return labels.isEmpty() ? "" : " {" + String.join(",", labels) + "}";
        }).orElse("");
    }

    private boolean hasPermission(CommandSource source, String action) {
        if (source.hasPermission(ADMIN_PERMISSION)) {
            return true;
        }
        String permission = switch (action) {
            case "help", "list", "status", "monitor", "history", "doctor", "servers", "position" -> VIEW_PERMISSION;
            case "create", "remove" -> CREATE_PERMISSION;
            case "reload" -> RELOAD_PERMISSION;
            default -> CONTROL_PERMISSION;
        };
        return source.hasPermission(permission);
    }

    private static String safeDetail(Exception exception) {
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

    private static String formatPosition(BotPosition position) {
        if (!position.known()) {
            return "unknown";
        }
        return String.format(Locale.ROOT, "x=%.3f y=%.3f z=%.3f yaw=%.2f pitch=%.2f",
            position.x(), position.y(), position.z(), position.yaw(), position.pitch());
    }

    private static String instantOrNull(Instant instant) {
        return instant == null ? null : instant.toString();
    }

    private static double finiteDouble(String raw, String name) {
        double value = Double.parseDouble(raw);
        if (!Double.isFinite(value)) {
            throw new IllegalArgumentException(name + " must be a finite number");
        }
        return value;
    }

    private static float finiteFloat(String raw, String name) {
        float value = Float.parseFloat(raw);
        if (!Float.isFinite(value)) {
            throw new IllegalArgumentException(name + " must be a finite number");
        }
        return value;
    }

    @Override
    public List<String> suggest(Invocation invocation) {
        String[] args = invocation.arguments();
        if (args.length <= 1) {
            String prefix = args.length == 0 ? "" : args[0].toLowerCase(Locale.ROOT);
            return ACTIONS.stream().filter(action -> action.startsWith(prefix)).toList();
        }
        if (args.length == 2 && BOT_ID_ACTIONS.contains(args[0].toLowerCase(Locale.ROOT))) {
            String prefix = args[1].toLowerCase(Locale.ROOT);
            return selectorSuggestions().stream()
                .filter(id -> id.toLowerCase(Locale.ROOT).startsWith(prefix)).toList();
        }
        if (args.length == 2 && args[0].equalsIgnoreCase("help")) {
            return List.of("1", "2").stream().filter(page -> page.startsWith(args[1])).toList();
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
        return List.of();
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
