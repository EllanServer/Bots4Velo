package dev.nulli0n.vbot.command;

import java.util.Comparator;
import java.util.List;
import java.util.Objects;

/** Builds a stable filtered and paginated bot list without touching live sessions. */
final class BotListView {
    private static final Comparator<BotViewRow> BY_ID = Comparator
        .comparing(BotViewRow::id, String.CASE_INSENSITIVE_ORDER)
        .thenComparing(BotViewRow::id);

    private BotListView() {
    }

    static PagedView<BotViewRow> create(List<BotViewRow> rows, BotViewQuery query) {
        Objects.requireNonNull(rows, "rows");
        Objects.requireNonNull(query, "query");
        List<BotViewRow> filtered = rows.stream()
            .map(row -> Objects.requireNonNull(row, "row"))
            .filter(query::matches)
            .sorted(BY_ID)
            .toList();
        return PagedView.slice(filtered, query.page());
    }
}
