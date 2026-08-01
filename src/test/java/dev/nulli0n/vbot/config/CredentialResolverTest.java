package dev.nulli0n.vbot.config;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assertions.assertThrows;

class CredentialResolverTest {
    @TempDir
    Path temporaryDirectory;

    @Test
    void malformedSecretsDocumentDoesNotExposeItsSourceLineOrValue() throws Exception {
        String credential = "v30-parser-secret-must-never-appear";
        Files.writeString(temporaryDirectory.resolve("secrets.yml"), """
            passwords:
              bot: \"%s
            """.formatted(credential));

        IOException failure = assertThrows(IOException.class,
            () -> CredentialResolver.load(temporaryDirectory));

        assertThat(failure.getMessage())
            .isEqualTo("secrets.yml could not be parsed; check its YAML structure and scalar values")
            .doesNotContain(credential);
        assertThat(failure.getCause()).isNull();
        assertThat(failure.toString()).doesNotContain(credential);
    }

    @Test
    void resolvesTrimmedReferencesAndScalarSecretAliases() throws Exception {
        Files.writeString(temporaryDirectory.resolve("secrets.yml"), """
            passwords:
              42: numeric-secret
              " PaddedAlias ": padded-secret
            """);
        CredentialResolver resolver = CredentialResolver.load(temporaryDirectory);

        assertThat(resolver.resolve("Numeric", true, "", "", " 42 ", Map.of()).value())
            .isEqualTo("numeric-secret");
        assertThat(resolver.resolve("Padded", true, "", "", " paddedalias ", Map.of()).value())
            .isEqualTo("padded-secret");
        assertThat(resolver.resolve("Environment", true, "", " TRIMMED_ENV ", "",
            Map.of("TRIMMED_ENV", "environment-secret")).value())
            .isEqualTo("environment-secret");
    }

    @Test
    void legacyReferencesRejectControlCharacters() {
        assertThatThrownBy(() -> CredentialResolver.empty().resolve(
            "ControlSecret", false, "", "", "team\u0007bot", Map.of()))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("control characters");
        assertThatThrownBy(() -> CredentialResolver.empty().resolve(
            "ControlEnvironment", false, "", "BOT\nPASSWORD", "", Map.of()))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("control characters");
    }
}
