package dev.nulli0n.vbot;

import org.junit.jupiter.api.Test;

import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

class ReloadHandoffGateTest {
    @Test
    void replacementCannotStartUntilOldProxyPlayerIsGone() {
        AtomicBoolean oldPlayerPresent = new AtomicBoolean(true);
        AtomicInteger starts = new AtomicInteger();
        ReloadHandoffGate gate = new ReloadHandoffGate(
            () -> true, () -> !oldPlayerPresent.get(), starts::incrementAndGet);

        assertThat(gate.poll()).isEqualTo(ReloadHandoffGate.PollResult.WAITING);
        assertThat(gate.poll()).isEqualTo(ReloadHandoffGate.PollResult.WAITING);
        assertThat(starts).hasValue(0);

        oldPlayerPresent.set(false);
        assertThat(gate.poll()).isEqualTo(ReloadHandoffGate.PollResult.STARTED);
        assertThat(gate.poll()).isEqualTo(ReloadHandoffGate.PollResult.FINISHED);
        assertThat(starts).hasValue(1);
    }

    @Test
    void supersededReplacementIsCancelledWithoutStarting() {
        AtomicBoolean current = new AtomicBoolean(false);
        AtomicInteger starts = new AtomicInteger();
        ReloadHandoffGate gate = new ReloadHandoffGate(
            current::get, () -> true, starts::incrementAndGet);

        assertThat(gate.poll()).isEqualTo(ReloadHandoffGate.PollResult.CANCELLED);
        current.set(true);
        assertThat(gate.poll()).isEqualTo(ReloadHandoffGate.PollResult.FINISHED);
        assertThat(starts).hasValue(0);
    }

    @Test
    void chainedReloadCarriesEveryStillPendingUsernameIntoTheNextGate() {
        Set<String> carried = ReloadHandoffGate.mergePendingUsernames(Set.of("X"), Set.of("Y"));
        Set<String> onlinePlayers = new java.util.HashSet<>(Set.of("X"));
        AtomicInteger starts = new AtomicInteger();
        ReloadHandoffGate next = new ReloadHandoffGate(
            () -> true,
            () -> carried.stream().noneMatch(onlinePlayers::contains),
            starts::incrementAndGet);

        assertThat(carried).containsExactlyInAnyOrder("X", "Y");
        assertThat(next.poll()).isEqualTo(ReloadHandoffGate.PollResult.WAITING);
        assertThat(starts).hasValue(0);

        onlinePlayers.remove("X");
        assertThat(next.poll()).isEqualTo(ReloadHandoffGate.PollResult.STARTED);
        assertThat(starts).hasValue(1);
    }
}
