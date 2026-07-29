package dev.nulli0n.vbot.backend.protocol;

import java.util.Objects;

public final class BackendPolicy {
    private final BackendInvulnerability invulnerability;
    private final BackendGameMode gameMode;
    private final RespawnPoint respawnPoint;

    public BackendPolicy(BackendInvulnerability invulnerability, BackendGameMode gameMode,
                         RespawnPoint respawnPoint) {
        this.invulnerability = Objects.requireNonNull(invulnerability, "invulnerability");
        this.gameMode = Objects.requireNonNull(gameMode, "gameMode");
        this.respawnPoint = Objects.requireNonNull(respawnPoint, "respawnPoint");
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

    public BackendPolicy withRespawnPoint(RespawnPoint replacement) {
        return new BackendPolicy(invulnerability, gameMode, replacement);
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
            && respawnPoint.equals(that.respawnPoint);
    }

    @Override
    public int hashCode() {
        return Objects.hash(invulnerability, gameMode, respawnPoint);
    }
}
