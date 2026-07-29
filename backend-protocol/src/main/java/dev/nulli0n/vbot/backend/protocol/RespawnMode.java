package dev.nulli0n.vbot.backend.protocol;

public enum RespawnMode {
    UNCHANGED(0),
    CURRENT(1),
    FIXED(2),
    WORLD_SPAWN(3),
    CLEAR(4);

    private final int id;

    RespawnMode(int id) {
        this.id = id;
    }

    public int id() {
        return id;
    }

    static RespawnMode fromId(int id) throws ProtocolException {
        for (RespawnMode value : values()) {
            if (value.id == id) {
                return value;
            }
        }
        throw new ProtocolException(BackendStatus.BAD_REQUEST, "Unknown respawn mode " + id);
    }
}
