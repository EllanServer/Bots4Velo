package dev.nulli0n.vbot.addon.api;

import java.nio.file.Path;

/** Stable capabilities supplied to an addon without exposing core internals. */
public interface AddonContext {
    AddonBotService bots();

    AddonLogger logger();

    Path dataDirectory();

    String coreVersion();
}
