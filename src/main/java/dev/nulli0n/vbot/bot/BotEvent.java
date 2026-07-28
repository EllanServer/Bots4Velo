package dev.nulli0n.vbot.bot;

import java.time.Instant;

public record BotEvent(Instant at, String botId, String type, String detail) {
}
