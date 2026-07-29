package dev.nulli0n.vbot.backend.protocol;

public enum BackendOperation {
    PROBE(1),
    APPLY_POLICY(2),
    RESPAWN(3),
    PROBE_EXT(4),
    APPLY_POLICY_EXT(5),
    RECOVER(6);

    private final int id;

    BackendOperation(int id) {
        this.id = id;
    }

    public int id() {
        return id;
    }

    static BackendOperation fromId(int id) throws ProtocolException {
        for (BackendOperation value : values()) {
            if (value.id == id) {
                return value;
            }
        }
        throw new ProtocolException(BackendStatus.BAD_REQUEST, "Unknown operation " + id);
    }
}
