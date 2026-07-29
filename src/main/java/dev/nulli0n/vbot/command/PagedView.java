package dev.nulli0n.vbot.command;

import java.util.List;
import java.util.Objects;

/** Immutable page payload shared by list and queue renderers. */
record PagedView<T>(List<T> rows, Pagination pagination) {
    PagedView {
        rows = List.copyOf(Objects.requireNonNull(rows, "rows"));
        pagination = Objects.requireNonNull(pagination, "pagination");
    }

    static <T> PagedView<T> slice(List<T> rows, int requestedPage) {
        Objects.requireNonNull(rows, "rows");
        Pagination pagination = Pagination.of(requestedPage, Pagination.DEFAULT_PAGE_SIZE, rows.size());
        if (pagination.firstItem() == 0) {
            return new PagedView<>(List.of(), pagination);
        }
        return new PagedView<>(rows.subList(pagination.firstItem() - 1, pagination.lastItem()), pagination);
    }
}
