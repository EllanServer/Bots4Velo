package dev.nulli0n.vbot;

import org.junit.jupiter.api.Test;
import org.yaml.snakeyaml.LoaderOptions;
import org.yaml.snakeyaml.Yaml;
import org.yaml.snakeyaml.constructor.SafeConstructor;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;

class InitializationFailureDiagnosticTest {
    @Test
    void startupDiagnosticNeverIncludesTheSecretBearingYamlLine() {
        String credential = "startup-sentinel-secret-92741";
        LoaderOptions options = new LoaderOptions();
        RuntimeException parserFailure = assertThrows(RuntimeException.class, () ->
            new Yaml(new SafeConstructor(options)).load("""
                bots:
                  farm:
                    password: [%s
                """.formatted(credential)));

        // Prove the upstream parser exception itself contains the source
        // secret, then verify the only startup diagnostic does not.
        assertThat(parserFailure.getMessage()).contains(credential);
        assertThat(Bots4VeloPlugin.initializationFailureDiagnostic(parserFailure))
            .contains(parserFailure.getClass().getSimpleName())
            .doesNotContain(credential)
            .doesNotContain(parserFailure.getMessage());
    }
}
