package dev.nulli0n.vbot.paper;

import dev.nulli0n.vbot.backend.protocol.BackendPolicy;
import dev.nulli0n.vbot.backend.protocol.BackendGameMode;
import dev.nulli0n.vbot.backend.protocol.BackendInvulnerability;
import dev.nulli0n.vbot.backend.protocol.ManagedBoolean;
import dev.nulli0n.vbot.backend.protocol.RespawnMode;

import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

public final class BackendPolicyCache {
    private final Map<UUID, BackendPolicy> policies = new ConcurrentHashMap<UUID, BackendPolicy>();

    public Optional<BackendPolicy> get(UUID playerId) {
        return Optional.ofNullable(policies.get(playerId));
    }

    public void put(UUID playerId, BackendPolicy policy) {
        if (policy.invulnerability() == BackendInvulnerability.UNCHANGED
            && policy.gameMode() == BackendGameMode.UNCHANGED
            && policy.respawnPoint().mode() == RespawnMode.UNCHANGED
            && policy.sleepingIgnored() == ManagedBoolean.UNCHANGED
            && policy.affectsSpawning() == ManagedBoolean.UNCHANGED
            && policy.pickupItems() == ManagedBoolean.UNCHANGED
            && policy.collidable() == ManagedBoolean.UNCHANGED) {
            policies.remove(playerId);
        }
        else {
            policies.put(playerId, policy);
        }
    }

    public void remove(UUID playerId) {
        policies.remove(playerId);
    }

    public int size() {
        return policies.size();
    }
}
