package dev.nulli0n.vbot.command;

import dev.nulli0n.vbot.bot.ActivationKind;
import dev.nulli0n.vbot.bot.BotState;
import dev.nulli0n.vbot.bot.FailureCategory;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class BotQueueViewTest {
    private static final Instant BASE = Instant.parse("2026-07-30T00:00:00Z");

    @Test
    void sortsByEstimatedActivationThenIdAndAssignsQueuePositions() {
        List<BotQueueView.Entry> entries = List.of(
            entry("zeta", 20),
            entry("Beta", 10),
            entry("alpha", 10),
            entry("Alpha", 10),
            new BotQueueView.Entry(row("held", BotState.STOPPED, "", FailureCategory.NONE),
                java.util.Optional.empty(), ActivationKind.START)
        );

        PagedView<BotQueueView.Row> view = BotQueueView.create(entries, BotViewQuery.parse());

        assertThat(view.rows()).extracting(row -> row.bot().id())
            .containsExactly("Alpha", "Beta", "zeta", "held");
        assertThat(view.rows()).extracting(BotQueueView.Row::position).containsExactly(1, 2, 3, 4);
        assertThat(view.rows().getLast().estimatedActivationAt()).isEmpty();
        assertThat(view.pagination()).isEqualTo(new Pagination(1, 8, 4, 1, 1, 4, false, false));
    }

    @Test
    void appliesSharedFiltersBeforeAssigningQueuePositions() {
        List<BotQueueView.Entry> entries = List.of(
            entry(row("farm01", BotState.CONNECTING, "survival", FailureCategory.NONE), 30),
            entry(row("farm02", BotState.RECONNECT_WAIT, "survival", FailureCategory.NETWORK), 10),
            entry(row("lobby01", BotState.RECONNECT_WAIT, "lobby", FailureCategory.NETWORK), 20)
        );

        PagedView<BotQueueView.Row> view = BotQueueView.create(entries,
            BotViewQuery.parse("--state", "reconnect_wait", "--server", "survival", "--failed"));

        assertThat(view.rows()).singleElement().satisfies(row -> {
            assertThat(row.position()).isEqualTo(1);
            assertThat(row.bot().id()).isEqualTo("farm02");
        });
    }

    @Test
    void paginationPreservesPositionsInTheCompleteFilteredQueue() {
        List<BotQueueView.Entry> entries = IntStream.rangeClosed(1, 10)
            .mapToObj(index -> entry("bot%02d".formatted(index), index))
            .collect(java.util.stream.Collectors.toCollection(ArrayList::new));
        Collections.reverse(entries);

        PagedView<BotQueueView.Row> view = BotQueueView.create(entries, BotViewQuery.parse("--page", "2"));

        assertThat(view.rows()).extracting(BotQueueView.Row::position).containsExactly(9, 10);
        assertThat(view.rows()).extracting(row -> row.bot().id()).containsExactly("bot09", "bot10");
        assertThat(view.pagination()).isEqualTo(new Pagination(2, 8, 10, 2, 9, 10, true, false));
    }

    @Test
    void duplicatePendingSourcesUseTheEarliestRealAttempt() {
        BotViewRow bot = row("farm01", BotState.RECONNECT_WAIT, "survival", FailureCategory.NETWORK);
        PagedView<BotQueueView.Row> view = BotQueueView.create(List.of(
            new BotQueueView.Entry(bot, BASE.plusSeconds(60), ActivationKind.START),
            new BotQueueView.Entry(bot, BASE.plusSeconds(10), ActivationKind.RECONNECT)
        ), BotViewQuery.parse());

        assertThat(view.rows()).singleElement().satisfies(row -> {
            assertThat(row.estimatedActivationAt()).contains(BASE.plusSeconds(10));
            assertThat(row.kind()).isEqualTo(ActivationKind.RECONNECT);
        });
        assertThat(view.pagination().totalItems()).isEqualTo(1);
    }

    @Test
    void queueRecordsValidateAndNormalizeTheirInput() {
        BotViewRow bot = row("bot", BotState.CONNECTING, "", FailureCategory.NONE);
        BotQueueView.Entry entry = new BotQueueView.Entry(bot, BASE, ActivationKind.RECONNECT);
        assertThat(entry.kind()).isEqualTo(ActivationKind.RECONNECT);

        assertThatThrownBy(() -> new BotQueueView.Entry(null, BASE, ActivationKind.START))
            .isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> new BotQueueView.Entry(bot, (java.util.Optional<Instant>) null,
            ActivationKind.START))
            .isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> new BotQueueView.Row(0, bot, BASE, ActivationKind.START))
            .isInstanceOf(IllegalArgumentException.class);
    }

    private static BotQueueView.Entry entry(String id, long seconds) {
        return entry(row(id, BotState.CONNECTING, "", FailureCategory.NONE), seconds);
    }

    private static BotQueueView.Entry entry(BotViewRow row, long seconds) {
        return new BotQueueView.Entry(row, BASE.plus(seconds, ChronoUnit.SECONDS), ActivationKind.START);
    }

    private static BotViewRow row(String id, BotState state, String server, FailureCategory category) {
        return new BotViewRow(id, id, state, server, Set.of("farm"), Set.of(), false, "", category);
    }
}
