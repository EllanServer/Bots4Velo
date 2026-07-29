package dev.nulli0n.vbot.backend;

import dev.nulli0n.vbot.backend.protocol.BackendGameMode;
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
    RespawnPoint respawnPoint
) {
    public BackendControlPatch {
        Objects.requireNonNull(invulnerability, "invulnerability");
        Objects.requireNonNull(gameMode, "gameMode");
        Objects.requireNonNull(respawnPoint, "respawnPoint");
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
}
