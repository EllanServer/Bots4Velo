package dev.nulli0n.vbot.backend.protocol;

import java.nio.charset.StandardCharsets;
import java.util.Base64;

public final class ProtocolSecrets {
    public static final int MINIMUM_BYTES = 32;

    private ProtocolSecrets() {
    }

    public static byte[] decode(String configured) {
        String value = configured == null ? "" : configured.trim();
        byte[] result;
        if (value.startsWith("base64:")) {
            try {
                result = Base64.getDecoder().decode(value.substring("base64:".length()));
            }
            catch (IllegalArgumentException exception) {
                throw new IllegalArgumentException("shared-secret contains invalid base64", exception);
            }
        }
        else {
            result = value.getBytes(StandardCharsets.UTF_8);
        }
        if (result.length < MINIMUM_BYTES) {
            throw new IllegalArgumentException("shared-secret must contain at least " + MINIMUM_BYTES + " bytes");
        }
        return result;
    }
}
