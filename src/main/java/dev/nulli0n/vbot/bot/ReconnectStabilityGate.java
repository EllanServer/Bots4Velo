package dev.nulli0n.vbot.bot;

import java.time.Duration;
import java.util.Objects;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.function.LongConsumer;

/**
 * Resets a consecutive reconnect budget only after one connection generation
 * has remained both playable and authenticated for a continuous interval.
 */
final class ReconnectStabilityGate {
    private final ScheduledExecutorService executor;
    private final long stableNanos;
    private final LongConsumer stableAction;

    private ScheduledFuture<?> task;
    private long generation = Long.MIN_VALUE;
    private long epoch;
    private boolean playable;
    private boolean authenticated;

    ReconnectStabilityGate(ScheduledExecutorService executor, Duration stableDuration,
                           LongConsumer stableAction) {
        this.executor = Objects.requireNonNull(executor, "executor");
        Objects.requireNonNull(stableDuration, "stableDuration");
        if (stableDuration.isNegative()) {
            throw new IllegalArgumentException("stableDuration must not be negative");
        }
        this.stableNanos = stableDuration.toNanos();
        this.stableAction = Objects.requireNonNull(stableAction, "stableAction");
    }

    synchronized void connectionStarted(long currentGeneration) {
        cancelLocked();
        generation = currentGeneration;
        playable = false;
        authenticated = false;
    }

    void enteredPlay(long currentGeneration) {
        Long immediatelyStable;
        synchronized (this) {
            if (generation != currentGeneration) {
                return;
            }
            playable = true;
            immediatelyStable = armIfEligibleLocked();
        }
        runStableAction(immediatelyStable);
    }

    synchronized void leftPlay(long currentGeneration) {
        if (generation != currentGeneration) {
            return;
        }
        playable = false;
        cancelLocked();
    }

    void authenticationCompleted(long currentGeneration) {
        Long immediatelyStable;
        synchronized (this) {
            if (generation != currentGeneration) {
                return;
            }
            authenticated = true;
            immediatelyStable = armIfEligibleLocked();
        }
        runStableAction(immediatelyStable);
    }

    synchronized void invalidate() {
        playable = false;
        authenticated = false;
        cancelLocked();
    }

    private Long armIfEligibleLocked() {
        if (!playable || !authenticated || (task != null && !task.isDone())) {
            return null;
        }
        long expectedGeneration = generation;
        long expectedEpoch = ++epoch;
        if (stableNanos == 0L) {
            return expectedGeneration;
        }
        task = executor.schedule(() -> complete(expectedGeneration, expectedEpoch),
            stableNanos, TimeUnit.NANOSECONDS);
        return null;
    }

    private void complete(long expectedGeneration, long expectedEpoch) {
        boolean stable;
        synchronized (this) {
            stable = generation == expectedGeneration && epoch == expectedEpoch && playable && authenticated;
            if (stable) {
                task = null;
            }
        }
        if (stable) {
            stableAction.accept(expectedGeneration);
        }
    }

    private void runStableAction(Long currentGeneration) {
        if (currentGeneration != null) {
            stableAction.accept(currentGeneration);
        }
    }

    private void cancelLocked() {
        epoch++;
        if (task != null) {
            task.cancel(false);
            task = null;
        }
    }
}
