package dev.nulli0n.vbot.observe;

import dev.nulli0n.vbot.bot.BotSnapshot;
import dev.nulli0n.vbot.bot.BotState;
import dev.nulli0n.vbot.bot.FailureCategory;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class PrometheusExporterTest {
    @Test
    void rendersEscapedLabelsAndOperationalCounters() {
        BotSnapshot snapshot = new BotSnapshot("farm\"one", "AFK\\One", "26.2", "configured",
            BotState.PLAY, 2, null, 3, 4, 5, null, null, null, "none", 0, 0,
            "authentication timed out after 2500 ms", null, 6, FailureCategory.AUTHENTICATION, List.of());

        String metrics = PrometheusExporter.render(List.of(snapshot));

        assertThat(metrics)
            .contains("bots4velo_bots_total 1")
            .contains("bots4velo_bot_online{id=\"farm\\\"one\",username=\"AFK\\\\One\"} 1")
            .contains("bots4velo_bot_disconnects_total{id=\"farm\\\"one\",username=\"AFK\\\\One\"} 4")
            .contains("bots4velo_bot_failure{id=\"farm\\\"one\",username=\"AFK\\\\One\",category=\"authentication\"} 1");
    }
}
