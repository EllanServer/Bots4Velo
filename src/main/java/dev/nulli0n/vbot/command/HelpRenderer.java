package dev.nulli0n.vbot.command;

import dev.nulli0n.vbot.BuildConstants;
import dev.nulli0n.vbot.message.PluginMessages;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.event.ClickEvent;
import net.kyori.adventure.text.event.HoverEvent;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.function.Predicate;

/** Builds the compact, clickable help UI without relying on client-version-specific formatting. */
final class HelpRenderer {
    static final int PAGE_COUNT = 4;

    private static final String VIEW_PERMISSION = "bots4velo.view";
    private static final String CONTROL_PERMISSION = "bots4velo.control";
    private static final String CREATE_PERMISSION = "bots4velo.create";
    private static final String RELOAD_PERMISSION = "bots4velo.reload";

    private static final List<HelpEntry> ENTRIES = List.of(
        entry(1, "/vbot list", "/vbot list", "help-list", "List bots, states, servers and labels", VIEW_PERMISSION),
        entry(1, "/vbot status <id>", "/vbot status ", "help-status", "Show detailed bot status", VIEW_PERMISSION),
        entry(1, "/vbot history <id>", "/vbot history ", "help-history", "Show recent bot events", VIEW_PERMISSION),
        entry(1, "/vbot doctor [selector]", "/vbot doctor ", "help-doctor", "Diagnose configuration and connectivity", VIEW_PERMISSION),
        entry(1, "/vbot monitor [id]", "/vbot monitor ", "help-monitor", "Output monitoring JSON", VIEW_PERMISSION),
        entry(1, "/vbot servers", "/vbot servers", "help-servers", "List Velocity backends", VIEW_PERMISSION),
        entry(1, "/vbot server <selector> <server>", "/vbot server ", "help-server", "Switch bots to a backend", CONTROL_PERMISSION),
        entry(1, "/vbot movehere <selector>", "/vbot movehere ", "help-movehere", "Bring bots to your server", CONTROL_PERMISSION),

        entry(2, "/vbot start|stop|reconnect <selector>", "/vbot start ", "help-lifecycle", "Control connections", CONTROL_PERMISSION),
        entry(2, "/vbot command <selector> <command>", "/vbot command ", "help-command", "Run a command as bots", CONTROL_PERMISSION),
        entry(2, "/vbot behavior <selector> <action>", "/vbot behavior ", "help-behavior", "Start, pause or inspect behavior", CONTROL_PERMISSION),
        entry(2, "/vbot behavior <selector> follow <player>", "/vbot behavior ", "help-follow", "Follow or unfollow a player", CONTROL_PERMISSION),
        entry(2, "/vbot position <id>", "/vbot position ", "help-position", "Show the protocol position", VIEW_PERMISSION),
        entry(2, "/vbot move <id> <x> <y> <z>", "/vbot move ", "help-move", "Move a bot", CONTROL_PERMISSION),
        entry(2, "/vbot look <id> <yaw> <pitch>", "/vbot look ", "help-look", "Rotate a bot", CONTROL_PERMISSION),
        entry(3, "/vbot create <id> <name> ...", "/vbot create ", "help-create", "Create a persistent bot", CREATE_PERMISSION),
        entry(3, "/vbot remove <id>", "/vbot remove ", "help-remove", "Remove a managed bot", CREATE_PERMISSION),
        entry(3, "/vbot reload", "/vbot reload", "help-reload", "Validate and reload configuration", RELOAD_PERMISSION),
        entry(3, "/vbot language", "/vbot language", "help-language-view", "Show the current UI language", VIEW_PERMISSION),
        entry(3, "/vbot language <locale>", "/vbot language ", "help-language-set", "Switch the global UI language", RELOAD_PERMISSION),

        entry(4, "/vbot afk <selector> status", "/vbot afk ", "help-afk-status", "Inspect the actual AFK policy", VIEW_PERMISSION),
        entry(4, "/vbot afk <selector> <preset|set|unmanage>", "/vbot afk ", "help-afk-change", "Apply or stop managing an AFK policy", CONTROL_PERMISSION),
        entry(4, "/vbot recover <selector>", "/vbot recover ", "help-recover", "Heal, feed, extinguish and respawn", CONTROL_PERMISSION),
        entry(4, "/vbot invulnerable <selector> <on|off|keep>", "/vbot invulnerable ", "help-invulnerable", "Manage backend invulnerability", CONTROL_PERMISSION),
        entry(4, "/vbot gamemode <selector> <mode|unchanged>", "/vbot gamemode ", "help-gamemode", "Manage backend game mode", CONTROL_PERMISSION),
        entry(4, "/vbot spawnpoint <selector> <mode>", "/vbot spawnpoint ", "help-spawnpoint", "Manage the respawn point", CONTROL_PERMISSION),
        entry(4, "/vbot respawn <selector>", "/vbot respawn ", "help-respawn", "Request a backend respawn", CONTROL_PERMISSION)
    );

    private HelpRenderer() {
    }

    static List<Component> render(PluginMessages messages, int page, Predicate<String> hasPermission) {
        Objects.requireNonNull(messages, "messages");
        Objects.requireNonNull(hasPermission, "hasPermission");
        if (page < 1 || page > PAGE_COUNT) {
            return List.of();
        }

        List<Component> output = new ArrayList<>();
        output.add(header(messages, page));
        List<HelpEntry> visible = entries(page).stream()
            .filter(entry -> hasPermission.test(entry.permission()))
            .toList();
        if (visible.isEmpty()) {
            output.add(Component.text(messages.text("help-empty", "No commands are available on this page."),
                NamedTextColor.DARK_GRAY));
        }
        else {
            visible.forEach(entry -> output.add(commandLine(messages, entry)));
        }
        if (page == 1) {
            output.add(Component.text(messages.text("help-selectors",
                "Selectors: all, @group:<name>, @tag:<name>, @server:<name>"), NamedTextColor.DARK_GRAY));
        }
        if (page == 4) {
            output.add(Component.text(messages.text("help-paper-note",
                "Player-state commands require the Paper companion."), NamedTextColor.DARK_GRAY));
        }
        output.add(navigation(messages, page));
        return List.copyOf(output);
    }

    static List<HelpEntry> entries(int page) {
        return ENTRIES.stream().filter(entry -> entry.page() == page).toList();
    }

    static List<String> plainLines(int page) {
        return entries(page).stream()
            .map(entry -> entry.syntax() + " - " + entry.englishDescription())
            .toList();
    }

    private static Component header(PluginMessages messages, int page) {
        String section = messages.text("help-section-" + page, switch (page) {
            case 1 -> "Overview and servers";
            case 2 -> "Control and movement";
            case 3 -> "Administration and language";
            default -> "Paper player state";
        });
        return Component.text("----- ", NamedTextColor.DARK_GRAY)
            .append(Component.text("Bots4Velo", NamedTextColor.GOLD, TextDecoration.BOLD))
            .append(Component.text(" v" + BuildConstants.VERSION, NamedTextColor.DARK_GRAY))
            .append(Component.text(" | " + section, NamedTextColor.WHITE))
            .append(Component.text(" " + page + "/" + PAGE_COUNT, NamedTextColor.GRAY))
            .append(Component.text(" -----", NamedTextColor.DARK_GRAY));
    }

    private static Component commandLine(PluginMessages messages, HelpEntry entry) {
        Component hover = Component.text(messages.text("help-click-command",
            "Click to insert: %s", entry.suggestion()), NamedTextColor.YELLOW)
            .append(Component.newline())
            .append(Component.text(messages.text("help-permission", "Permission: %s", entry.permission()),
                NamedTextColor.GRAY));
        Component command = Component.text(entry.syntax(), NamedTextColor.AQUA)
            .clickEvent(ClickEvent.suggestCommand(entry.suggestion()))
            .hoverEvent(HoverEvent.showText(hover));
        return Component.text(" > ", NamedTextColor.DARK_GRAY)
            .append(command)
            .append(Component.text(" - " + messages.text(entry.descriptionKey(), entry.englishDescription()),
                NamedTextColor.GRAY));
    }

    private static Component navigation(PluginMessages messages, int page) {
        Component result = Component.empty();
        if (page > 1) {
            result = result.append(pageButton(messages, "<", page - 1));
        }
        else {
            result = result.append(Component.text("<", NamedTextColor.DARK_GRAY));
        }
        result = result.append(Component.text("  ", NamedTextColor.DARK_GRAY));
        for (int candidate = 1; candidate <= PAGE_COUNT; candidate++) {
            if (candidate == page) {
                result = result.append(Component.text("[" + candidate + "]", NamedTextColor.GOLD,
                    TextDecoration.BOLD));
            }
            else {
                result = result.append(pageButton(messages, "[" + candidate + "]", candidate));
            }
            if (candidate < PAGE_COUNT) {
                result = result.append(Component.space());
            }
        }
        result = result.append(Component.text("  ", NamedTextColor.DARK_GRAY));
        if (page < PAGE_COUNT) {
            result = result.append(pageButton(messages, ">", page + 1));
        }
        else {
            result = result.append(Component.text(">", NamedTextColor.DARK_GRAY));
        }
        return result;
    }

    private static Component pageButton(PluginMessages messages, String label, int page) {
        return Component.text(label, NamedTextColor.AQUA)
            .clickEvent(ClickEvent.runCommand("/vbot help " + page))
            .hoverEvent(HoverEvent.showText(Component.text(messages.text("help-open-page",
                "Open help page %s", page), NamedTextColor.YELLOW)));
    }

    private static HelpEntry entry(int page, String syntax, String suggestion, String descriptionKey,
                                   String englishDescription, String permission) {
        return new HelpEntry(page, syntax, suggestion, descriptionKey, englishDescription, permission);
    }

    record HelpEntry(
        int page,
        String syntax,
        String suggestion,
        String descriptionKey,
        String englishDescription,
        String permission
    ) {
    }
}
