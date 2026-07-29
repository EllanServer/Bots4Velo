package dev.nulli0n.vbot.backend.protocol;

/**
 * A persistent boolean policy where {@link #UNCHANGED} leaves the backend's
 * current value untouched.
 */
public enum ManagedBoolean {
    UNCHANGED(0),
    ENABLED(1),
    DISABLED(2);

    private final int id;

    ManagedBoolean(int id) {
        this.id = id;
    }

    public int id() {
        return id;
    }

    static ManagedBoolean fromId(int id) throws ProtocolException {
        for (ManagedBoolean value : values()) {
            if (value.id == id) {
                return value;
            }
        }
        throw new ProtocolException(BackendStatus.BAD_REQUEST, "Unknown managed boolean " + id);
    }
}
