package dev.nulli0n.vbot.addon.api;

public interface AddonLogger {
    void info(String message);

    void warn(String message);

    void error(String message, Throwable failure);
}
