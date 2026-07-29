package dev.nulli0n.vbot.backend.protocol;

import java.util.Objects;

public final class RespawnPoint {
    private final RespawnMode mode;
    private final String world;
    private final double x;
    private final double y;
    private final double z;
    private final float yaw;
    private final float pitch;

    public RespawnPoint(RespawnMode mode, String world, double x, double y, double z, float yaw, float pitch) {
        this.mode = Objects.requireNonNull(mode, "mode");
        this.world = world == null ? "" : world;
        this.x = x;
        this.y = y;
        this.z = z;
        this.yaw = yaw;
        this.pitch = pitch;
    }

    public static RespawnPoint unchanged() {
        return new RespawnPoint(RespawnMode.UNCHANGED, "", 0, 0, 0, 0, 0);
    }

    public static RespawnPoint current() {
        return new RespawnPoint(RespawnMode.CURRENT, "", 0, 0, 0, 0, 0);
    }

    public static RespawnPoint fixed(String world, double x, double y, double z, float yaw, float pitch) {
        return new RespawnPoint(RespawnMode.FIXED, world, x, y, z, yaw, pitch);
    }

    public static RespawnPoint worldSpawn(String world) {
        return new RespawnPoint(RespawnMode.WORLD_SPAWN, world, 0, 0, 0, 0, 0);
    }

    public static RespawnPoint clear() {
        return new RespawnPoint(RespawnMode.CLEAR, "", 0, 0, 0, 0, 0);
    }

    public RespawnMode mode() {
        return mode;
    }

    public String world() {
        return world;
    }

    public double x() {
        return x;
    }

    public double y() {
        return y;
    }

    public double z() {
        return z;
    }

    public float yaw() {
        return yaw;
    }

    public float pitch() {
        return pitch;
    }

    @Override
    public boolean equals(Object other) {
        if (this == other) {
            return true;
        }
        if (!(other instanceof RespawnPoint)) {
            return false;
        }
        RespawnPoint that = (RespawnPoint) other;
        return mode == that.mode && world.equals(that.world)
            && Double.compare(x, that.x) == 0 && Double.compare(y, that.y) == 0
            && Double.compare(z, that.z) == 0 && Float.compare(yaw, that.yaw) == 0
            && Float.compare(pitch, that.pitch) == 0;
    }

    @Override
    public int hashCode() {
        return Objects.hash(mode, world, x, y, z, yaw, pitch);
    }
}
