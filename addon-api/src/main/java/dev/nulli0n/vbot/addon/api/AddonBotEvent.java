package dev.nulli0n.vbot.addon.api;

import java.time.Instant;

public record AddonBotEvent(Instant at, String botId, String type, String detail) {
}
