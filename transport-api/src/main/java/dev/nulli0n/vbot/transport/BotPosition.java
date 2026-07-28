package dev.nulli0n.vbot.transport;

public record BotPosition(
    boolean known,
    double x,
    double y,
    double z,
    float yaw,
    float pitch
) {
    public static BotPosition unknown() {
        return new BotPosition(false, 0, 0, 0, 0, 0);
    }

    public static BotPosition known(double x, double y, double z, float yaw, float pitch) {
        return new BotPosition(true, x, y, z, yaw, pitch);
    }
}
