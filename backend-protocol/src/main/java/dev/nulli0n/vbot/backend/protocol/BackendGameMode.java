package dev.nulli0n.vbot.backend.protocol;

public enum BackendGameMode {
    UNCHANGED(0),
    SURVIVAL(1),
    CREATIVE(2),
    ADVENTURE(3),
    SPECTATOR(4);

    private final int id;

    BackendGameMode(int id) {
        this.id = id;
    }

    public int id() {
        return id;
    }

    static BackendGameMode fromId(int id) throws ProtocolException {
        for (BackendGameMode value : values()) {
            if (value.id == id) {
                return value;
            }
        }
        throw new ProtocolException(BackendStatus.BAD_REQUEST, "Unknown game mode " + id);
    }
}
