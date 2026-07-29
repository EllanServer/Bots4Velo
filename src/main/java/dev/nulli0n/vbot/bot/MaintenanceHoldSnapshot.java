package dev.nulli0n.vbot.bot;

import java.time.Instant;
import java.util.Objects;
import java.util.Optional;

/** A read-only maintenance hold which prevents a bot from starting or reconnecting. */
public record MaintenanceHoldSnapshot(
    String botId,
    String reason,
    Instant createdAt,
    Optional<Instant> expiresAt,
    String server
) {
    public MaintenanceHoldSnapshot(String botId, String reason, Instant createdAt,
                                   Optional<Instant> expiresAt) {
        this(botId, reason, createdAt, expiresAt, "");
    }

    public MaintenanceHoldSnapshot {
        botId = Objects.requireNonNull(botId, "botId").trim();
        reason = Objects.requireNonNull(reason, "reason").trim();
        createdAt = Objects.requireNonNull(createdAt, "createdAt");
        if (botId.isEmpty()) {
            throw new IllegalArgumentException("botId must not be blank");
        }
        if (reason.isEmpty()) {
            throw new IllegalArgumentException("reason must not be blank");
        }
        expiresAt = expiresAt == null ? Optional.empty() : expiresAt;
        if (expiresAt.isPresent() && !expiresAt.orElseThrow().isAfter(createdAt)) {
            throw new IllegalArgumentException("expiresAt must be after createdAt");
        }
        server = server == null ? "" : server.trim();
    }
}
