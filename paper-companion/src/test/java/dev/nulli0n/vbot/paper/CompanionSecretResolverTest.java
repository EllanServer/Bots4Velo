package dev.nulli0n.vbot.paper;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class CompanionSecretResolverTest {
    private static final String ENVIRONMENT_SECRET = "environment-secret-0123456789abcdef";
    private static final String LITERAL_SECRET = "literal-secret-value-0123456789abcdef";

    @Test
    void nonEmptyEnvironmentValueTakesPrecedence() {
        byte[] resolved = CompanionSecretResolver.resolve("BOTS4VELO_BACKEND_SECRET", LITERAL_SECRET,
            name -> ENVIRONMENT_SECRET);

        assertArrayEquals(ENVIRONMENT_SECRET.getBytes(StandardCharsets.UTF_8), resolved);
    }

    @Test
    void absentOrEmptyEnvironmentValueFallsBackToLiteral() {
        assertArrayEquals(LITERAL_SECRET.getBytes(StandardCharsets.UTF_8),
            CompanionSecretResolver.resolve("BOTS4VELO_BACKEND_SECRET", LITERAL_SECRET, name -> null));
        assertArrayEquals(LITERAL_SECRET.getBytes(StandardCharsets.UTF_8),
            CompanionSecretResolver.resolve("BOTS4VELO_BACKEND_SECRET", LITERAL_SECRET, name -> "  "));
    }

    @Test
    void invalidEnvironmentValueDoesNotSilentlyUseLiteral() {
        assertThrows(IllegalArgumentException.class,
            () -> CompanionSecretResolver.resolve("BOTS4VELO_BACKEND_SECRET", LITERAL_SECRET,
                name -> "too-short"));
    }

    @Test
    void validEnvironmentValueWinsOverPlaceholderLiteral() {
        assertArrayEquals(ENVIRONMENT_SECRET.getBytes(StandardCharsets.UTF_8),
            CompanionSecretResolver.resolve("BOTS4VELO_BACKEND_SECRET",
                "CHANGE_ME_TO_A_RANDOM_SECRET_AT_LEAST_32_BYTES", name -> ENVIRONMENT_SECRET));
    }
}
