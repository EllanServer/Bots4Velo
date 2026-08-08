package dev.nulli0n.vbot.addon.api;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletionStage;
import java.util.function.Consumer;

public interface AddonBotService {
    List<AddonBotSnapshot> bots();

    Optional<AddonBotSnapshot> bot(String id);

    boolean start(String id);

    boolean stop(String id);

    boolean reconnect(String id);

    Optional<String> currentServer(String id);

    CompletionStage<AddonServerSwitchResult> switchServer(String id, String server);

    boolean isPlayerOnline(UUID playerId);

    boolean sendPlayerMessage(UUID playerId, String message);

    void addEventListener(Consumer<AddonBotEvent> listener);

    void removeEventListener(Consumer<AddonBotEvent> listener);
}
