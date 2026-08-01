package dev.nulli0n.vbot.config;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Locale;
import java.util.Objects;
import java.util.regex.Pattern;

/**
 * A persistable credential source for a managed bot. The reference identifies
 * where a password is read from; it never contains the resolved password.
 */
public record ManagedCredentialReference(Kind kind, String reference) {
    private static final Pattern SECRET_ALIAS = Pattern.compile("[A-Za-z0-9][A-Za-z0-9_.-]{0,63}");
    private static final Pattern ENVIRONMENT_NAME = Pattern.compile("[A-Za-z_][A-Za-z0-9_]{0,127}");

    public ManagedCredentialReference {
        kind = Objects.requireNonNull(kind, "Credential reference kind must not be null");
        reference = Objects.requireNonNull(reference, "Credential reference must not be null");
        if (!reference.equals(reference.trim())) {
            throw new IllegalArgumentException("Credential reference must not have surrounding whitespace");
        }
        switch (kind) {
            case NONE -> {
                if (!reference.isEmpty()) {
                    throw new IllegalArgumentException("NONE credential reference must be empty");
                }
            }
            case SECRET -> {
                if (!SECRET_ALIAS.matcher(reference).matches()) {
                    throw new IllegalArgumentException(
                        "Secret alias must use 1-64 letters, digits, '.', '_' or '-' and start with a letter or digit");
                }
            }
            case ENVIRONMENT -> {
                if (!ENVIRONMENT_NAME.matcher(reference).matches()) {
                    throw new IllegalArgumentException(
                        "Environment name must start with a letter or '_' and contain only letters, digits or '_'");
                }
            }
        }
    }

    public static ManagedCredentialReference none() {
        return new ManagedCredentialReference(Kind.NONE, "");
    }

    public static ManagedCredentialReference secret(String alias) {
        return new ManagedCredentialReference(Kind.SECRET, alias);
    }

    public static ManagedCredentialReference environment(String name) {
        return new ManagedCredentialReference(Kind.ENVIRONMENT, name);
    }

    /** A non-reversible identifier suitable for configuration change previews. */
    public String sourceFingerprint() {
        if (kind == Kind.NONE) {
            return "none";
        }
        String normalized = kind == Kind.SECRET ? reference.toLowerCase(Locale.ROOT) : reference;
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                .digest(normalized.getBytes(StandardCharsets.UTF_8));
            return (kind == Kind.SECRET ? "secret:" : "environment:")
                + HexFormat.of().formatHex(digest, 0, 8);
        }
        catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is unavailable", exception);
        }
    }

    public enum Kind {
        NONE,
        SECRET,
        ENVIRONMENT
    }
}
