package dev.nulli0n.vbot.command;

import java.util.Objects;

/** Converts parser failures to output that cannot echo an offending secret-bearing YAML line. */
final class ConfigValidationFailure {
    private static final String USER_MESSAGE =
        "Configuration validation failed; live bots were not changed. Check the proxy log and YAML syntax.";

    private ConfigValidationFailure() {
    }

    static String userMessage() {
        return USER_MESSAGE;
    }

    static String diagnosticType(Throwable failure) {
        return Objects.requireNonNull(failure, "failure").getClass().getSimpleName();
    }
}
