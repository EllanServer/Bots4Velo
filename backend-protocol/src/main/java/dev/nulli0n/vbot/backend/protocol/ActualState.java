package dev.nulli0n.vbot.backend.protocol;

import java.util.Objects;

public final class ActualState {
    private final boolean present;
    private final boolean invulnerable;
    private final BackendGameMode gameMode;
    private final RespawnPoint respawnPoint;

    private ActualState(boolean present, boolean invulnerable, BackendGameMode gameMode,
                        RespawnPoint respawnPoint) {
        this.present = present;
        this.invulnerable = invulnerable;
        this.gameMode = Objects.requireNonNull(gameMode, "gameMode");
        this.respawnPoint = Objects.requireNonNull(respawnPoint, "respawnPoint");
    }

    public static ActualState absent() {
        return new ActualState(false, false, BackendGameMode.UNCHANGED, RespawnPoint.unchanged());
    }

    public static ActualState present(boolean invulnerable, BackendGameMode gameMode,
                                      RespawnPoint respawnPoint) {
        return new ActualState(true, invulnerable, gameMode, respawnPoint);
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
            && gameMode == that.gameMode && respawnPoint.equals(that.respawnPoint);
    }

    @Override
    public int hashCode() {
        return Objects.hash(present, invulnerable, gameMode, respawnPoint);
    }
}
