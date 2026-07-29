package dev.nulli0n.vbot.command;

/** One-based pagination metadata intended for direct use by command renderers. */
record Pagination(
    int page,
    int pageSize,
    int totalItems,
    int totalPages,
    int firstItem,
    int lastItem,
    boolean hasPrevious,
    boolean hasNext
) {
    static final int DEFAULT_PAGE_SIZE = 8;

    static Pagination of(int page, int pageSize, int totalItems) {
        if (page < 1) {
            throw new IllegalArgumentException("page must be positive");
        }
        if (pageSize < 1) {
            throw new IllegalArgumentException("pageSize must be positive");
        }
        if (totalItems < 0) {
            throw new IllegalArgumentException("totalItems must not be negative");
        }

        int totalPages = totalItems == 0 ? 0 : ((totalItems - 1) / pageSize) + 1;
        long offset = (long) (page - 1) * pageSize;
        boolean containsItems = offset < totalItems;
        int firstItem = containsItems ? Math.toIntExact(offset + 1) : 0;
        int lastItem = containsItems ? (int) Math.min(offset + pageSize, totalItems) : 0;
        return new Pagination(page, pageSize, totalItems, totalPages, firstItem, lastItem,
            page > 1 && totalPages > 0, page < totalPages);
    }
}
