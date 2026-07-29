package dev.nulli0n.vbot.bot;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/** Thread-safe, in-memory maintenance holds keyed by normalized bot id. */
final class MaintenanceHoldRegistry {
    private static final String DEFAULT_REASON = "maintenance";

    private final Clock clock;
    private final Map<String, MaintenanceHoldSnapshot> holds = new ConcurrentHashMap<>();

    MaintenanceHoldRegistry(Clock clock) {
        this.clock = clock;
    }

    MaintenanceHoldSnapshot hold(String botId, String reason) {
        return put(botId, reason, Optional.empty(), "");
    }

    MaintenanceHoldSnapshot hold(String botId, String reason, Duration ttl) {
        if (ttl == null || ttl.isZero() || ttl.isNegative()) {
            throw new IllegalArgumentException("Maintenance hold TTL must be positive");
        }
        Instant now = clock.instant();
        return put(botId, reason, Optional.of(now.plus(ttl)), "", now);
    }

    MaintenanceHoldSnapshot hold(String botId, String reason, String server) {
        return put(botId, reason, Optional.empty(), server);
    }

    MaintenanceHoldSnapshot hold(String botId, String reason, Duration ttl, String server) {
        if (ttl == null || ttl.isZero() || ttl.isNegative()) {
            throw new IllegalArgumentException("Maintenance hold TTL must be positive");
        }
        Instant now = clock.instant();
        return put(botId, reason, Optional.of(now.plus(ttl)), server, now);
    }

    Optional<MaintenanceHoldSnapshot> snapshot(String botId) {
        String key = normalize(botId);
        MaintenanceHoldSnapshot current = holds.get(key);
        if (current == null) {
            return Optional.empty();
        }
        Instant now = clock.instant();
        if (expired(current, now)) {
            holds.remove(key, current);
            return Optional.empty();
        }
        return Optional.of(current);
    }

    List<MaintenanceHoldSnapshot> snapshots() {
        Instant now = clock.instant();
        holds.forEach((key, current) -> {
            if (expired(current, now)) {
                holds.remove(key, current);
            }
        });
        return holds.values().stream()
            .sorted(Comparator.comparing(MaintenanceHoldSnapshot::botId, String.CASE_INSENSITIVE_ORDER))
            .toList();
    }

    boolean isHeld(String botId) {
        return snapshot(botId).isPresent();
    }

    boolean resume(String botId) {
        String key = normalize(botId);
        MaintenanceHoldSnapshot current = holds.get(key);
        if (current == null) {
            return false;
        }
        if (expired(current, clock.instant())) {
            holds.remove(key, current);
            return false;
        }
        return holds.remove(key, current);
    }

    boolean restore(MaintenanceHoldSnapshot snapshot) {
        if (snapshot == null) {
            throw new IllegalArgumentException("Maintenance hold snapshot must not be null");
        }
        if (expired(snapshot, clock.instant())) {
            return false;
        }
        holds.put(normalize(snapshot.botId()), snapshot);
        return true;
    }

    void remove(String botId) {
        holds.remove(normalize(botId));
    }

    void clear() {
        holds.clear();
    }

    private MaintenanceHoldSnapshot put(String botId, String reason, Optional<Instant> expiresAt,
                                        String server) {
        return put(botId, reason, expiresAt, server, clock.instant());
    }

    private MaintenanceHoldSnapshot put(String botId, String reason, Optional<Instant> expiresAt,
                                        String server, Instant now) {
        String normalizedReason = reason == null || reason.isBlank() ? DEFAULT_REASON : reason.trim();
        MaintenanceHoldSnapshot replacement = new MaintenanceHoldSnapshot(
            botId.trim(), normalizedReason, now, expiresAt, server);
        holds.put(normalize(botId), replacement);
        return replacement;
    }

    private static boolean expired(MaintenanceHoldSnapshot hold, Instant now) {
        return hold.expiresAt().map(expiry -> !now.isBefore(expiry)).orElse(false);
    }

    private static String normalize(String botId) {
        if (botId == null || botId.isBlank()) {
            throw new IllegalArgumentException("Bot id must not be blank");
        }
        return botId.trim().toLowerCase(Locale.ROOT);
    }
}
