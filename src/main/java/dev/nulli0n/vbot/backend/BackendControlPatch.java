package dev.nulli0n.vbot.backend;

import dev.nulli0n.vbot.backend.protocol.BackendGameMode;
import dev.nulli0n.vbot.backend.protocol.ManagedBoolean;
import dev.nulli0n.vbot.backend.protocol.RespawnPoint;

import java.util.Objects;

/**
 * A partial player-state update. Presence flags distinguish an omitted field
 * from an explicit KEEP/UNCHANGED value, which stops managing that field.
 */
public record BackendControlPatch(
    boolean invulnerabilityPresent,
    InvulnerabilityChange invulnerability,
    boolean gameModePresent,
    BackendGameMode gameMode,
    boolean respawnPointPresent,
    RespawnPoint respawnPoint,
    boolean sleepingIgnoredPresent,
    ManagedBoolean sleepingIgnored,
    boolean affectsSpawningPresent,
    ManagedBoolean affectsSpawning,
    boolean pickupItemsPresent,
    ManagedBoolean pickupItems,
    boolean collidablePresent,
    ManagedBoolean collidable
) {
    public BackendControlPatch {
        Objects.requireNonNull(invulnerability, "invulnerability");
        Objects.requireNonNull(gameMode, "gameMode");
        Objects.requireNonNull(respawnPoint, "respawnPoint");
        Objects.requireNonNull(sleepingIgnored, "sleepingIgnored");
        Objects.requireNonNull(affectsSpawning, "affectsSpawning");
        Objects.requireNonNull(pickupItems, "pickupItems");
        Objects.requireNonNull(collidable, "collidable");
    }

    /** Source-compatible constructor for the v2.5 three-field patch model. */
    public BackendControlPatch(
        boolean invulnerabilityPresent,
        InvulnerabilityChange invulnerability,
        boolean gameModePresent,
        BackendGameMode gameMode,
        boolean respawnPointPresent,
        RespawnPoint respawnPoint
    ) {
        this(invulnerabilityPresent, invulnerability, gameModePresent, gameMode,
            respawnPointPresent, respawnPoint,
            false, ManagedBoolean.UNCHANGED, false, ManagedBoolean.UNCHANGED,
            false, ManagedBoolean.UNCHANGED, false, ManagedBoolean.UNCHANGED);
    }

    public static BackendControlPatch invulnerability(InvulnerabilityChange change) {
        return new BackendControlPatch(true, change, false, BackendGameMode.UNCHANGED,
            false, RespawnPoint.unchanged());
    }

    public static BackendControlPatch gameMode(BackendGameMode gameMode) {
        return new BackendControlPatch(false, InvulnerabilityChange.KEEP, true, gameMode,
            false, RespawnPoint.unchanged());
    }

    public static BackendControlPatch respawnPoint(RespawnPoint respawnPoint) {
        return new BackendControlPatch(false, InvulnerabilityChange.KEEP, false, BackendGameMode.UNCHANGED,
            true, respawnPoint);
    }

    public static BackendControlPatch sleepingIgnored(ManagedBoolean value) {
        return extendedSingle(true, value, false, ManagedBoolean.UNCHANGED,
            false, ManagedBoolean.UNCHANGED, false, ManagedBoolean.UNCHANGED);
    }

    public static BackendControlPatch affectsSpawning(ManagedBoolean value) {
        return extendedSingle(false, ManagedBoolean.UNCHANGED, true, value,
            false, ManagedBoolean.UNCHANGED, false, ManagedBoolean.UNCHANGED);
    }

    public static BackendControlPatch pickupItems(ManagedBoolean value) {
        return extendedSingle(false, ManagedBoolean.UNCHANGED, false, ManagedBoolean.UNCHANGED,
            true, value, false, ManagedBoolean.UNCHANGED);
    }

    public static BackendControlPatch collidable(ManagedBoolean value) {
        return extendedSingle(false, ManagedBoolean.UNCHANGED, false, ManagedBoolean.UNCHANGED,
            false, ManagedBoolean.UNCHANGED, true, value);
    }

    /** Creates one complete AFK preset patch without exposing the record's presence flags. */
    public static BackendControlPatch afkPreset(
        InvulnerabilityChange invulnerability,
        ManagedBoolean sleepingIgnored,
        ManagedBoolean affectsSpawning,
        ManagedBoolean pickupItems,
        ManagedBoolean collidable
    ) {
        return new BackendControlPatch(true, invulnerability, false, BackendGameMode.UNCHANGED,
            false, RespawnPoint.unchanged(),
            true, sleepingIgnored, true, affectsSpawning, true, pickupItems, true, collidable);
    }

    public boolean extendedFieldsPresent() {
        return sleepingIgnoredPresent || affectsSpawningPresent || pickupItemsPresent || collidablePresent;
    }

    private static BackendControlPatch extendedSingle(
        boolean sleepingIgnoredPresent,
        ManagedBoolean sleepingIgnored,
        boolean affectsSpawningPresent,
        ManagedBoolean affectsSpawning,
        boolean pickupItemsPresent,
        ManagedBoolean pickupItems,
        boolean collidablePresent,
        ManagedBoolean collidable
    ) {
        return new BackendControlPatch(false, InvulnerabilityChange.KEEP,
            false, BackendGameMode.UNCHANGED, false, RespawnPoint.unchanged(),
            sleepingIgnoredPresent, sleepingIgnored, affectsSpawningPresent, affectsSpawning,
            pickupItemsPresent, pickupItems, collidablePresent, collidable);
    }
}
