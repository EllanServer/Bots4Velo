package dev.nulli0n.vbot.command;

import com.velocitypowered.api.command.CommandSource;
import dev.nulli0n.vbot.Bots4VeloPlugin;
import net.kyori.adventure.text.format.NamedTextColor;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

/** Handles maintenance locks without expanding the main command router. */
final class MaintenanceCommandHandler {
    private final Bots4VeloPlugin plugin;

    MaintenanceCommandHandler(Bots4VeloPlugin plugin) {
        this.plugin = plugin;
    }

    void hold(CommandSource source, String[] args) {
        if (args.length < 2) {
            feedback(source, NamedTextColor.YELLOW, "usage-hold",
                "Usage: /vbot hold <id|selector> [--ttl <number><s|m|h|d>] [reason...]");
            return;
        }
        Duration ttl = null;
        List<String> reasonParts = new ArrayList<>();
        for (int index = 2; index < args.length; index++) {
            if (args[index].equalsIgnoreCase("--ttl")) {
                if (ttl != null || index + 1 >= args.length) {
                    invalidTtl(source);
                    return;
                }
                try {
                    ttl = MaintenanceTtl.parse(args[++index]);
                }
                catch (IllegalArgumentException exception) {
                    invalidTtl(source);
                    return;
                }
            }
            else if (args[index].startsWith("--")) {
                feedback(source, NamedTextColor.RED, "hold-unknown-option",
                    "Unknown hold option: %s", args[index]);
                return;
            }
            else {
                reasonParts.add(args[index]);
            }
        }
        List<dev.nulli0n.vbot.bot.BotSession> targets = plugin.selectBots(args[1]);
        if (targets.isEmpty()) {
            feedback(source, NamedTextColor.RED, "no-matches",
                "No bots matched selector: %s", args[1]);
            return;
        }
        String reason = reasonParts.isEmpty()
            ? plugin.messages().text("hold-default-reason", "operator maintenance")
            : String.join(" ", reasonParts);
        int held = 0;
        for (var target : targets) {
            String id = target.definition().id();
            String currentServer = plugin.currentServer(id).orElse("");
            boolean success = ttl == null
                ? plugin.holdBot(id, reason, currentServer)
                : plugin.holdBot(id, reason, ttl, currentServer);
            if (success) {
                held++;
            }
        }
        String expiry = ttl == null ? plugin.messages().text("value-never", "never") : formatDuration(ttl);
        feedback(source, held == targets.size() ? NamedTextColor.GREEN : NamedTextColor.YELLOW,
            "hold-result", "Held %s/%s bot(s); expires: %s; reason: %s", held, targets.size(), expiry, reason);
    }

    void resume(CommandSource source, String[] args) {
        if (args.length != 2) {
            feedback(source, NamedTextColor.YELLOW, "usage-resume",
                "Usage: /vbot resume <id|selector>");
            return;
        }
        List<dev.nulli0n.vbot.bot.BotSession> targets = selectForResume(args[1]);
        if (targets.isEmpty()) {
            feedback(source, NamedTextColor.RED, "no-matches",
                "No bots matched selector: %s", args[1]);
            return;
        }
        int resumed = 0;
        for (var target : targets) {
            if (plugin.resumeBot(target.definition().id())) {
                resumed++;
            }
        }
        feedback(source, resumed > 0 ? NamedTextColor.GREEN : NamedTextColor.YELLOW,
            "resume-result", "Resumed %s/%s bot(s); bots remain stopped until explicitly started.",
            resumed, targets.size());
    }

    private List<dev.nulli0n.vbot.bot.BotSession> selectForResume(String selector) {
        String normalized = selector == null ? "" : selector.trim();
        if (!normalized.toLowerCase(java.util.Locale.ROOT).startsWith("@server:")) {
            return plugin.selectBots(selector);
        }
        String server = normalized.substring("@server:".length()).trim();
        if (server.isEmpty()) {
            return List.of();
        }
        return plugin.manager().sessions().stream()
            .filter(session -> plugin.currentServer(session.definition().id())
                .map(current -> current.equalsIgnoreCase(server))
                .orElseGet(() -> plugin.manager().holdSnapshot(session.definition().id())
                    .map(hold -> hold.server().equalsIgnoreCase(server)).orElse(false)))
            .toList();
    }

    private void invalidTtl(CommandSource source) {
        feedback(source, NamedTextColor.RED, "hold-invalid-ttl",
            "Hold TTL must be supplied once as <number><s|m|h|d> (maximum 30d).");
    }

    private void feedback(CommandSource source, NamedTextColor color, String key,
                          String englishFallback, Object... arguments) {
        source.sendMessage(CommandUi.feedback(plugin.messages(), color, key, englishFallback, arguments));
    }

    private static String formatDuration(Duration duration) {
        long seconds = duration.toSeconds();
        long days = seconds / 86_400;
        long hours = (seconds % 86_400) / 3_600;
        long minutes = (seconds % 3_600) / 60;
        long remainingSeconds = seconds % 60;
        if (days > 0) {
            return days + "d " + hours + "h";
        }
        if (hours > 0) {
            return hours + "h " + minutes + "m";
        }
        if (minutes > 0) {
            return minutes + "m " + remainingSeconds + "s";
        }
        return remainingSeconds + "s";
    }
}
