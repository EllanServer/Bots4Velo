package dev.nulli0n.vbot.bot;

import dev.nulli0n.vbot.config.BotPluginConfig.ReconnectConfig;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class ReconnectPolicyTest {
    private final ReconnectPolicy policy = new ReconnectPolicy(new ReconnectConfig(1_000, 10_000, 2.0, 0.1, 3));

    @Test
    void appliesExponentialBackoffAndCap() {
        assertThat(policy.delayMillis(1, 0.5)).isEqualTo(1_000);
        assertThat(policy.delayMillis(2, 0.5)).isEqualTo(2_000);
        assertThat(policy.delayMillis(8, 0.5)).isEqualTo(10_000);
    }

    @Test
    void appliesBoundedJitter() {
        assertThat(policy.delayMillis(1, 0.0)).isEqualTo(900);
        assertThat(policy.delayMillis(1, 1.0)).isEqualTo(1_100);
    }

    @Test
    void enforcesAttemptLimit() {
        assertThat(policy.allows(3)).isTrue();
        assertThat(policy.allows(4)).isFalse();
    }
}
