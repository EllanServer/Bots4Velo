package dev.nulli0n.vbot.command;

import com.velocitypowered.api.command.CommandSource;
import dev.nulli0n.vbot.Bots4VeloPlugin;
import dev.nulli0n.vbot.bot.ActivationKind;
import dev.nulli0n.vbot.bot.BotSnapshot;
import dev.nulli0n.vbot.bot.BotState;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.event.ClickEvent;
import net.kyori.adventure.text.event.HoverEvent;
import net.kyori.adventure.text.format.NamedTextColor;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/** Renders the read-only, filterable bot list and activation queue commands. */
final class BotQueryCommandHandler {
    private final Bots4VeloPlugin plugin;

    BotQueryCommandHandler(Bots4VeloPlugin plugin) {
        this.plugin = plugin;
    }

    void list(CommandSource source, String[] arguments) {
        BotViewQuery query = parse(source, "list", arguments);
        if (query == null) {
            return;
        }
        List<BotViewRow> allRows = rows(false);
        PagedView<BotViewRow> view = BotListView.create(allRows, query);
        if (!validPage(source, query, view.pagination())) {
            return;
        }

        Pagination page = view.pagination();
        source.sendMessage(CommandUi.feedback(plugin.messages(), NamedTextColor.GOLD,
            "list-query-title", "Bots: %s match(es) | page %s/%s", page.totalItems(), page.page(),
            Math.max(1, page.totalPages())));
        Map<String, BotSnapshot> snapshots = new HashMap<>();
        plugin.manager().snapshots().forEach(snapshot ->
            snapshots.put(snapshot.id().toLowerCase(Locale.ROOT), snapshot));
        for (BotViewRow row : view.rows()) {
            BotSnapshot snapshot = snapshots.get(row.id().toLowerCase(Locale.ROOT));
            String protocol = snapshot == null ? "-" : snapshot.protocolVersion();
            Component identity = Component.text(row.id() + " / " + row.username(), NamedTextColor.AQUA)
                .clickEvent(ClickEvent.runCommand("/vbot status " + row.id()))
                .hoverEvent(HoverEvent.showText(Component.text(plugin.messages().text("list-click-status",
                    "Open status for %s", row.id()), NamedTextColor.YELLOW)));
            Component line = Component.text(" > ", NamedTextColor.DARK_GRAY)
                .append(identity)
                .append(Component.text(" [" + row.state() + "]", stateColor(row.state())))
                .append(Component.text(" @ " + displayServer(row.server()) + " [" + protocol + "]"
                    + labels(row), NamedTextColor.GRAY));
            if (row.held()) {
                line = line.append(Component.text(" [HELD]", NamedTextColor.YELLOW)
                    .hoverEvent(HoverEvent.showText(Component.text(row.holdReason(), NamedTextColor.GRAY))));
            }
            source.sendMessage(line);
        }
        if (view.rows().isEmpty()) {
            source.sendMessage(Component.text(plugin.messages().text("list-empty",
                "No bots match this query."), NamedTextColor.GRAY));
        }
        sendPagination(source, "list", query, page);
    }

    void queue(CommandSource source, String[] arguments) {
        BotViewQuery query = parse(source, "queue", arguments);
        if (query == null) {
            return;
        }
        List<BotViewRow> rows = rows(true);
        Map<String, BotViewRow> rowsById = new HashMap<>();
        rows.forEach(row -> rowsById.put(row.id().toLowerCase(Locale.ROOT), row));

        List<BotQueueView.Entry> entries = new ArrayList<>();
        plugin.manager().activationSnapshots().forEach(activation -> {
            String key = activation.botId().toLowerCase(Locale.ROOT);
            BotViewRow row = rowsById.get(key);
            if (row != null) {
                entries.add(new BotQueueView.Entry(row, activation.scheduledAt(), activation.kind()));
            }
        });
        plugin.manager().sessions().forEach(session -> {
            String key = session.definition().id().toLowerCase(Locale.ROOT);
            BotViewRow row = rowsById.get(key);
            var next = session.nextConnectionAttempt();
            if (row != null && next.isPresent() && !row.held()) {
                entries.add(new BotQueueView.Entry(row, next.get().scheduledAt(), next.get().kind()));
            }
        });

        PagedView<BotQueueView.Row> view = BotQueueView.create(entries, query);
        if (!validPage(source, query, view.pagination())) {
            return;
        }
        Pagination page = view.pagination();
        source.sendMessage(CommandUi.feedback(plugin.messages(), NamedTextColor.GOLD,
            "queue-title", "Pending activations: %s | page %s/%s", page.totalItems(), page.page(),
            Math.max(1, page.totalPages())));
        if (plugin.manager().activationsPaused()) {
            source.sendMessage(Component.text(plugin.messages().text("queue-activations-paused",
                "Activations are paused until the reload player handoff completes."),
                NamedTextColor.YELLOW));
        }
        Instant now = Instant.now();
        for (BotQueueView.Row queued : view.rows()) {
            String reason = queued.kind() == ActivationKind.RECONNECT
                ? plugin.messages().text("queue-reason-reconnect", "reconnect")
                : plugin.messages().text("queue-reason-activation", "start");
            String eta = queued.estimatedActivationAt().map(at -> formatEta(now, at))
                .orElseGet(() -> plugin.messages().text("queue-eta-unknown", "unknown"));
            source.sendMessage(Component.text(" #" + queued.position() + " ", NamedTextColor.DARK_GRAY)
                .append(Component.text(queued.bot().id(), NamedTextColor.AQUA)
                    .clickEvent(ClickEvent.runCommand("/vbot status " + queued.bot().id())))
                .append(Component.text(plugin.messages().text("queue-entry",
                    " | %s | ETA %s", reason, eta), NamedTextColor.GRAY)));
        }
        if (view.rows().isEmpty()) {
            source.sendMessage(Component.text(plugin.messages().text("queue-empty",
                "No pending starts or reconnects match this query."), NamedTextColor.GRAY));
        }
        sendPagination(source, "queue", query, page);
    }

    private BotViewQuery parse(CommandSource source, String action, String[] arguments) {
        try {
            return BotViewQuery.parse(Arrays.copyOfRange(arguments, 1, arguments.length));
        }
        catch (BotViewQuery.ParseException exception) {
            String key;
            String fallback;
            switch (exception.failure()) {
                case UNKNOWN_OPTION -> {
                    key = "query-unknown-option";
                    fallback = "Unknown query option: %s";
                }
                case DUPLICATE_OPTION -> {
                    key = "query-duplicate-option";
                    fallback = "Query option was supplied more than once: %s";
                }
                case DUPLICATE_SELECTOR -> {
                    key = "query-duplicate-selector";
                    fallback = "Only one selector may be supplied; unexpected: %s";
                }
                case INVALID_SELECTOR -> {
                    key = "query-invalid-selector";
                    fallback = "Selector requires a non-empty group, tag, or server name: %s";
                }
                case MISSING_VALUE -> {
                    key = "query-missing-value";
                    fallback = "Query option requires a value: %s";
                }
                case INVALID_STATE -> {
                    key = "query-invalid-state";
                    fallback = "Unknown bot state: %s";
                }
                case INVALID_PAGE -> {
                    key = "query-invalid-page";
                    fallback = "Page must be a positive integer: %s";
                }
                case EMPTY_ARGUMENT -> {
                    key = "query-empty-argument";
                    fallback = "Query arguments must not be empty: %s";
                }
                default -> throw exception;
            }
            source.sendMessage(CommandUi.feedback(plugin.messages(), NamedTextColor.RED,
                key, fallback, exception.token()));
            sendUsage(source, action);
            return null;
        }
    }

    private void sendUsage(CommandSource source, String action) {
        String key = action.equals("queue") ? "usage-queue" : "usage-list";
        source.sendMessage(CommandUi.feedback(plugin.messages(), NamedTextColor.YELLOW, key,
            "Usage: /vbot %s [id|selector] [--state <state>] [--server <server>] [--failed] [--page <n>]",
            action));
    }

    private boolean validPage(CommandSource source, BotViewQuery query, Pagination pagination) {
        if ((pagination.totalPages() == 0 && query.page() == 1)
            || (pagination.totalPages() > 0 && query.page() <= pagination.totalPages())) {
            return true;
        }
        source.sendMessage(CommandUi.feedback(plugin.messages(), NamedTextColor.RED,
            "query-page-out-of-range", "Page %s does not exist; maximum page is %s.", query.page(),
            Math.max(1, pagination.totalPages())));
        return false;
    }

    private List<BotViewRow> rows(boolean configuredServerFallback) {
        return plugin.manager().sessions().stream().map(session -> {
            BotSnapshot snapshot = session.snapshot();
            var hold = plugin.manager().holdSnapshot(snapshot.id());
            String server = plugin.currentServer(snapshot.id()).filter(value -> !value.isBlank())
                .orElseGet(() -> configuredServerFallback ? session.definition().targetServer() : "");
            return new BotViewRow(snapshot.id(), snapshot.username(), snapshot.state(),
                server, new HashSet<>(session.definition().groups()),
                new HashSet<>(session.definition().tags()), hold.isPresent(),
                hold.map(value -> value.reason()).orElse(""),
                snapshot.failureCategory());
        }).toList();
    }

    private void sendPagination(CommandSource source, String action, BotViewQuery query, Pagination page) {
        if (page.totalPages() <= 1) {
            return;
        }
        Component navigation = Component.empty();
        if (page.hasPrevious()) {
            navigation = navigation.append(pageButton("pagination-previous", "Previous",
                queryCommand(action, query, page.page() - 1)));
        }
        else {
            navigation = navigation.append(Component.text("[<]", NamedTextColor.DARK_GRAY));
        }
        navigation = navigation.append(Component.text(plugin.messages().text("pagination-page",
            "  Page %s/%s  ", page.page(), page.totalPages()), NamedTextColor.GRAY));
        if (page.hasNext()) {
            navigation = navigation.append(pageButton("pagination-next", "Next",
                queryCommand(action, query, page.page() + 1)));
        }
        else {
            navigation = navigation.append(Component.text("[>]", NamedTextColor.DARK_GRAY));
        }
        source.sendMessage(navigation);
    }

    private Component pageButton(String key, String fallback, String command) {
        return Component.text("[" + plugin.messages().text(key, fallback) + "]", NamedTextColor.AQUA)
            .clickEvent(ClickEvent.runCommand(command))
            .hoverEvent(HoverEvent.showText(Component.text(plugin.messages().text("pagination-hover",
                "Open %s", command), NamedTextColor.YELLOW)));
    }

    private static String queryCommand(String action, BotViewQuery query, int page) {
        StringBuilder command = new StringBuilder("/vbot ").append(action);
        query.selector().ifPresent(value -> command.append(' ').append(value));
        query.state().ifPresent(value -> command.append(" --state ").append(value.name()));
        query.server().ifPresent(value -> command.append(" --server ").append(value));
        if (query.failedOnly()) {
            command.append(" --failed");
        }
        return command.append(" --page ").append(page).toString();
    }

    private String formatEta(Instant now, Instant activationAt) {
        long millis = Duration.between(now, activationAt).toMillis();
        if (millis <= 0) {
            return plugin.messages().text("queue-eta-now", "now");
        }
        long seconds = Math.max(1, (millis + 999) / 1000);
        long hours = seconds / 3600;
        long minutes = (seconds % 3600) / 60;
        long remainingSeconds = seconds % 60;
        if (hours > 0) {
            return hours + "h " + minutes + "m";
        }
        if (minutes > 0) {
            return minutes + "m " + remainingSeconds + "s";
        }
        return remainingSeconds + "s";
    }

    private static NamedTextColor stateColor(BotState state) {
        return switch (state) {
            case PLAY -> NamedTextColor.GREEN;
            case FAILED -> NamedTextColor.RED;
            case CONNECTING, LOGIN, CONFIGURATION, RECONNECT_WAIT -> NamedTextColor.YELLOW;
            case STOPPED, STOPPING -> NamedTextColor.GRAY;
        };
    }

    private static String displayServer(String server) {
        return server.isBlank() ? "-" : server;
    }

    private static String labels(BotViewRow row) {
        List<String> labels = new ArrayList<>();
        row.groups().forEach(group -> labels.add("group:" + group));
        row.tags().forEach(tag -> labels.add("tag:" + tag));
        labels.sort(String.CASE_INSENSITIVE_ORDER);
        return labels.isEmpty() ? "" : " {" + String.join(",", labels) + "}";
    }
}
