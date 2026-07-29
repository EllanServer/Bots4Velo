package dev.nulli0n.vbot.paper;

import dev.nulli0n.vbot.backend.protocol.ActualState;
import dev.nulli0n.vbot.backend.protocol.BackendGameMode;
import dev.nulli0n.vbot.backend.protocol.BackendInvulnerability;
import dev.nulli0n.vbot.backend.protocol.BackendPolicy;
import dev.nulli0n.vbot.backend.protocol.BackendStatus;
import dev.nulli0n.vbot.backend.protocol.ManagedBoolean;
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
    private static final double MAXIMUM_RECOVERY_HEALTH = 1024.0D;
    private static final int RECOVERY_FOOD = 20;
    private static final float RECOVERY_SATURATION = 20.0F;

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
        applyManagedBooleans(player, requested);
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
        return ActualState.presentExtended(player.isInvulnerable(), gameMode, point,
            player.isSleepingIgnored(), player.getAffectsSpawning(), player.getCanPickupItems(),
            player.isCollidable());
    }

    void recover(Player player) throws PolicyApplyException {
        double maximumHealth = player.getMaxHealth();
        if (!finite(maximumHealth) || maximumHealth <= 0.0D
            || maximumHealth > MAXIMUM_RECOVERY_HEALTH) {
            throw new PolicyApplyException(BackendStatus.APPLY_FAILED,
                "Paper reported an invalid maximum health for recovery");
        }
        try {
            player.setHealth(maximumHealth);
            player.setFoodLevel(RECOVERY_FOOD);
            player.setSaturation(RECOVERY_SATURATION);
            player.setFireTicks(0);
            player.setFallDistance(0.0F);
        }
        catch (RuntimeException exception) {
            throw new PolicyApplyException(BackendStatus.APPLY_FAILED,
                "Paper rejected the recovery values", exception);
        }
        if (!finite(player.getHealth()) || Double.compare(player.getHealth(), maximumHealth) != 0
            || player.getFoodLevel() != RECOVERY_FOOD || !finite(player.getSaturation())
            || Float.compare(player.getSaturation(), RECOVERY_SATURATION) != 0
            || player.getFireTicks() != 0 || !finite(player.getFallDistance())
            || Float.compare(player.getFallDistance(), 0.0F) != 0) {
            throw new PolicyApplyException(BackendStatus.APPLY_FAILED,
                "Another plugin rejected one or more recovery values");
        }
    }

    private void applyManagedBooleans(Player player, BackendPolicy requested) {
        if (requested.sleepingIgnored() != ManagedBoolean.UNCHANGED) {
            player.setSleepingIgnored(requested.sleepingIgnored() == ManagedBoolean.ENABLED);
        }
        if (requested.affectsSpawning() != ManagedBoolean.UNCHANGED) {
            player.setAffectsSpawning(requested.affectsSpawning() == ManagedBoolean.ENABLED);
        }
        if (requested.pickupItems() != ManagedBoolean.UNCHANGED) {
            player.setCanPickupItems(requested.pickupItems() == ManagedBoolean.ENABLED);
        }
        if (requested.collidable() != ManagedBoolean.UNCHANGED) {
            // Bukkit's flag is authoritative server-side; client-side collision prediction
            // and scoreboard-team plugins can still make player collision best-effort.
            player.setCollidable(requested.collidable() == ManagedBoolean.ENABLED);
        }
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
        verifyManagedBoolean("sleeping-ignored", requested.sleepingIgnored(), actual.sleepingIgnored());
        verifyManagedBoolean("affects-spawning", requested.affectsSpawning(), actual.affectsSpawning());
        verifyManagedBoolean("item-pickup", requested.pickupItems(), actual.pickupItems());
        verifyManagedBoolean("collidable (best-effort)", requested.collidable(), actual.collidable());
    }

    private void verifyManagedBoolean(String name, ManagedBoolean requested, boolean actual)
        throws PolicyApplyException {
        if ((requested == ManagedBoolean.ENABLED && !actual)
            || (requested == ManagedBoolean.DISABLED && actual)) {
            throw new PolicyApplyException(BackendStatus.APPLY_FAILED,
                "Another plugin rejected the " + name + " change");
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
