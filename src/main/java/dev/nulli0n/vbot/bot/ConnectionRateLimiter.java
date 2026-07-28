package dev.nulli0n.vbot.bot;

import java.util.concurrent.TimeUnit;
import java.util.function.LongSupplier;

/** Reserves globally spaced connection slots for all sessions in one manager. */
public final class ConnectionRateLimiter {
    private final long intervalNanos;
    private final LongSupplier nanoTime;
    private long nextSlotNanos;

    public ConnectionRateLimiter(long intervalMillis) {
        this(intervalMillis, System::nanoTime);
    }

    ConnectionRateLimiter(long intervalMillis, LongSupplier nanoTime) {
        this.intervalNanos = TimeUnit.MILLISECONDS.toNanos(Math.max(0, intervalMillis));
        this.nanoTime = nanoTime;
    }

    public synchronized long reserveDelayMillis(long minimumDelayMillis) {
        long now = nanoTime.getAsLong();
        long earliest = saturatedAdd(now,
            TimeUnit.MILLISECONDS.toNanos(Math.max(0, minimumDelayMillis)));
        long slot = Math.max(earliest, nextSlotNanos);
        nextSlotNanos = saturatedAdd(slot, intervalNanos);
        long delayNanos = Math.max(0, slot - now);
        return (delayNanos + 999_999L) / 1_000_000L;
    }

    private static long saturatedAdd(long left, long right) {
        if (right > 0 && left > Long.MAX_VALUE - right) {
            return Long.MAX_VALUE;
        }
        return left + right;
    }
}
