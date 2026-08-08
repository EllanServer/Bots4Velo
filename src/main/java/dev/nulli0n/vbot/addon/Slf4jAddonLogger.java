package dev.nulli0n.vbot.addon;

import dev.nulli0n.vbot.addon.api.AddonLogger;
import org.slf4j.Logger;

public record Slf4jAddonLogger(Logger delegate) implements AddonLogger {
    @Override
    public void info(String message) {
        delegate.info(message);
    }

    @Override
    public void warn(String message) {
        delegate.warn(message);
    }

    @Override
    public void error(String message, Throwable failure) {
        delegate.error(message, failure);
    }
}
