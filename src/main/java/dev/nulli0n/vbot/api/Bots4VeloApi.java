package dev.nulli0n.vbot.api;

import dev.nulli0n.vbot.bot.BotEvent;
import dev.nulli0n.vbot.bot.BotSnapshot;

import java.util.List;
import java.util.Optional;
import java.util.function.Consumer;

/** Public in-process API for other Velocity plugins. */
public interface Bots4VeloApi {
    List<BotSnapshot> bots();

    Optional<BotSnapshot> bot(String id);

    boolean start(String id);

    boolean stop(String id);

    boolean reconnect(String id);

    void addEventListener(Consumer<BotEvent> listener);

    void removeEventListener(Consumer<BotEvent> listener);
}
