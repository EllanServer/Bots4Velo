package dev.nulli0n.vbot.paper;

import dev.nulli0n.vbot.backend.protocol.RespawnMode;
import dev.nulli0n.vbot.backend.protocol.RespawnPoint;

final class RespawnLocationMatcher {
    private RespawnLocationMatcher() {
    }

    static boolean clearMatches(RespawnPoint actual) {
        return actual.mode() == RespawnMode.CLEAR;
    }

    static boolean sameBlock(String requestedWorld, double requestedX, double requestedY, double requestedZ,
                             RespawnPoint actual) {
        if (actual.mode() != RespawnMode.FIXED || requestedWorld == null
            || !requestedWorld.equals(actual.world())) {
            return false;
        }
        return finite(requestedX) && finite(requestedY) && finite(requestedZ)
            && finite(actual.x()) && finite(actual.y()) && finite(actual.z())
            && Math.floor(requestedX) == Math.floor(actual.x())
            && Math.floor(requestedY) == Math.floor(actual.y())
            && Math.floor(requestedZ) == Math.floor(actual.z());
    }

    private static boolean finite(double value) {
        return !Double.isNaN(value) && !Double.isInfinite(value);
    }
}
