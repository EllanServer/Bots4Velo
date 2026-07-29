package dev.nulli0n.vbot.bot;

import org.junit.jupiter.api.Test;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

class AuthenticationSettleGateTest {
    @Test
    void holdsSuccessfulContinuationUntilTheConfiguredDelay() {
        ScheduledExecutorService executor = Executors.newSingleThreadScheduledExecutor();
        try {
            AuthenticationSettleGate gate = new AuthenticationSettleGate(executor);
            AtomicInteger completions = new AtomicInteger();

            gate.schedule(10_000, completions::incrementAndGet);

            assertThat(gate.pending()).isTrue();
            assertThat(completions).hasValue(0);
            gate.cancel();
            assertThat(gate.pending()).isFalse();
        }
        finally {
            executor.shutdownNow();
        }
    }

    @Test
    void cancellationPreventsAnOldSessionContinuation() {
        ScheduledExecutorService executor = Executors.newSingleThreadScheduledExecutor();
        try {
            AuthenticationSettleGate gate = new AuthenticationSettleGate(executor);
            AtomicInteger completions = new AtomicInteger();

            gate.schedule(10_000, () -> completions.addAndGet(100));
            gate.cancel();
            gate.schedule(0, completions::incrementAndGet);

            assertThat(gate.pending()).isFalse();
            assertThat(completions).hasValue(1);
        }
        finally {
            executor.shutdownNow();
        }
    }

    @Test
    void cancellationPreventsADequeuedContinuationFromSchedulingAnotherAttempt() throws Exception {
        ScheduledExecutorService executor = Executors.newSingleThreadScheduledExecutor();
        try {
            AuthenticationSettleGate gate = new AuthenticationSettleGate(executor);
            CountDownLatch started = new CountDownLatch(1);
            CountDownLatch release = new CountDownLatch(1);
            CountDownLatch finished = new CountDownLatch(1);
            AtomicInteger attempts = new AtomicInteger();

            gate.scheduleUntilComplete(1, 10_000, () -> {
                attempts.incrementAndGet();
                started.countDown();
                try {
                    if (!release.await(5, TimeUnit.SECONDS)) {
                        throw new AssertionError("test did not release the dequeued continuation");
                    }
                    return false;
                }
                catch (InterruptedException exception) {
                    Thread.currentThread().interrupt();
                    return true;
                }
                finally {
                    finished.countDown();
                }
            });

            assertThat(started.await(5, TimeUnit.SECONDS)).isTrue();
            gate.cancel();
            release.countDown();
            assertThat(finished.await(5, TimeUnit.SECONDS)).isTrue();
            assertThat(gate.pending()).isFalse();
            assertThat(attempts).hasValue(1);
        }
        finally {
            executor.shutdownNow();
        }
    }

}
