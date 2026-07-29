package dev.nulli0n.vbot.backend.protocol;

import java.util.Base64;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Map;

public final class ReplayWindow {
    private final long maximumClockSkewMillis;
    private final long retentionMillis;
    private final int maximumEntries;
    private final Map<String, Long> seen = new LinkedHashMap<String, Long>();

    public ReplayWindow(long maximumClockSkewMillis, long retentionMillis, int maximumEntries) {
        if (maximumClockSkewMillis < 1 || retentionMillis < maximumClockSkewMillis || maximumEntries < 1) {
            throw new IllegalArgumentException("Invalid replay-window settings");
        }
        this.maximumClockSkewMillis = maximumClockSkewMillis;
        this.retentionMillis = retentionMillis;
        this.maximumEntries = maximumEntries;
    }

    public synchronized BackendStatus validate(ControlRequest request, long nowMillis) {
        long minimumTimestamp = saturatingSubtract(nowMillis, maximumClockSkewMillis);
        long maximumTimestamp = saturatingAdd(nowMillis, maximumClockSkewMillis);
        if (request.timestampMillis() < minimumTimestamp || request.timestampMillis() > maximumTimestamp) {
            return BackendStatus.EXPIRED;
        }

        prune(nowMillis);
        String key = request.targetUuid().toString() + ':'
            + Base64.getEncoder().encodeToString(request.nonce());
        if (seen.containsKey(key)) {
            return BackendStatus.REPLAYED;
        }
        while (seen.size() >= maximumEntries) {
            Iterator<String> iterator = seen.keySet().iterator();
            if (!iterator.hasNext()) {
                break;
            }
            iterator.next();
            iterator.remove();
        }
        seen.put(key, saturatingAdd(nowMillis, retentionMillis));
        return BackendStatus.OK;
    }

    private void prune(long nowMillis) {
        Iterator<Map.Entry<String, Long>> iterator = seen.entrySet().iterator();
        while (iterator.hasNext()) {
            long expiresAt = iterator.next().getValue();
            if (expiresAt != Long.MAX_VALUE && expiresAt <= nowMillis) {
                iterator.remove();
            }
        }
    }

    private static long saturatingAdd(long value, long increment) {
        return value > Long.MAX_VALUE - increment ? Long.MAX_VALUE : value + increment;
    }

    private static long saturatingSubtract(long value, long decrement) {
        return value < Long.MIN_VALUE + decrement ? Long.MIN_VALUE : value - decrement;
    }
}
