package dev.nulli0n.vbot.bot;

import dev.nulli0n.vbot.config.BotPluginConfig.BehaviorMode;

import java.time.Instant;

public record BehaviorSnapshot(
    BehaviorMode mode,
    boolean requested,
    boolean running,
    boolean paused,
    long cycles,
    Instant lastActionAt,
    String lastAction,
    String followTarget
) {
}
