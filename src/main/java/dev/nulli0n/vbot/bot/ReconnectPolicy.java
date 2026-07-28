package dev.nulli0n.vbot.bot;

import dev.nulli0n.vbot.config.BotPluginConfig.ReconnectConfig;

public final class ReconnectPolicy {
    private final ReconnectConfig config;

    public ReconnectPolicy(ReconnectConfig config) {
        this.config = config;
    }

    public boolean allows(int attempt) {
        return config.maximumAttempts() == 0 || attempt <= config.maximumAttempts();
    }

    public long delayMillis(int attempt, double randomUnit) {
        int exponent = Math.max(0, attempt - 1);
        double base = config.initialDelayMillis() * Math.pow(config.multiplier(), exponent);
        double capped = Math.min(config.maximumDelayMillis(), base);
        double normalized = Math.max(0.0, Math.min(1.0, randomUnit));
        double factor = 1.0 + ((normalized * 2.0) - 1.0) * config.jitter();
        return Math.max(0L, Math.round(capped * factor));
    }
}
