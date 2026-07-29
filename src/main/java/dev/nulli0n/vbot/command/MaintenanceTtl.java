package dev.nulli0n.vbot.command;

import java.time.Duration;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Parses the intentionally small, operator-friendly TTL syntax used by maintenance holds. */
final class MaintenanceTtl {
    private static final Pattern VALUE = Pattern.compile("([1-9][0-9]*)([smhd])", Pattern.CASE_INSENSITIVE);
    private static final Duration MAXIMUM = Duration.ofDays(30);

    private MaintenanceTtl() {
    }

    static Duration parse(String raw) {
        String normalized = raw == null ? "" : raw.trim().toLowerCase(Locale.ROOT);
        Matcher matcher = VALUE.matcher(normalized);
        if (!matcher.matches()) {
            throw new IllegalArgumentException("TTL must use <number><s|m|h|d>");
        }
        long amount;
        try {
            amount = Long.parseLong(matcher.group(1));
        }
        catch (NumberFormatException exception) {
            throw new IllegalArgumentException("TTL is too large", exception);
        }
        Duration duration;
        try {
            duration = switch (matcher.group(2)) {
                case "s" -> Duration.ofSeconds(amount);
                case "m" -> Duration.ofMinutes(amount);
                case "h" -> Duration.ofHours(amount);
                case "d" -> Duration.ofDays(amount);
                default -> throw new IllegalArgumentException("Unsupported TTL unit");
            };
        }
        catch (ArithmeticException exception) {
            throw new IllegalArgumentException("TTL is too large", exception);
        }
        if (duration.compareTo(MAXIMUM) > 0) {
            throw new IllegalArgumentException("TTL must not exceed 30 days");
        }
        return duration;
    }
}
