package dev.nulli0n.vbot.api.common;

import dev.nulli0n.vbot.Bots4VeloPlugin;
import dev.nulli0n.vbot.bot.BotEvent;
import dev.nulli0n.vbot.bot.BotSession;
import dev.nulli0n.vbot.bot.BotSnapshot;

import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.function.Consumer;
import java.util.function.Function;

/** Core implementation of {@link CommonBotApi}; kept separate from the legacy API. */
public final class CoreCommonBotApi implements CommonBotApi {
    private final Bots4VeloPlugin plugin;
    private final ConcurrentMap<Consumer<CommonBotEvent>, Consumer<BotEvent>> listeners = new ConcurrentHashMap<>();

    public CoreCommonBotApi(Bots4VeloPlugin plugin) {
        this.plugin = plugin;
    }

    @Override
    public List<BotView> bots() {
        return plugin.bots().stream().map(this::viewSnapshot).toList();
    }

    @Override
    public List<BotView> select(String selector) {
        return plugin.selectBots(selector).stream().map(this::view).toList();
    }

    @Override
    public Optional<BotView> bot(String id) {
        return plugin.bot(id).map(this::viewSnapshot);
    }

    @Override
    public BatchResult start(String selector) {
        return apply(selector, Action.START, id -> plugin.start(id));
    }

    @Override
    public BatchResult stop(String selector) {
        return apply(selector, Action.STOP, id -> plugin.stop(id));
    }

    @Override
    public BatchResult reconnect(String selector) {
        return apply(selector, Action.RECONNECT, id -> plugin.reconnect(id));
    }

    @Override
    public CompletionStage<List<ServerSwitchResult>> switchServer(String selector, String server) {
        List<String> ids = selectedIds(selector);
        List<CompletableFuture<ServerSwitchResult>> futures = ids.stream()
            .map(id -> plugin.switchBotServer(id, server).thenApply(CoreCommonBotApi::switchResult))
            .toList();
        CompletableFuture<Void> all = CompletableFuture.allOf(futures.toArray(CompletableFuture[]::new));
        return all.thenApply(ignored -> futures.stream().map(CompletableFuture::join).toList());
    }

    @Override
    public BatchResult command(String selector, String command) {
        return apply(selector, Action.COMMAND, id -> plugin.selectBots(id).stream()
            .findFirst().map(session -> session.sendCommand(command)).orElse(false));
    }

    @Override
    public BatchResult move(String selector, double x, double y, double z) {
        return apply(selector, Action.MOVE, id -> plugin.selectBots(id).stream()
            .findFirst().map(session -> session.moveTo(x, y, z)).orElse(false));
    }

    @Override
    public BatchResult look(String selector, float yaw, float pitch) {
        return apply(selector, Action.LOOK, id -> plugin.selectBots(id).stream()
            .findFirst().map(session -> session.look(yaw, pitch)).orElse(false));
    }

    @Override
    public BatchResult swing(String selector) {
        return apply(selector, Action.SWING, id -> plugin.selectBots(id).stream()
            .findFirst().map(BotSession::swingMainHand).orElse(false));
    }

    @Override
    public BatchResult jump(String selector) {
        return apply(selector, Action.JUMP, id -> plugin.selectBots(id).stream()
            .findFirst().map(BotSession::jump).orElse(false));
    }

    @Override
    public BatchResult setSneaking(String selector, boolean sneaking) {
        return apply(selector, Action.SNEAKING, id -> plugin.selectBots(id).stream()
            .findFirst().map(session -> session.setSneaking(sneaking)).orElse(false));
    }

    @Override
    public BatchResult startBehavior(String selector) {
        return apply(selector, Action.BEHAVIOR_START, id -> plugin.selectBots(id).stream()
            .findFirst().map(session -> {
                session.startBehavior();
                return true;
            }).orElse(false));
    }

    @Override
    public BatchResult pauseBehavior(String selector) {
        return apply(selector, Action.BEHAVIOR_PAUSE, id -> plugin.selectBots(id).stream()
            .findFirst().map(session -> {
                session.pauseBehavior();
                return true;
            }).orElse(false));
    }

    @Override
    public FollowResult follow(String botId, String playerName) {
        Bots4VeloPlugin.FollowResult result = plugin.startFollowing(botId, playerName);
        return new FollowResult(result.successful(), result.detail());
    }

    @Override
    public boolean stopFollowing(String botId) {
        return plugin.stopFollowing(botId);
    }

    @Override
    public void addEventListener(Consumer<CommonBotEvent> listener) {
        if (listener == null) {
            return;
        }
        Consumer<BotEvent> adapter = event -> listener.accept(new CommonBotEvent(
            event.at(), event.botId(), event.type(), event.detail()));
        if (listeners.putIfAbsent(listener, adapter) == null) {
            plugin.addEventListener(adapter);
        }
    }

    @Override
    public void removeEventListener(Consumer<CommonBotEvent> listener) {
        Consumer<BotEvent> adapter = listeners.remove(listener);
        if (adapter != null) {
            plugin.removeEventListener(adapter);
        }
    }

    private BatchResult apply(String selector, Action action, Function<String, Boolean> operation) {
        List<String> ids = selectedIds(selector);
        int accepted = 0;
        for (String id : ids) {
            if (Boolean.TRUE.equals(operation.apply(id))) {
                accepted++;
            }
        }
        return new BatchResult(action, selector, ids.size(), accepted, ids);
    }

    private List<String> selectedIds(String selector) {
        return plugin.selectBots(selector).stream().map(session -> session.definition().id()).toList();
    }

    private BotView view(BotSession session) {
        if (session == null) {
            return null;
        }
        return view(session, session.snapshot());
    }

    private BotView viewSnapshot(BotSnapshot snapshot) {
        return plugin.selectBots(snapshot.id()).stream().findFirst()
            .map(session -> view(session, snapshot)).orElse(null);
    }

    private BotView view(BotSession session, BotSnapshot snapshot) {
        if (session == null || snapshot == null) {
            return null;
        }
        return new BotView(snapshot.id(), snapshot.username(), snapshot.state(), snapshot.protocolVersion(),
            snapshot.protocolSource(), plugin.currentServer(snapshot.id()), snapshot.position(), session.isPlayable(),
            session.isAuthenticationComplete(), snapshot.reconnectAttempts(), snapshot.onlineSeconds());
    }

    private static ServerSwitchResult switchResult(Bots4VeloPlugin.BotServerSwitchResult result) {
        return new ServerSwitchResult(ServerSwitchResult.Status.valueOf(result.status().name()), result.botId(),
            result.username(), result.server(), result.detail());
    }
}
