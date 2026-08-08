package dev.nulli0n.vbot.api.common;

import dev.nulli0n.vbot.bot.BotEvent;
import dev.nulli0n.vbot.bot.BotState;
import dev.nulli0n.vbot.transport.BotPosition;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletionStage;
import java.util.function.Consumer;

/**
 * Convenience controls for the operations most integrations need.
 *
 * <p>This is intentionally a separate additive API. The original
 * {@code dev.nulli0n.vbot.api.Bots4VeloApi} interface is not changed.</p>
 */
public interface CommonBotApi {
    List<BotView> bots();

    List<BotView> select(String selector);

    Optional<BotView> bot(String id);

    BatchResult start(String selector);

    BatchResult stop(String selector);

    BatchResult reconnect(String selector);

    CompletionStage<List<ServerSwitchResult>> switchServer(String selector, String server);

    BatchResult command(String selector, String command);

    BatchResult move(String selector, double x, double y, double z);

    BatchResult look(String selector, float yaw, float pitch);

    BatchResult swing(String selector);

    BatchResult jump(String selector);

    BatchResult setSneaking(String selector, boolean sneaking);

    BatchResult startBehavior(String selector);

    BatchResult pauseBehavior(String selector);

    FollowResult follow(String botId, String playerName);

    boolean stopFollowing(String botId);

    void addEventListener(Consumer<CommonBotEvent> listener);

    void removeEventListener(Consumer<CommonBotEvent> listener);

    enum Action {
        START,
        STOP,
        RECONNECT,
        COMMAND,
        MOVE,
        LOOK,
        SWING,
        JUMP,
        SNEAKING,
        BEHAVIOR_START,
        BEHAVIOR_PAUSE
    }

    record BatchResult(Action action, String selector, int matched, int accepted, List<String> botIds) {
        public BatchResult {
            botIds = botIds == null ? List.of() : List.copyOf(botIds);
            selector = selector == null ? "" : selector;
        }

        public boolean successful() {
            return matched > 0 && accepted == matched;
        }
    }

    record BotView(
        String id,
        String username,
        BotState state,
        String protocolVersion,
        String protocolSource,
        Optional<String> currentServer,
        BotPosition position,
        boolean playable,
        boolean authenticated,
        int reconnectAttempts,
        long onlineSeconds
    ) {
        public BotView {
            currentServer = currentServer == null ? Optional.empty() : currentServer;
            position = position == null ? BotPosition.unknown() : position;
        }
    }

    record ServerSwitchResult(
        Status status,
        String botId,
        String username,
        String server,
        String detail
    ) {
        public boolean successful() {
            return status == Status.SWITCHED || status == Status.ALREADY_CONNECTED;
        }

        public enum Status {
            SWITCHED,
            ALREADY_CONNECTED,
            BOT_NOT_FOUND,
            BOT_NOT_READY,
            AUTHENTICATION_PENDING,
            SERVER_NOT_FOUND,
            FAILED
        }
    }

    record FollowResult(boolean successful, String detail) {
    }

    record CommonBotEvent(Instant at, String botId, String type, String detail) {
    }
}
