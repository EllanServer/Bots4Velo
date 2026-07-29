package dev.nulli0n.vbot.backend;

import dev.nulli0n.vbot.backend.protocol.BackendCapabilities;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

/** A small identity-bound cache; a new Velocity connection never inherits old capabilities. */
final class BackendCapabilityCache {
    record Capabilities(boolean probeExtended, boolean applyPolicyExtended, boolean recover) {
        static Capabilities parse(String legacyProbeDetail) {
            return new Capabilities(
                BackendCapabilities.supports(legacyProbeDetail, BackendCapabilities.PROBE_EXT),
                BackendCapabilities.supports(legacyProbeDetail, BackendCapabilities.APPLY_POLICY_EXT),
                BackendCapabilities.supports(legacyProbeDetail, BackendCapabilities.RECOVER));
        }
    }

    private final int maximumEntries;
    private final Map<String, Entry> entries = new LinkedHashMap<>(16, 0.75F, true);

    BackendCapabilityCache(int maximumEntries) {
        if (maximumEntries < 1) {
            throw new IllegalArgumentException("maximumEntries must be positive");
        }
        this.maximumEntries = maximumEntries;
    }

    synchronized Optional<Capabilities> get(String botKey, Object connection) {
        Entry entry = entries.get(botKey);
        if (entry == null) {
            return Optional.empty();
        }
        if (entry.connection() != connection) {
            entries.remove(botKey);
            return Optional.empty();
        }
        return Optional.of(entry.capabilities());
    }

    synchronized void put(String botKey, Object connection, Capabilities capabilities) {
        entries.put(botKey, new Entry(connection, capabilities));
        while (entries.size() > maximumEntries) {
            String eldest = entries.keySet().iterator().next();
            entries.remove(eldest);
        }
    }

    synchronized void remove(String botKey) {
        entries.remove(botKey);
    }

    synchronized void clear() {
        entries.clear();
    }

    synchronized int size() {
        return entries.size();
    }

    private record Entry(Object connection, Capabilities capabilities) {
    }
}
