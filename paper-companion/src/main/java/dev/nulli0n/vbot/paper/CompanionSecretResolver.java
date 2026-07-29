package dev.nulli0n.vbot.paper;

import dev.nulli0n.vbot.backend.protocol.ProtocolSecrets;

import java.util.function.Function;

final class CompanionSecretResolver {
    private CompanionSecretResolver() {
    }

    static byte[] resolve(String environmentVariable, String literal,
                          Function<String, String> environment) {
        String variable = environmentVariable == null ? "" : environmentVariable.trim();
        if (!variable.isEmpty()) {
            final String value;
            try {
                value = environment.apply(variable);
            }
            catch (RuntimeException exception) {
                throw new IllegalArgumentException("Could not read shared-secret-env " + variable, exception);
            }
            if (value != null && !value.trim().isEmpty()) {
                return decodeSelected(value);
            }
        }
        return decodeSelected(literal);
    }

    private static byte[] decodeSelected(String value) {
        String normalized = value == null ? "" : value.trim();
        if (normalized.toUpperCase(java.util.Locale.ROOT).startsWith("CHANGE_ME")) {
            throw new IllegalArgumentException("The selected shared secret still contains a CHANGE_ME placeholder");
        }
        return ProtocolSecrets.decode(value);
    }
}
