package dev.nulli0n.vbot.command;

import dev.nulli0n.vbot.bot.BotState;
import dev.nulli0n.vbot.bot.FailureCategory;

import java.util.Collection;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

/** Immutable projection of the bot and runtime metadata needed by command views. */
record BotViewRow(
    String id,
    String username,
    BotState state,
    String server,
    Set<String> groups,
    Set<String> tags,
    boolean held,
    String holdReason,
    FailureCategory failureCategory
) {
    BotViewRow {
        id = requiredText(id, "id");
        username = requiredText(username, "username");
        state = Objects.requireNonNull(state, "state");
        server = optionalText(server);
        groups = normalizedLabels(groups, "groups");
        tags = normalizedLabels(tags, "tags");
        holdReason = optionalText(holdReason);
        failureCategory = Objects.requireNonNull(failureCategory, "failureCategory");
    }

    boolean failed() {
        return state == BotState.FAILED || failureCategory != FailureCategory.NONE;
    }

    private static Set<String> normalizedLabels(Collection<String> values, String name) {
        Objects.requireNonNull(values, name);
        return values.stream()
            .map(value -> requiredText(value, name).toLowerCase(Locale.ROOT))
            .collect(Collectors.toUnmodifiableSet());
    }

    private static String requiredText(String value, String name) {
        String normalized = Objects.requireNonNull(value, name).trim();
        if (normalized.isEmpty()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
        return normalized;
    }

    private static String optionalText(String value) {
        return value == null ? "" : value.trim();
    }
}
