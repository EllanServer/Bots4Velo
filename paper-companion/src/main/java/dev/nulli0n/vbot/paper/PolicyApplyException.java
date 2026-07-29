package dev.nulli0n.vbot.paper;

import dev.nulli0n.vbot.backend.protocol.BackendStatus;

final class PolicyApplyException extends Exception {
    private final BackendStatus status;

    PolicyApplyException(BackendStatus status, String message) {
        super(message);
        this.status = status;
    }

    PolicyApplyException(BackendStatus status, String message, Throwable cause) {
        super(message, cause);
        this.status = status;
    }

    BackendStatus status() {
        return status;
    }
}
