package dev.nulli0n.vbot.backend.protocol;

public enum BackendStatus {
    OK(0),
    BAD_REQUEST(1),
    UNAUTHORIZED(2),
    REPLAYED(3),
    EXPIRED(4),
    VERSION_MISMATCH(5),
    BOT_NOT_ON_SERVER(6),
    WORLD_NOT_FOUND(7),
    INVALID_LOCATION(8),
    APPLY_FAILED(9),
    UNSUPPORTED(10),
    PLUGIN_MISSING(11),
    TIMEOUT(12);

    private final int id;

    BackendStatus(int id) {
        this.id = id;
    }

    public int id() {
        return id;
    }

    static BackendStatus fromId(int id) throws ProtocolException {
        for (BackendStatus value : values()) {
            if (value.id == id) {
                return value;
            }
        }
        throw new ProtocolException(BAD_REQUEST, "Unknown status " + id);
    }
}
