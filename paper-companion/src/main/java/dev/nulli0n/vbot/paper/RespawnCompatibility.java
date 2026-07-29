package dev.nulli0n.vbot.paper;

import org.bukkit.Location;
import org.bukkit.entity.Player;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;

final class RespawnCompatibility {
    private RespawnCompatibility() {
    }

    static Location get(Player player) throws PolicyApplyException {
        Method modern = method(player, "getRespawnLocation");
        if (modern != null) {
            return invokeLocation(modern, player);
        }
        Method legacy = method(player, "getBedSpawnLocation");
        if (legacy == null) {
            throw new PolicyApplyException(dev.nulli0n.vbot.backend.protocol.BackendStatus.UNSUPPORTED,
                "This Paper version exposes no respawn-location getter");
        }
        return invokeLocation(legacy, player);
    }

    static void set(Player player, Location location) throws PolicyApplyException {
        Method modern = method(player, "setRespawnLocation", Location.class, boolean.class);
        if (modern != null) {
            invokeVoid(modern, player, location, Boolean.TRUE);
            return;
        }
        Method legacy = method(player, "setBedSpawnLocation", Location.class, boolean.class);
        if (legacy == null) {
            throw new PolicyApplyException(dev.nulli0n.vbot.backend.protocol.BackendStatus.UNSUPPORTED,
                "This Paper version exposes no respawn-location setter");
        }
        invokeVoid(legacy, player, location, Boolean.TRUE);
    }

    private static Method method(Player player, String name, Class<?>... parameterTypes) {
        try {
            return player.getClass().getMethod(name, parameterTypes);
        }
        catch (NoSuchMethodException ignored) {
            return null;
        }
    }

    private static Location invokeLocation(Method method, Player player) throws PolicyApplyException {
        try {
            return (Location) method.invoke(player);
        }
        catch (IllegalAccessException | InvocationTargetException | ClassCastException exception) {
            throw new PolicyApplyException(dev.nulli0n.vbot.backend.protocol.BackendStatus.APPLY_FAILED,
                "Could not read the respawn location", unwrap(exception));
        }
    }

    private static void invokeVoid(Method method, Player player, Object... arguments) throws PolicyApplyException {
        try {
            method.invoke(player, arguments);
        }
        catch (IllegalAccessException | InvocationTargetException exception) {
            throw new PolicyApplyException(dev.nulli0n.vbot.backend.protocol.BackendStatus.APPLY_FAILED,
                "Could not update the respawn location", unwrap(exception));
        }
    }

    private static Throwable unwrap(Exception exception) {
        if (exception instanceof InvocationTargetException
            && ((InvocationTargetException) exception).getCause() != null) {
            return ((InvocationTargetException) exception).getCause();
        }
        return exception;
    }
}
