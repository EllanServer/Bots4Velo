package dev.nulli0n.vbot.paper;

import dev.nulli0n.vbot.backend.protocol.RespawnPoint;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RespawnLocationMatcherTest {
    @Test
    void acceptsPaperNormalizationWithinTheSameBlock() {
        RespawnPoint normalized = RespawnPoint.fixed("world", 12.0D, 64.0D, -9.0D, 0.0F, 0.0F);

        assertTrue(RespawnLocationMatcher.sameBlock(
            "world", 12.75D, 64.99D, -8.25D, normalized));
    }

    @Test
    void negativeFractionalCoordinatesUseMinecraftFloorSemantics() {
        RespawnPoint normalized = RespawnPoint.fixed("world", -1.0D, 70.0D, -2.0D, 0.0F, 0.0F);

        assertTrue(RespawnLocationMatcher.sameBlock(
            "world", -0.01D, 70.5D, -1.01D, normalized));
        assertFalse(RespawnLocationMatcher.sameBlock(
            "world", 0.01D, 70.5D, -1.01D, normalized));
    }

    @Test
    void rejectsARealBlockOrWorldMismatch() {
        RespawnPoint actual = RespawnPoint.fixed("world", 13.0D, 64.0D, -9.0D, 0.0F, 0.0F);

        assertFalse(RespawnLocationMatcher.sameBlock(
            "world", 12.75D, 64.99D, -8.25D, actual));
        assertFalse(RespawnLocationMatcher.sameBlock(
            "world_nether", 13.25D, 64.99D, -8.25D, actual));
    }

    @Test
    void clearOnlyMatchesAnAbsentRespawnPoint() {
        assertTrue(RespawnLocationMatcher.clearMatches(RespawnPoint.clear()));
        assertFalse(RespawnLocationMatcher.clearMatches(
            RespawnPoint.fixed("world", 0.0D, 64.0D, 0.0D, 0.0F, 0.0F)));
    }
}
