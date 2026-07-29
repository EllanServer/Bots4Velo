package dev.nulli0n.vbot.paper;

import dev.nulli0n.vbot.backend.protocol.BackendGameMode;
import dev.nulli0n.vbot.backend.protocol.BackendInvulnerability;
import dev.nulli0n.vbot.backend.protocol.BackendPolicy;
import dev.nulli0n.vbot.backend.protocol.ManagedBoolean;
import dev.nulli0n.vbot.backend.protocol.RespawnPoint;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

class BackendPolicyCacheTest {
    @Test
    void replacesAndRemovesAPlayersFullDesiredPolicy() {
        BackendPolicyCache cache = new BackendPolicyCache();
        UUID playerId = UUID.randomUUID();
        BackendPolicy first = BackendPolicy.unchanged();
        BackendPolicy replacement = new BackendPolicy(BackendInvulnerability.DISABLED,
            BackendGameMode.SURVIVAL, RespawnPoint.worldSpawn("world"));

        cache.put(playerId, first);
        cache.put(playerId, replacement);

        assertEquals(replacement, cache.get(playerId).orElse(null));
        assertEquals(1, cache.size());
        cache.remove(playerId);
        assertFalse(cache.get(playerId).isPresent());
    }

    @Test
    void allUnchangedPolicyClearsAnOlderManagedPolicy() {
        BackendPolicyCache cache = new BackendPolicyCache();
        UUID playerId = UUID.randomUUID();
        cache.put(playerId, new BackendPolicy(BackendInvulnerability.ENABLED,
            BackendGameMode.CREATIVE, RespawnPoint.fixed("world", 1.0D, 64.0D, 2.0D, 0.0F, 0.0F)));

        cache.put(playerId, BackendPolicy.unchanged());

        assertFalse(cache.get(playerId).isPresent());
        assertEquals(0, cache.size());
    }

    @Test
    void extendedBooleanPolicyIsCachedAndAllUnchangedStillClearsIt() {
        BackendPolicyCache cache = new BackendPolicyCache();
        UUID playerId = UUID.randomUUID();
        BackendPolicy extended = new BackendPolicy(BackendInvulnerability.UNCHANGED,
            BackendGameMode.UNCHANGED, RespawnPoint.unchanged(), ManagedBoolean.ENABLED,
            ManagedBoolean.DISABLED, ManagedBoolean.ENABLED, ManagedBoolean.DISABLED);

        cache.put(playerId, extended);

        assertEquals(extended, cache.get(playerId).orElse(null));
        cache.put(playerId, BackendPolicy.unchanged());
        assertFalse(cache.get(playerId).isPresent());
    }
}
