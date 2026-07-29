package dev.nulli0n.vbot.backend.protocol;

import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;

class ReplayWindowTest {
    @Test
    void rejectsExpiredAndRepeatedNonces() {
        long now = 1_000_000L;
        ReplayWindow window = new ReplayWindow(5_000L, 30_000L, 10);
        ControlRequest accepted = request(now, (byte) 1);

        assertEquals(BackendStatus.OK, window.validate(accepted, now));
        assertEquals(BackendStatus.REPLAYED, window.validate(accepted, now + 1));
        assertEquals(BackendStatus.EXPIRED, window.validate(request(now - 5_001L, (byte) 2), now));
        assertEquals(BackendStatus.EXPIRED, window.validate(request(now + 5_001L, (byte) 3), now));
    }

    @Test
    void boundedCacheEvictsOldestEntry() {
        long now = 1_000_000L;
        ReplayWindow window = new ReplayWindow(5_000L, 30_000L, 1);
        ControlRequest first = request(now, (byte) 1);

        assertEquals(BackendStatus.OK, window.validate(first, now));
        assertEquals(BackendStatus.OK, window.validate(request(now, (byte) 2), now));
        assertEquals(BackendStatus.OK, window.validate(first, now));
    }

    @Test
    void timestampRangeChecksDoNotOverflowAtLongLimits() {
        ReplayWindow window = new ReplayWindow(5_000L, 30_000L, 10);

        assertEquals(BackendStatus.EXPIRED,
            window.validate(request(Long.MIN_VALUE, (byte) 1), Long.MAX_VALUE));
        assertEquals(BackendStatus.EXPIRED,
            window.validate(request(Long.MAX_VALUE, (byte) 2), Long.MIN_VALUE));

        ControlRequest nearMaximum = request(Long.MAX_VALUE, (byte) 3);
        assertEquals(BackendStatus.OK, window.validate(nearMaximum, Long.MAX_VALUE));
        assertEquals(BackendStatus.REPLAYED, window.validate(nearMaximum, Long.MAX_VALUE));
    }

    private static ControlRequest request(long timestamp, byte marker) {
        byte[] nonce = new byte[ControlRequest.NONCE_BYTES];
        nonce[0] = marker;
        return new ControlRequest(UUID.randomUUID(), UUID.fromString("33333333-3333-3333-3333-333333333333"),
            timestamp, nonce, BackendOperation.PROBE, null);
    }
}
