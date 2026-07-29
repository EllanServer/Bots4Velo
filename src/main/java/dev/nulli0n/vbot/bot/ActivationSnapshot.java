package dev.nulli0n.vbot.bot;

import java.time.Instant;

/** A read-only view of a bot activation waiting in the manager queue. */
public record ActivationSnapshot(
    String botId,
    Instant scheduledAt,
    ActivationKind kind
) {
    public ActivationSnapshot(String botId, Instant scheduledAt) {
        this(botId, scheduledAt, ActivationKind.START);
    }
}
