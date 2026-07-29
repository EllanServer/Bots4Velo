package dev.nulli0n.vbot.backend.protocol;

import java.util.Objects;

public final class ActualState {
    private final boolean present;
    private final boolean invulnerable;
    private final BackendGameMode gameMode;
    private final RespawnPoint respawnPoint;
    private final boolean extendedPresent;
    private final boolean sleepingIgnored;
    private final boolean affectsSpawning;
    private final boolean pickupItems;
    private final boolean collidable;

    private ActualState(boolean present, boolean invulnerable, BackendGameMode gameMode,
                        RespawnPoint respawnPoint, boolean extendedPresent, boolean sleepingIgnored,
                        boolean affectsSpawning, boolean pickupItems, boolean collidable) {
        this.present = present;
        this.invulnerable = invulnerable;
        this.gameMode = Objects.requireNonNull(gameMode, "gameMode");
        this.respawnPoint = Objects.requireNonNull(respawnPoint, "respawnPoint");
        if (extendedPresent && !present) {
            throw new IllegalArgumentException("Extended state requires a present base state");
        }
        this.extendedPresent = extendedPresent;
        this.sleepingIgnored = sleepingIgnored;
        this.affectsSpawning = affectsSpawning;
        this.pickupItems = pickupItems;
        this.collidable = collidable;
    }

    public static ActualState absent() {
        return new ActualState(false, false, BackendGameMode.UNCHANGED, RespawnPoint.unchanged(),
            false, false, false, false, false);
    }

    public static ActualState present(boolean invulnerable, BackendGameMode gameMode,
                                      RespawnPoint respawnPoint) {
        return new ActualState(true, invulnerable, gameMode, respawnPoint,
            false, false, false, false, false);
    }

    public static ActualState presentExtended(boolean invulnerable, BackendGameMode gameMode,
                                              RespawnPoint respawnPoint, boolean sleepingIgnored,
                                              boolean affectsSpawning, boolean pickupItems,
                                              boolean collidable) {
        return new ActualState(true, invulnerable, gameMode, respawnPoint, true,
            sleepingIgnored, affectsSpawning, pickupItems, collidable);
    }

    public boolean present() {
        return present;
    }

    public boolean invulnerable() {
        return invulnerable;
    }

    public BackendGameMode gameMode() {
        return gameMode;
    }

    public RespawnPoint respawnPoint() {
        return respawnPoint;
    }

    public boolean extendedPresent() {
        return extendedPresent;
    }

    public boolean sleepingIgnored() {
        return sleepingIgnored;
    }

    public boolean affectsSpawning() {
        return affectsSpawning;
    }

    public boolean pickupItems() {
        return pickupItems;
    }

    public boolean collidable() {
        return collidable;
    }

    @Override
    public boolean equals(Object other) {
        if (this == other) {
            return true;
        }
        if (!(other instanceof ActualState)) {
            return false;
        }
        ActualState that = (ActualState) other;
        return present == that.present && invulnerable == that.invulnerable
            && gameMode == that.gameMode && respawnPoint.equals(that.respawnPoint)
            && extendedPresent == that.extendedPresent && sleepingIgnored == that.sleepingIgnored
            && affectsSpawning == that.affectsSpawning && pickupItems == that.pickupItems
            && collidable == that.collidable;
    }

    @Override
    public int hashCode() {
        return Objects.hash(present, invulnerable, gameMode, respawnPoint, extendedPresent,
            sleepingIgnored, affectsSpawning, pickupItems, collidable);
    }
}
