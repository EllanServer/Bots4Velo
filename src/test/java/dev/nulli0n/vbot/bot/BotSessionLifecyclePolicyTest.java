package dev.nulli0n.vbot.bot;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class BotSessionLifecyclePolicyTest {
    @Test
    void automaticRulesBlockOnlyOperatorActionableAuthenticationFailures() {
        assertThat(BotSession.automaticStartAllowed(BotState.FAILED, true)).isFalse();
        assertThat(BotSession.automaticStartAllowed(BotState.STOPPED, true)).isFalse();

        assertThat(BotSession.automaticStartAllowed(BotState.FAILED, false)).isTrue();
        assertThat(BotSession.automaticStartAllowed(BotState.STOPPED, false)).isTrue();
        assertThat(BotSession.automaticStartAllowed(BotState.PLAY, false)).isFalse();
        assertThat(BotSession.automaticRecoveryAllowed(true)).isFalse();
        assertThat(BotSession.automaticRecoveryAllowed(false)).isTrue();
    }
}
