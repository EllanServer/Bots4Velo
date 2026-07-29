package dev.nulli0n.vbot.backend.protocol;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.Base64;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class ProtocolSecretsTest {
    @Test
    void acceptsRawAndBase64Secrets() {
        byte[] secret = "0123456789abcdef0123456789abcdef".getBytes(StandardCharsets.UTF_8);

        assertArrayEquals(secret, ProtocolSecrets.decode(new String(secret, StandardCharsets.UTF_8)));
        assertArrayEquals(secret, ProtocolSecrets.decode("base64:" + Base64.getEncoder().encodeToString(secret)));
    }

    @Test
    void rejectsShortSecrets() {
        assertThrows(IllegalArgumentException.class, () -> ProtocolSecrets.decode("too-short"));
    }
}
