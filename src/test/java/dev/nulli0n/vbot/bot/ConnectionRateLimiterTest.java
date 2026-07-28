package dev.nulli0n.vbot.bot;

import org.junit.jupiter.api.Test;

import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;

class ConnectionRateLimiterTest {
    @Test
    void spacesEveryReservedConnectionSlot() {
        AtomicLong clock = new AtomicLong();
        ConnectionRateLimiter limiter = new ConnectionRateLimiter(1_500, clock::get);

        assertThat(limiter.reserveDelayMillis(0)).isZero();
        assertThat(limiter.reserveDelayMillis(0)).isEqualTo(1_500);

        clock.set(TimeUnit.MILLISECONDS.toNanos(500));
        assertThat(limiter.reserveDelayMillis(0)).isEqualTo(2_500);
    }

    @Test
    void honorsPolicyDelayWhenItIsLaterThanNextGlobalSlot() {
        AtomicLong clock = new AtomicLong();
        ConnectionRateLimiter limiter = new ConnectionRateLimiter(1_000, clock::get);

        assertThat(limiter.reserveDelayMillis(0)).isZero();
        assertThat(limiter.reserveDelayMillis(5_000)).isEqualTo(5_000);
    }
}
