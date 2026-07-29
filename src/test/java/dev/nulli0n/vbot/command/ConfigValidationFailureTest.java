package dev.nulli0n.vbot.command;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class ConfigValidationFailureTest {
    @Test
    void neverEchoesParserMessagesThatContainYamlSecrets() {
        String secret = "SYNTHETIC_PASSWORD_92741";
        RuntimeException parserFailure = new IllegalArgumentException(
            "while parsing: password: \"" + secret);

        assertThat(ConfigValidationFailure.userMessage()).doesNotContain(secret, "password:");
        assertThat(ConfigValidationFailure.diagnosticType(parserFailure))
            .isEqualTo("IllegalArgumentException")
            .doesNotContain(secret, "password:");
    }
}
