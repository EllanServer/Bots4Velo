package dev.nulli0n.vbot.backend;

import dev.nulli0n.vbot.backend.protocol.ActualState;
import dev.nulli0n.vbot.backend.protocol.BackendStatus;

import java.util.Objects;

/** A normalized acknowledgement from the Paper companion. */
public record BackendControlResult(
    String botId,
    BackendStatus status,
    String detail,
    ActualState actualState
) {
    public BackendControlResult {
        botId = Objects.requireNonNull(botId, "botId");
        status = Objects.requireNonNull(status, "status");
        detail = detail == null ? "" : detail.trim();
        actualState = actualState == null ? ActualState.absent() : actualState;
    }

    public static BackendControlResult failure(String botId, BackendStatus status, String detail) {
        return new BackendControlResult(botId, status, detail, ActualState.absent());
    }

    public boolean successful() {
        return status == BackendStatus.OK;
    }
}
