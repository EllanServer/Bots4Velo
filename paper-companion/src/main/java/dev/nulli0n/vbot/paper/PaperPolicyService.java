package dev.nulli0n.vbot.paper;

import dev.nulli0n.vbot.backend.protocol.ActualState;
import dev.nulli0n.vbot.backend.protocol.BackendGameMode;
import dev.nulli0n.vbot.backend.protocol.BackendInvulnerability;
import dev.nulli0n.vbot.backend.protocol.BackendPolicy;
import dev.nulli0n.vbot.backend.protocol.BackendStatus;
import dev.nulli0n.vbot.backend.protocol.RespawnPoint;
import org.bukkit.Bukkit;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.entity.Player;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;

final class PaperPolicyService {
    private static final double WORLD_LIMIT = 29_999_984.0D;

    BackendPolicy apply(Player player, BackendPolicy requested) throws PolicyApplyException {
        ResolvedRespawn resolved = resolveRespawn(player, requested.respawnPoint());
        GameMode gameMode = resolveGameMode(requested.gameMode());

        if (requested.invulnerability() == BackendInvulnerability.ENABLED) {
            player.setInvulnerable(true);
        }
        else if (requested.invulnerability() == BackendInvulnerability.DISABLED) {
            player.setInvulnerable(false);
        }
        if (gameMode != null) {
            player.setGameMode(gameMode);
        }
        if (resolved.apply) {
            RespawnCompatibility.set(player, resolved.location);
        }

        ActualState actual = actualState(player);
        verify(requested, resolved, actual);
        return requested.withRespawnPoint(resolved.cachedPoint);
    }

    ActualState actualState(Player player) throws PolicyApplyException {
        RespawnPoint point = RespawnPoint.clear();
        Location location = RespawnCompatibility.get(player);
        if (location != null && location.getWorld() != null) {
            point = RespawnPoint.fixed(location.getWorld().getName(), location.getX(), location.getY(),
                location.getZ(), location.getYaw(), location.getPitch());
        }
        BackendGameMode gameMode;
        try {
            gameMode = BackendGameMode.valueOf(player.getGameMode().name());
        }
        catch (IllegalArgumentException exception) {
            gameMode = BackendGameMode.UNCHANGED;
        }
        return ActualState.present(player.isInvulnerable(), gameMode, point);
    }

    private ResolvedRespawn resolveRespawn(Player player, RespawnPoint requested) throws PolicyApplyException {
        switch (requested.mode()) {
            case UNCHANGED:
                return new ResolvedRespawn(false, null, requested);
            case CLEAR:
                return new ResolvedRespawn(true, null, requested);
            case CURRENT:
                Location current = player.getLocation().clone();
                validateLocation(current);
                RespawnPoint fixed = RespawnPoint.fixed(current.getWorld().getName(), current.getX(), current.getY(),
                    current.getZ(), current.getYaw(), current.getPitch());
                return new ResolvedRespawn(true, current, fixed);
            case FIXED:
                Location configured = configuredLocation(requested);
                return new ResolvedRespawn(true, configured, requested);
            case WORLD_SPAWN:
                World world = requested.world().isEmpty() ? player.getWorld() : Bukkit.getWorld(requested.world());
                if (world == null) {
                    throw new PolicyApplyException(BackendStatus.WORLD_NOT_FOUND,
                        "Unknown Paper world: " + requested.world());
                }
                Location spawn = world.getSpawnLocation().clone();
                validateLocation(spawn);
                return new ResolvedRespawn(true, spawn, requested);
            default:
                throw new PolicyApplyException(BackendStatus.BAD_REQUEST,
                    "Unsupported respawn mode: " + requested.mode());
        }
    }

    private Location configuredLocation(RespawnPoint point) throws PolicyApplyException {
        if (point.world().isEmpty()) {
            throw new PolicyApplyException(BackendStatus.WORLD_NOT_FOUND,
                "A fixed respawn point requires a Paper world name");
        }
        World world = Bukkit.getWorld(point.world());
        if (world == null) {
            throw new PolicyApplyException(BackendStatus.WORLD_NOT_FOUND,
                "Unknown Paper world: " + point.world());
        }
        Location location = new Location(world, point.x(), point.y(), point.z(), point.yaw(), point.pitch());
        validateLocation(location);
        return location;
    }

    private void validateLocation(Location location) throws PolicyApplyException {
        if (location.getWorld() == null || !finite(location.getX()) || !finite(location.getY())
            || !finite(location.getZ()) || !finite(location.getYaw()) || !finite(location.getPitch())) {
            throw new PolicyApplyException(BackendStatus.INVALID_LOCATION,
                "Respawn coordinates must be finite and include a world");
        }
        int minimumHeight = minimumHeight(location.getWorld());
        if (Math.abs(location.getX()) > WORLD_LIMIT || Math.abs(location.getZ()) > WORLD_LIMIT
            || location.getY() < minimumHeight || location.getY() >= location.getWorld().getMaxHeight()
            || location.getPitch() < -90.0F || location.getPitch() > 90.0F) {
            throw new PolicyApplyException(BackendStatus.INVALID_LOCATION,
                "Respawn coordinates are outside the Paper world bounds");
        }
    }

    private int minimumHeight(World world) {
        try {
            Method method = world.getClass().getMethod("getMinHeight");
            return ((Number) method.invoke(world)).intValue();
        }
        catch (NoSuchMethodException ignored) {
            return 0;
        }
        catch (IllegalAccessException | InvocationTargetException | ClassCastException exception) {
            return 0;
        }
    }

    private GameMode resolveGameMode(BackendGameMode mode) throws PolicyApplyException {
        if (mode == BackendGameMode.UNCHANGED) {
            return null;
        }
        try {
            return GameMode.valueOf(mode.name());
        }
        catch (IllegalArgumentException exception) {
            throw new PolicyApplyException(BackendStatus.UNSUPPORTED,
                "Paper does not support game mode " + mode, exception);
        }
    }

    private void verify(BackendPolicy requested, ResolvedRespawn resolved, ActualState actual)
        throws PolicyApplyException {
        if (requested.invulnerability() == BackendInvulnerability.ENABLED && !actual.invulnerable()) {
            throw new PolicyApplyException(BackendStatus.APPLY_FAILED,
                "Another plugin rejected the invulnerability change");
        }
        if (requested.invulnerability() == BackendInvulnerability.DISABLED && actual.invulnerable()) {
            throw new PolicyApplyException(BackendStatus.APPLY_FAILED,
                "Another plugin rejected the invulnerability change");
        }
        if (requested.gameMode() != BackendGameMode.UNCHANGED && actual.gameMode() != requested.gameMode()) {
            throw new PolicyApplyException(BackendStatus.APPLY_FAILED,
                "Another plugin rejected the game-mode change");
        }
        if (resolved.apply && !sameRespawn(resolved.location, actual.respawnPoint())) {
            throw new PolicyApplyException(BackendStatus.APPLY_FAILED,
                "Paper did not retain the requested respawn location");
        }
    }

    private boolean sameRespawn(Location requested, RespawnPoint actual) {
        if (requested == null) {
            return RespawnLocationMatcher.clearMatches(actual);
        }
        return requested.getWorld() != null && RespawnLocationMatcher.sameBlock(
            requested.getWorld().getName(), requested.getX(), requested.getY(), requested.getZ(), actual);
    }

    private boolean finite(double value) {
        return !Double.isNaN(value) && !Double.isInfinite(value);
    }

    private static final class ResolvedRespawn {
        private final boolean apply;
        private final Location location;
        private final RespawnPoint cachedPoint;

        private ResolvedRespawn(boolean apply, Location location, RespawnPoint cachedPoint) {
            this.apply = apply;
            this.location = location;
            this.cachedPoint = cachedPoint;
        }
    }
}
