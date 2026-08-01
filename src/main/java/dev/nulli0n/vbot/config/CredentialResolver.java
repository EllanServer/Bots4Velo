package dev.nulli0n.vbot.config;

import org.yaml.snakeyaml.LoaderOptions;
import org.yaml.snakeyaml.Yaml;
import org.yaml.snakeyaml.constructor.SafeConstructor;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

/** Resolves credential references without exposing their values to persistence code. */
final class CredentialResolver {
    private final Map<String, String> secrets;

    private CredentialResolver(Map<String, String> secrets) {
        this.secrets = Map.copyOf(secrets);
    }

    static CredentialResolver empty() {
        return new CredentialResolver(Map.of());
    }

    static CredentialResolver load(Path dataDirectory) throws IOException {
        Path secretsPath = dataDirectory.resolve("secrets.yml");
        if (Files.notExists(secretsPath)) {
            return empty();
        }
        LoaderOptions options = new LoaderOptions();
        options.setAllowDuplicateKeys(false);
        try (InputStream stream = Files.newInputStream(secretsPath)) {
            try {
                Object loaded = new Yaml(new SafeConstructor(options)).load(stream);
                if (loaded == null) {
                    return empty();
                }
                Map<String, Object> root = stringMap(loaded, "secrets.yml root");
                Map<String, String> values = new LinkedHashMap<>();
                section(root, "passwords", "secrets.yml passwords").forEach((name, value) ->
                    values.put(normalizeSecretName(name), scalar(value, "secrets.yml passwords." + name)));
                section(root, "bots", "secrets.yml bots").forEach((name, value) -> {
                    if (value instanceof Map<?, ?>) {
                        Map<String, Object> bot = stringMap(value, "secrets.yml bots." + name);
                        Object password = bot.get("password");
                        if (password != null) {
                            String resolved = scalar(password, "secrets.yml bots." + name + ".password");
                            if (!resolved.isBlank()) {
                                values.putIfAbsent(normalizeSecretName(name), resolved);
                            }
                        }
                    }
                });
                return new CredentialResolver(values);
            } catch (RuntimeException ignored) {
                // SnakeYAML parser errors can quote the offending source line. Do not retain the
                // original exception as a cause because that line can contain a credential value.
                throw new IOException("secrets.yml could not be parsed; check its YAML structure and scalar values");
            }
        }
    }

    ResolvedCredential resolve(String botId, boolean enabled, String inline,
                               String environmentName, String secretAlias,
                               Map<String, String> environment) {
        String safeInline = inline == null ? "" : inline;
        String safeEnvironment = environmentName == null ? "" : environmentName.trim();
        String safeSecret = secretAlias == null ? "" : secretAlias.trim();
        int sources = (safeInline.trim().isEmpty() ? 0 : 1)
            + (safeEnvironment.isEmpty() ? 0 : 1)
            + (safeSecret.isEmpty() ? 0 : 1);
        if (sources > 1) {
            throw new IllegalArgumentException("bots." + botId
                + " must define only one of password, password-env or password-secret");
        }
        if (!safeEnvironment.isEmpty()) {
            return resolveLegacyReference(botId, enabled, ManagedCredentialReference.Kind.ENVIRONMENT,
                safeEnvironment, environment);
        }
        if (!safeSecret.isEmpty()) {
            return resolveLegacyReference(botId, enabled, ManagedCredentialReference.Kind.SECRET,
                safeSecret, environment);
        }
        return new ResolvedCredential(safeInline, safeInline.trim().isEmpty() ? "none" : "inline");
    }

    ResolvedCredential resolve(String botId, boolean enabled,
                               ManagedCredentialReference reference,
                               Map<String, String> environment) {
        return switch (reference.kind()) {
            case NONE -> new ResolvedCredential("", reference.sourceFingerprint());
            case SECRET -> {
                String value = secrets.get(normalizeSecretName(reference.reference()));
                yield resolvedReference(botId, enabled, reference.reference(), reference.sourceFingerprint(),
                    value, "password-secret was not found");
            }
            case ENVIRONMENT -> {
                String value = environment.get(reference.reference());
                yield resolvedReference(botId, enabled, reference.reference(), reference.sourceFingerprint(),
                    value, "password-env is not set");
            }
        };
    }

    private ResolvedCredential resolveLegacyReference(String botId, boolean enabled,
                                                       ManagedCredentialReference.Kind kind,
                                                       String rawReference,
                                                       Map<String, String> environment) {
        String reference = normalizeLegacyReference(rawReference,
            kind == ManagedCredentialReference.Kind.SECRET ? "password-secret" : "password-env");
        String value = kind == ManagedCredentialReference.Kind.SECRET
            ? secrets.get(normalizeSecretName(reference)) : environment.get(reference);
        String failure = kind == ManagedCredentialReference.Kind.SECRET
            ? "password-secret was not found" : "password-env is not set";
        return resolvedReference(botId, enabled, reference, sourceFingerprint(kind, reference), value, failure);
    }

    private static ResolvedCredential resolvedReference(String botId, boolean enabled,
                                                         String reference, String sourceFingerprint,
                                                         String value, String failure) {
        if (value == null || value.isBlank()) {
            if (enabled) {
                throw new IllegalArgumentException("bots." + botId + "." + failure + ": "
                    + reference);
            }
            return new ResolvedCredential("", sourceFingerprint);
        }
        return new ResolvedCredential(value, sourceFingerprint);
    }

    /**
     * Existing YAML accepted arbitrary scalar aliases. Preserve that behavior
     * without allowing invisible control characters or ambiguous outer space;
     * new command input still uses ManagedCredentialReference's strict factories.
     */
    static String normalizeLegacyReference(String value, String path) {
        String normalized = value == null ? "" : value.trim();
        if (normalized.isEmpty()) {
            throw new IllegalArgumentException(path + " must not be empty");
        }
        if (normalized.codePoints().anyMatch(Character::isISOControl)) {
            throw new IllegalArgumentException(path + " must not contain control characters");
        }
        return normalized;
    }

    private static String sourceFingerprint(ManagedCredentialReference.Kind kind, String reference) {
        if (kind == ManagedCredentialReference.Kind.NONE) {
            return "none";
        }
        String normalized = kind == ManagedCredentialReference.Kind.SECRET
            ? reference.toLowerCase(Locale.ROOT) : reference;
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                .digest(normalized.getBytes(StandardCharsets.UTF_8));
            return (kind == ManagedCredentialReference.Kind.SECRET ? "secret:" : "environment:")
                + HexFormat.of().formatHex(digest, 0, 8);
        }
        catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is unavailable", exception);
        }
    }

    private static String normalizeSecretName(String value) {
        return value == null ? "" : value.trim().toLowerCase(Locale.ROOT);
    }

    private static Map<String, Object> section(Map<String, Object> root, String key, String path) {
        Object value = root.get(key);
        return value == null ? Map.of() : stringMap(value, path);
    }

    private static Map<String, Object> stringMap(Object value, String path) {
        if (!(value instanceof Map<?, ?> raw)) {
            throw new IllegalArgumentException(path + " must be a mapping");
        }
        Map<String, Object> result = new LinkedHashMap<>();
        raw.forEach((key, item) -> {
            if (key instanceof Map<?, ?> || key instanceof Iterable<?>
                || (key != null && key.getClass().isArray())) {
                throw new IllegalArgumentException(path + " keys must be scalar values");
            }
            result.put(String.valueOf(key), item);
        });
        return result;
    }

    private static String scalar(Object value, String path) {
        if (value instanceof String text) {
            return text;
        }
        if (value instanceof Number || value instanceof Boolean || value instanceof Character) {
            return String.valueOf(value);
        }
        throw new IllegalArgumentException(path + " must be a scalar value");
    }

    record ResolvedCredential(String value, String sourceFingerprint) {
    }
}
