package dev.nulli0n.vbot.backend.protocol;

import org.junit.jupiter.api.Test;

import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class BackendCapabilitiesTest {
    @Test
    void advertisementIsSafelyParsed() {
        String detail = "Bots4VeloPaper protocol 1; " + BackendCapabilities.ADVERTISEMENT;

        Set<String> capabilities = BackendCapabilities.parse(detail);

        assertEquals(3, capabilities.size());
        assertTrue(BackendCapabilities.supports(detail, BackendCapabilities.PROBE_EXT));
        assertTrue(BackendCapabilities.supports(detail, BackendCapabilities.APPLY_POLICY_EXT));
        assertTrue(BackendCapabilities.supports(detail, BackendCapabilities.RECOVER));
        assertThrows(UnsupportedOperationException.class, () -> capabilities.add("future/1"));
    }

    @Test
    void parserDoesNotAcceptPrefixesOrPartialMatches() {
        assertFalse(BackendCapabilities.supports(
            "notcaps=probe-ext/1; caps=apply-policy-ext/10,recover/10",
            BackendCapabilities.APPLY_POLICY_EXT));
        assertFalse(BackendCapabilities.supports("caps=probe-ext/1suffix",
            BackendCapabilities.PROBE_EXT));
        assertTrue(BackendCapabilities.parse(null).isEmpty());
    }
}
