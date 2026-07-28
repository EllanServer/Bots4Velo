package dev.nulli0n.vbot.bot;

import java.util.Locale;

public enum FailureCategory {
    NONE,
    AUTHENTICATION,
    TWO_FACTOR_OR_CAPTCHA,
    BANNED_OR_KICKED,
    BACKEND_UNAVAILABLE,
    NETWORK,
    CONFIGURATION,
    UNKNOWN;

    public static FailureCategory classify(String detail) {
        String value = detail == null ? "" : detail.toLowerCase(Locale.ROOT);
        if (value.isBlank() || value.equals("connected") || value.equals("never connected")) {
            return NONE;
        }
        if (value.contains("captcha") || value.contains("2fa") || value.contains("two-factor")
            || value.contains("verification code")) {
            return TWO_FACTOR_OR_CAPTCHA;
        }
        if (value.contains("ban") || value.contains("kicked") || value.contains("blacklist")) {
            return BANNED_OR_KICKED;
        }
        if (value.contains("incorrect password") || value.contains("wrong password")
            || value.contains("invalid password") || value.contains("authentication failed")) {
            return AUTHENTICATION;
        }
        if (value.contains("backend") || value.contains("server switch") || value.contains("unable to connect")
            || value.contains("connection refused")) {
            return BACKEND_UNAVAILABLE;
        }
        if (value.contains("timeout") || value.contains("connection") || value.contains("network")) {
            return NETWORK;
        }
        if (value.contains("config") || value.contains("protocol")) {
            return CONFIGURATION;
        }
        return UNKNOWN;
    }
}
