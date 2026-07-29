package dev.nulli0n.vbot.bot;

import java.util.Objects;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;

/** Runs an authentication continuation after a cancellable client-settle delay. */
final class AuthenticationSettleGate {
    private final ScheduledExecutorService executor;
    private ScheduledFuture<?> pending;
    private long sequence;

    AuthenticationSettleGate(ScheduledExecutorService executor) {
        this.executor = Objects.requireNonNull(executor, "executor");
    }

    void schedule(long delayMillis, Runnable continuation) {
        Objects.requireNonNull(continuation, "continuation");
        scheduleUntilComplete(delayMillis, 1, () -> {
            continuation.run();
            return true;
        });
    }

    void scheduleUntilComplete(long delayMillis, long retryDelayMillis, BooleanSupplier continuation) {
        Objects.requireNonNull(continuation, "continuation");
        long scheduledSequence;
        synchronized (this) {
            cancelPending();
            scheduledSequence = ++sequence;
            if (delayMillis > 0) {
                pending = executor.schedule(
                    () -> run(scheduledSequence, retryDelayMillis, continuation),
                    delayMillis,
                    TimeUnit.MILLISECONDS
                );
                return;
            }
        }
        run(scheduledSequence, retryDelayMillis, continuation);
    }

    synchronized void cancel() {
        sequence++;
        cancelPending();
    }

    private void cancelPending() {
        if (pending != null) {
            pending.cancel(false);
            pending = null;
        }
    }

    synchronized boolean pending() {
        return pending != null && !pending.isDone();
    }

    private void run(long scheduledSequence, long retryDelayMillis, BooleanSupplier continuation) {
        synchronized (this) {
            if (sequence != scheduledSequence) {
                return;
            }
            pending = null;
        }
        if (continuation.getAsBoolean()) {
            return;
        }
        synchronized (this) {
            // Cancellation may race with a continuation that has already been
            // dequeued. Never let that old continuation revive itself.
            if (sequence != scheduledSequence) {
                return;
            }
            long delay = Math.max(1, retryDelayMillis);
            pending = executor.schedule(
                () -> run(scheduledSequence, retryDelayMillis, continuation),
                delay,
                TimeUnit.MILLISECONDS
            );
        }
    }
}
