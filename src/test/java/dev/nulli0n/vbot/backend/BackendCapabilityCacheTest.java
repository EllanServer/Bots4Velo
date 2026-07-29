package dev.nulli0n.vbot.backend;

import dev.nulli0n.vbot.backend.protocol.BackendCapabilities;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class BackendCapabilityCacheTest {
    @Test
    void parsesCurrentAdvertisementAndTreatsOldProbeDetailAsLegacy() {
        BackendCapabilityCache.Capabilities legacy =
            BackendCapabilityCache.Capabilities.parse("Bots4VeloPaper is ready");
        BackendCapabilityCache.Capabilities current = BackendCapabilityCache.Capabilities.parse(
            "Bots4VeloPaper is ready; " + BackendCapabilities.ADVERTISEMENT);

        assertThat(legacy.probeExtended()).isFalse();
        assertThat(legacy.applyPolicyExtended()).isFalse();
        assertThat(legacy.recover()).isFalse();
        assertThat(current.probeExtended()).isTrue();
        assertThat(current.applyPolicyExtended()).isTrue();
        assertThat(current.recover()).isTrue();
    }

    @Test
    void neverReusesCapabilitiesForAReplacementConnection() {
        BackendCapabilityCache cache = new BackendCapabilityCache(2);
        Object firstConnection = new Object();
        Object replacementConnection = new Object();
        BackendCapabilityCache.Capabilities capabilities =
            new BackendCapabilityCache.Capabilities(true, true, true);

        cache.put("bot", firstConnection, capabilities);

        assertThat(cache.get("bot", firstConnection)).contains(capabilities);
        assertThat(cache.get("bot", replacementConnection)).isEmpty();
        assertThat(cache.size()).isZero();
    }

    @Test
    void boundsEntriesAndSupportsExplicitInvalidation() {
        BackendCapabilityCache cache = new BackendCapabilityCache(2);
        BackendCapabilityCache.Capabilities capabilities =
            new BackendCapabilityCache.Capabilities(false, false, false);
        Object first = new Object();
        Object second = new Object();
        Object third = new Object();

        cache.put("first", first, capabilities);
        cache.put("second", second, capabilities);
        assertThat(cache.get("first", first)).contains(capabilities);
        cache.put("third", third, capabilities);

        assertThat(cache.get("second", second)).isEmpty();
        assertThat(cache.get("first", first)).contains(capabilities);
        assertThat(cache.get("third", third)).contains(capabilities);

        cache.remove("first");
        assertThat(cache.get("first", first)).isEmpty();
        cache.clear();
        assertThat(cache.size()).isZero();
    }
}
