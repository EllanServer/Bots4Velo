package dev.nulli0n.vbot.backend.protocol;

import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.regex.Pattern;

/** Shared capability names advertised in the signed legacy PROBE detail. */
public final class BackendCapabilities {
    public static final String PROBE_EXT = "probe-ext/1";
    public static final String APPLY_POLICY_EXT = "apply-policy-ext/1";
    public static final String RECOVER = "recover/1";
    public static final String ADVERTISEMENT = "caps=" + PROBE_EXT + "," + APPLY_POLICY_EXT + "," + RECOVER;

    private static final Pattern VALID_CAPABILITY = Pattern.compile("[a-z0-9-]+/[1-9][0-9]*");

    private BackendCapabilities() {
    }

    /**
     * Parses only exact {@code caps=} fields delimited by whitespace or a
     * semicolon. Malformed entries are ignored rather than partially matched.
     */
    public static Set<String> parse(String detail) {
        if (detail == null || detail.trim().isEmpty()) {
            return Collections.emptySet();
        }
        LinkedHashSet<String> capabilities = new LinkedHashSet<String>();
        String[] fields = detail.trim().split("[;\\s]+");
        for (String field : fields) {
            if (!field.startsWith("caps=")) {
                continue;
            }
            String encoded = field.substring("caps=".length());
            if (encoded.isEmpty()) {
                continue;
            }
            for (String capability : encoded.split(",", -1)) {
                if (VALID_CAPABILITY.matcher(capability).matches()) {
                    capabilities.add(capability);
                }
            }
        }
        if (capabilities.isEmpty()) {
            return Collections.emptySet();
        }
        return Collections.unmodifiableSet(capabilities);
    }

    public static boolean supports(String detail, String capability) {
        return capability != null && parse(detail).contains(capability);
    }
}
