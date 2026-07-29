package dev.nulli0n.vbot.command;

import dev.nulli0n.vbot.bot.ActivationKind;

import java.time.Instant;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.stream.IntStream;

/** Builds a queue view ordered by expected activation time and then bot id. */
final class BotQueueView {
    private static final Comparator<Entry> BY_ACTIVATION = Comparator
        .comparing((Entry entry) -> entry.estimatedActivationAt().orElse(Instant.MAX))
        .thenComparing(entry -> entry.bot().id(), String.CASE_INSENSITIVE_ORDER)
        .thenComparing(entry -> entry.bot().id());

    private BotQueueView() {
    }

    static PagedView<Row> create(List<Entry> entries, BotViewQuery query) {
        Objects.requireNonNull(entries, "entries");
        Objects.requireNonNull(query, "query");
        Map<String, Entry> earliestByBot = new LinkedHashMap<>();
        entries.stream()
            .map(entry -> Objects.requireNonNull(entry, "entry"))
            .filter(entry -> query.matches(entry.bot()))
            .forEach(entry -> earliestByBot.merge(entry.bot().id().toLowerCase(Locale.ROOT), entry,
                BotQueueView::earlier));
        List<Entry> sortedEntries = earliestByBot.values().stream()
            .sorted(BY_ACTIVATION)
            .toList();
        List<Row> sorted = IntStream.range(0, sortedEntries.size())
            .mapToObj(index -> {
                Entry entry = sortedEntries.get(index);
                return new Row(index + 1, entry.bot(), entry.estimatedActivationAt(), entry.kind());
            })
            .toList();
        return PagedView.slice(sorted, query.page());
    }

    private static Entry earlier(Entry left, Entry right) {
        return BY_ACTIVATION.compare(left, right) <= 0 ? left : right;
    }

    record Entry(BotViewRow bot, Optional<Instant> estimatedActivationAt, ActivationKind kind) {
        Entry(BotViewRow bot, Instant estimatedActivationAt, ActivationKind kind) {
            this(bot, Optional.ofNullable(estimatedActivationAt), kind);
        }

        Entry {
            bot = Objects.requireNonNull(bot, "bot");
            estimatedActivationAt = Objects.requireNonNull(estimatedActivationAt, "estimatedActivationAt");
            kind = Objects.requireNonNull(kind, "kind");
        }
    }

    record Row(int position, BotViewRow bot, Optional<Instant> estimatedActivationAt, ActivationKind kind) {
        Row(int position, BotViewRow bot, Instant estimatedActivationAt, ActivationKind kind) {
            this(position, bot, Optional.ofNullable(estimatedActivationAt), kind);
        }

        Row {
            if (position < 1) {
                throw new IllegalArgumentException("position must be positive");
            }
            bot = Objects.requireNonNull(bot, "bot");
            estimatedActivationAt = Objects.requireNonNull(estimatedActivationAt, "estimatedActivationAt");
            kind = Objects.requireNonNull(kind, "kind");
        }
    }
}
