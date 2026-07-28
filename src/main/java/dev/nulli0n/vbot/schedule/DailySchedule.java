package dev.nulli0n.vbot.schedule;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.ResolverStyle;

/** Calculates the next wall-clock execution time for a daily bot schedule. */
public final class DailySchedule {
    private static final DateTimeFormatter TIME_FORMAT = DateTimeFormatter.ofPattern("HH:mm")
        .withResolverStyle(ResolverStyle.STRICT);

    private DailySchedule() {
    }

    public static Duration delayUntilNext(String at, String timezone, Instant now) {
        ZoneId zone = ZoneId.of(timezone);
        ZonedDateTime current = now.atZone(zone);
        ZonedDateTime next = current.with(parseTime(at));
        if (!next.isAfter(current)) {
            next = next.plusDays(1);
        }
        return Duration.between(current, next);
    }

    public static LocalTime parseTime(String value) {
        return LocalTime.parse(value, TIME_FORMAT);
    }
}
