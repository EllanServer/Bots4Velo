package dev.nulli0n.vbot.backend.protocol;

import java.util.Objects;

public final class BackendPolicy {
    private final BackendInvulnerability invulnerability;
    private final BackendGameMode gameMode;
    private final RespawnPoint respawnPoint;
    private final ManagedBoolean sleepingIgnored;
    private final ManagedBoolean affectsSpawning;
    private final ManagedBoolean pickupItems;
    private final ManagedBoolean collidable;

    public BackendPolicy(BackendInvulnerability invulnerability, BackendGameMode gameMode,
                         RespawnPoint respawnPoint) {
        this(invulnerability, gameMode, respawnPoint, ManagedBoolean.UNCHANGED,
            ManagedBoolean.UNCHANGED, ManagedBoolean.UNCHANGED, ManagedBoolean.UNCHANGED);
    }

    public BackendPolicy(BackendInvulnerability invulnerability, BackendGameMode gameMode,
                         RespawnPoint respawnPoint, ManagedBoolean sleepingIgnored,
                         ManagedBoolean affectsSpawning, ManagedBoolean pickupItems,
                         ManagedBoolean collidable) {
        this.invulnerability = Objects.requireNonNull(invulnerability, "invulnerability");
        this.gameMode = Objects.requireNonNull(gameMode, "gameMode");
        this.respawnPoint = Objects.requireNonNull(respawnPoint, "respawnPoint");
        this.sleepingIgnored = Objects.requireNonNull(sleepingIgnored, "sleepingIgnored");
        this.affectsSpawning = Objects.requireNonNull(affectsSpawning, "affectsSpawning");
        this.pickupItems = Objects.requireNonNull(pickupItems, "pickupItems");
        this.collidable = Objects.requireNonNull(collidable, "collidable");
    }

    public static BackendPolicy unchanged() {
        return new BackendPolicy(BackendInvulnerability.UNCHANGED, BackendGameMode.UNCHANGED,
            RespawnPoint.unchanged());
    }

    public BackendInvulnerability invulnerability() {
        return invulnerability;
    }

    public BackendGameMode gameMode() {
        return gameMode;
    }

    public RespawnPoint respawnPoint() {
        return respawnPoint;
    }

    public ManagedBoolean sleepingIgnored() {
        return sleepingIgnored;
    }

    public ManagedBoolean affectsSpawning() {
        return affectsSpawning;
    }

    public ManagedBoolean pickupItems() {
        return pickupItems;
    }

    public ManagedBoolean collidable() {
        return collidable;
    }

    public BackendPolicy withRespawnPoint(RespawnPoint replacement) {
        return new BackendPolicy(invulnerability, gameMode, replacement, sleepingIgnored,
            affectsSpawning, pickupItems, collidable);
    }

    @Override
    public boolean equals(Object other) {
        if (this == other) {
            return true;
        }
        if (!(other instanceof BackendPolicy)) {
            return false;
        }
        BackendPolicy that = (BackendPolicy) other;
        return invulnerability == that.invulnerability && gameMode == that.gameMode
            && respawnPoint.equals(that.respawnPoint) && sleepingIgnored == that.sleepingIgnored
            && affectsSpawning == that.affectsSpawning && pickupItems == that.pickupItems
            && collidable == that.collidable;
    }

    @Override
    public int hashCode() {
        return Objects.hash(invulnerability, gameMode, respawnPoint, sleepingIgnored,
            affectsSpawning, pickupItems, collidable);
    }
}
