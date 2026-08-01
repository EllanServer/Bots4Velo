package dev.nulli0n.vbot.bot;

import dev.nulli0n.vbot.config.BotPluginConfig.ReconnectConfig;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ReconnectStabilityGateTest {
    private final ScheduledExecutorService executor = Executors.newSingleThreadScheduledExecutor();

    @AfterEach
    void stopExecutor() {
        executor.shutdownNow();
    }

    @Test
    void authenticationCompletedAfterPlayStartsItsOwnFullStabilityWindow() throws Exception {
        CountDownLatch reset = new CountDownLatch(1);
        ReconnectStabilityGate gate = gate(Duration.ofMillis(160), ignored -> reset.countDown());

        gate.connectionStarted(1L);
        gate.enteredPlay(1L);

        assertThat(reset.await(220, TimeUnit.MILLISECONDS)).isFalse();
        gate.authenticationCompleted(1L);
        assertThat(reset.await(80, TimeUnit.MILLISECONDS)).isFalse();
        assertThat(reset.await(1, TimeUnit.SECONDS)).isTrue();
    }

    @Test
    void leavingPlayRestartsTheContinuousWindow() throws Exception {
        CountDownLatch reset = new CountDownLatch(1);
        ReconnectStabilityGate gate = gate(Duration.ofMillis(180), ignored -> reset.countDown());

        gate.connectionStarted(7L);
        gate.enteredPlay(7L);
        gate.authenticationCompleted(7L);
        assertThat(reset.await(100, TimeUnit.MILLISECONDS)).isFalse();

        gate.leftPlay(7L);
        gate.enteredPlay(7L);

        assertThat(reset.await(100, TimeUnit.MILLISECONDS)).isFalse();
        assertThat(reset.await(1, TimeUnit.SECONDS)).isTrue();
    }

    @Test
    void invalidationAndNewGenerationMakeTheOldDelayedTaskHarmless() throws Exception {
        CountDownLatch reset = new CountDownLatch(1);
        AtomicInteger resetGeneration = new AtomicInteger(-1);
        ReconnectStabilityGate gate = gate(Duration.ofMillis(180), generation -> {
            resetGeneration.set(Math.toIntExact(generation));
            reset.countDown();
        });

        gate.connectionStarted(11L);
        gate.enteredPlay(11L);
        gate.authenticationCompleted(11L);
        assertThat(reset.await(100, TimeUnit.MILLISECONDS)).isFalse();

        // Used by disconnect, stop, explicit reconnect and maintenance hold.
        gate.invalidate();
        gate.connectionStarted(12L);
        gate.enteredPlay(12L);
        gate.authenticationCompleted(12L);

        assertThat(reset.await(100, TimeUnit.MILLISECONDS)).isFalse();
        assertThat(reset.await(1, TimeUnit.SECONDS)).isTrue();
        assertThat(resetGeneration).hasValue(12);
    }

    @Test
    void shortPlayCyclesCannotRenewAFiniteReconnectBudget() throws Exception {
        AtomicInteger attempts = new AtomicInteger(3);
        ReconnectStabilityGate gate = gate(Duration.ofMillis(120), ignored -> attempts.set(0));
        ReconnectPolicy policy = new ReconnectPolicy(new ReconnectConfig(
            0L, 100L, 1.0D, 0.0D, 3, 1));

        for (long generation = 1L; generation <= 3L; generation++) {
            gate.connectionStarted(generation);
            gate.enteredPlay(generation);
            gate.authenticationCompleted(generation);
            gate.leftPlay(generation);
            gate.invalidate();
        }

        assertThat(executor.schedule(() -> { }, 180, TimeUnit.MILLISECONDS).get(1, TimeUnit.SECONDS)).isNull();
        assertThat(attempts).hasValue(3);
        assertThat(policy.allows(attempts.get() + 1)).isFalse();
    }

    @Test
    void zeroSecondsResetsImmediatelyButOnlyWhenBothConditionsMatchTheGeneration() {
        AtomicInteger attempts = new AtomicInteger(4);
        ReconnectStabilityGate gate = gate(Duration.ZERO, ignored -> attempts.set(0));

        gate.connectionStarted(20L);
        gate.enteredPlay(20L);
        gate.authenticationCompleted(19L);
        assertThat(attempts).hasValue(4);

        gate.authenticationCompleted(20L);
        assertThat(attempts).hasValue(0);
    }

    @Test
    void zeroSecondStableActionRunsWithoutHoldingTheGateMonitor() throws Exception {
        AtomicReference<ReconnectStabilityGate> reference = new AtomicReference<>();
        AtomicReference<Boolean> competingThreadAcquiredMonitor = new AtomicReference<>(false);
        CountDownLatch actionFinished = new CountDownLatch(1);
        ReconnectStabilityGate gate = gate(Duration.ZERO, ignored -> {
            CountDownLatch acquired = new CountDownLatch(1);
            Thread competing = new Thread(() -> {
                synchronized (reference.get()) {
                    acquired.countDown();
                }
            }, "reconnect-gate-lock-test");
            competing.start();
            try {
                competingThreadAcquiredMonitor.set(acquired.await(1, TimeUnit.SECONDS));
            }
            catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
            }
            finally {
                actionFinished.countDown();
            }
        });
        reference.set(gate);

        gate.connectionStarted(30L);
        gate.enteredPlay(30L);
        gate.authenticationCompleted(30L);

        assertThat(actionFinished.await(2, TimeUnit.SECONDS)).isTrue();
        assertThat(competingThreadAcquiredMonitor).hasValue(true);
    }

    @Test
    void rejectsNegativeStabilityDuration() {
        assertThatThrownBy(() -> gate(Duration.ofSeconds(-1), ignored -> { }))
            .isInstanceOf(IllegalArgumentException.class);
    }

    private ReconnectStabilityGate gate(Duration duration, java.util.function.LongConsumer action) {
        return new ReconnectStabilityGate(executor, duration, action);
    }
}
