package dev.nulli0n.vbot.backend.protocol;

public final class ProtocolException extends Exception {
    private final BackendStatus status;

    public ProtocolException(BackendStatus status, String message) {
        super(message);
        this.status = status;
    }

    public ProtocolException(BackendStatus status, String message, Throwable cause) {
        super(message, cause);
        this.status = status;
    }

    public BackendStatus status() {
        return status;
    }
}
