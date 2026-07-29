package dev.nulli0n.vbot.bot;

import java.time.Instant;
import java.util.Objects;

/** Read-only ETA and reason for a connection attempt queued inside one session. */
public record ConnectionAttemptSnapshot(Instant scheduledAt, ActivationKind kind) {
    public ConnectionAttemptSnapshot {
        scheduledAt = Objects.requireNonNull(scheduledAt, "scheduledAt");
        kind = Objects.requireNonNull(kind, "kind");
    }
}
