package dev.nulli0n.vbot.command;

import dev.nulli0n.vbot.bot.BotState;
import dev.nulli0n.vbot.bot.FailureCategory;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class BotListViewTest {
    @Test
    void filtersBySelectorsStateServerAndFailure() {
        List<BotViewRow> rows = List.of(
            row("Farm01", BotState.PLAY, "Survival", Set.of("workers"), Set.of("farm"), FailureCategory.NONE),
            row("Farm02", BotState.FAILED, "Survival", Set.of("workers"), Set.of("backup"),
                FailureCategory.AUTHENTICATION),
            row("Lobby01", BotState.RECONNECT_WAIT, "Lobby", Set.of("lobby"), Set.of("backup"),
                FailureCategory.NETWORK)
        );

        assertThat(ids(BotListView.create(rows, BotViewQuery.parse("@group:workers")))).containsExactly("Farm01", "Farm02");
        assertThat(ids(BotListView.create(rows, BotViewQuery.parse("@tag:backup")))).containsExactly("Farm02", "Lobby01");
        assertThat(ids(BotListView.create(rows, BotViewQuery.parse("@backup")))).containsExactly("Farm02", "Lobby01");
        assertThat(ids(BotListView.create(rows, BotViewQuery.parse("@server:lobby")))).containsExactly("Lobby01");
        assertThat(ids(BotListView.create(rows, BotViewQuery.parse("farm01")))).containsExactly("Farm01");
        assertThat(ids(BotListView.create(rows, BotViewQuery.parse("--state", "play")))).containsExactly("Farm01");
        assertThat(ids(BotListView.create(rows, BotViewQuery.parse("--server", "SURVIVAL"))))
            .containsExactly("Farm01", "Farm02");
        assertThat(ids(BotListView.create(rows, BotViewQuery.parse("--failed"))))
            .containsExactly("Farm02", "Lobby01");
        assertThat(ids(BotListView.create(rows, BotViewQuery.parse("@all", "--failed", "--server", "survival"))))
            .containsExactly("Farm02");
    }

    @Test
    void failedIncludesFailedStateEvenWithoutACategorizedFailure() {
        BotViewRow row = row("failed", BotState.FAILED, "", Set.of(), Set.of(), FailureCategory.NONE);

        assertThat(ids(BotListView.create(List.of(row), BotViewQuery.parse("--failed"))))
            .containsExactly("failed");
    }

    @Test
    void sortsByIdCaseInsensitivelyWithADeterministicTieBreaker() {
        List<BotViewRow> rows = List.of(
            row("zeta", BotState.PLAY),
            row("beta", BotState.PLAY),
            row("Alpha", BotState.PLAY),
            row("alpha", BotState.PLAY)
        );

        assertThat(ids(BotListView.create(rows, BotViewQuery.parse())))
            .containsExactly("Alpha", "alpha", "beta", "zeta");
    }

    @Test
    void paginatesAtEightRowsAndReportsOneBasedMetadata() {
        List<BotViewRow> rows = IntStream.rangeClosed(1, 18)
            .mapToObj(index -> row("bot%02d".formatted(index), BotState.PLAY))
            .toList();

        PagedView<BotViewRow> page = BotListView.create(rows, BotViewQuery.parse("--page", "2"));

        assertThat(page.rows()).extracting(BotViewRow::id)
            .containsExactly("bot09", "bot10", "bot11", "bot12", "bot13", "bot14", "bot15", "bot16");
        assertThat(page.pagination()).isEqualTo(new Pagination(2, 8, 18, 3, 9, 16, true, true));

        PagedView<BotViewRow> last = BotListView.create(rows, BotViewQuery.parse("--page", "3"));
        assertThat(last.rows()).extracting(BotViewRow::id).containsExactly("bot17", "bot18");
        assertThat(last.pagination()).isEqualTo(new Pagination(3, 8, 18, 3, 17, 18, true, false));
    }

    @Test
    void reportsEmptyAndOutOfRangePagesWithoutClampingTheRequest() {
        PagedView<BotViewRow> empty = BotListView.create(List.of(), BotViewQuery.parse());
        assertThat(empty.rows()).isEmpty();
        assertThat(empty.pagination()).isEqualTo(new Pagination(1, 8, 0, 0, 0, 0, false, false));

        PagedView<BotViewRow> outOfRange = BotListView.create(
            List.of(row("bot", BotState.PLAY)), BotViewQuery.parse("--page", "4"));
        assertThat(outOfRange.rows()).isEmpty();
        assertThat(outOfRange.pagination()).isEqualTo(new Pagination(4, 8, 1, 1, 0, 0, true, false));
    }

    @Test
    void rowAndPageCollectionsAreDefensivelyImmutable() {
        Set<String> groups = new HashSet<>(Set.of("Farm"));
        BotViewRow row = new BotViewRow("bot", "Bot", BotState.PLAY, null, groups, Set.of(),
            true, " maintenance ", FailureCategory.NONE);
        groups.add("later");
        assertThat(row.groups()).containsExactly("farm");
        assertThat(row.server()).isEmpty();
        assertThat(row.holdReason()).isEqualTo("maintenance");
        assertThatThrownBy(() -> row.groups().add("other")).isInstanceOf(UnsupportedOperationException.class);

        List<BotViewRow> source = new ArrayList<>(List.of(row));
        PagedView<BotViewRow> page = BotListView.create(source, BotViewQuery.parse());
        source.clear();
        assertThat(page.rows()).containsExactly(row);
        assertThatThrownBy(() -> page.rows().clear()).isInstanceOf(UnsupportedOperationException.class);
    }

    private static List<String> ids(PagedView<BotViewRow> view) {
        return view.rows().stream().map(BotViewRow::id).toList();
    }

    private static BotViewRow row(String id, BotState state) {
        return row(id, state, "", Set.of(), Set.of(), FailureCategory.NONE);
    }

    private static BotViewRow row(String id, BotState state, String server, Set<String> groups,
                                  Set<String> tags, FailureCategory failureCategory) {
        return new BotViewRow(id, id, state, server, groups, tags, false, "", failureCategory);
    }
}
