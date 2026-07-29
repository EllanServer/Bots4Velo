package dev.nulli0n.vbot.paper;

import dev.nulli0n.vbot.backend.protocol.ActualState;
import dev.nulli0n.vbot.backend.protocol.BackendChannel;
import dev.nulli0n.vbot.backend.protocol.BackendCapabilities;
import dev.nulli0n.vbot.backend.protocol.BackendInvulnerability;
import dev.nulli0n.vbot.backend.protocol.BackendOperation;
import dev.nulli0n.vbot.backend.protocol.BackendPolicy;
import dev.nulli0n.vbot.backend.protocol.BackendStatus;
import dev.nulli0n.vbot.backend.protocol.ControlRequest;
import dev.nulli0n.vbot.backend.protocol.ControlResponse;
import dev.nulli0n.vbot.backend.protocol.ProtocolCodec;
import dev.nulli0n.vbot.backend.protocol.ProtocolException;
import dev.nulli0n.vbot.backend.protocol.ReplayWindow;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.player.PlayerChangedWorldEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerRespawnEvent;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.plugin.messaging.PluginMessageListener;

import java.util.Arrays;
import java.util.Collections;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

public final class Bots4VeloPaperPlugin extends JavaPlugin implements PluginMessageListener, Listener {
    private static final long REJECT_LOG_INTERVAL_MILLIS = 10_000L;

    private final BackendPolicyCache policies = new BackendPolicyCache();
    private final PaperPolicyService policyService = new PaperPolicyService();
    private final Set<UUID> recoveries = Collections.newSetFromMap(
        new ConcurrentHashMap<UUID, Boolean>());
    private byte[] secret;
    private ReplayWindow replayWindow;
    private SignedResponseCache signedResponses;
    private int maximumMessageBytes;
    private boolean cancelDamageEvents;
    private boolean logRejectedMessages;
    private volatile long nextRejectedLogAt;

    @Override
    public void onEnable() {
        saveDefaultConfig();
        try {
            String secretEnvironment = getConfig().getString(
                "shared-secret-env", "BOTS4VELO_BACKEND_SECRET");
            String configuredSecret = getConfig().getString("shared-secret", "");
            secret = CompanionSecretResolver.resolve(secretEnvironment, configuredSecret, System::getenv);
            long skewMillis = secondsToMillis("maximum-clock-skew-seconds", 60L);
            long retentionMillis = secondsToMillis("replay-retention-seconds", 300L);
            int maximumEntries = positiveInt("maximum-replay-entries", 4096);
            replayWindow = new ReplayWindow(skewMillis, retentionMillis, maximumEntries);
            signedResponses = new SignedResponseCache(retentionMillis, maximumEntries);
            maximumMessageBytes = positiveInt("maximum-message-bytes", ProtocolCodec.MAX_FRAME_BYTES);
            maximumMessageBytes = Math.min(maximumMessageBytes, ProtocolCodec.MAX_FRAME_BYTES);
            cancelDamageEvents = getConfig().getBoolean("cancel-damage-events", true);
            logRejectedMessages = getConfig().getBoolean("log-rejected-messages", true);
        }
        catch (IllegalArgumentException exception) {
            getLogger().severe("Bots4VeloPaper is disabled: " + exception.getMessage());
            getServer().getPluginManager().disablePlugin(this);
            return;
        }

        getServer().getMessenger().registerIncomingPluginChannel(this, BackendChannel.ID, this);
        getServer().getMessenger().registerOutgoingPluginChannel(this, BackendChannel.ID);
        getServer().getPluginManager().registerEvents(this, this);
        getLogger().info("Listening for authenticated Bots4Velo policies on " + BackendChannel.ID);
    }

    @Override
    public void onDisable() {
        getServer().getMessenger().unregisterIncomingPluginChannel(this, BackendChannel.ID, this);
        getServer().getMessenger().unregisterOutgoingPluginChannel(this, BackendChannel.ID);
        if (secret != null) {
            Arrays.fill(secret, (byte) 0);
            secret = null;
        }
    }

    @Override
    public void onPluginMessageReceived(String channel, Player carrier, byte[] message) {
        if (!BackendChannel.ID.equals(channel) || secret == null) {
            return;
        }
        if (message == null || message.length > maximumMessageBytes) {
            rejected("oversized or empty frame");
            return;
        }
        final byte[] copy = message.clone();
        if (Bukkit.isPrimaryThread()) {
            process(carrier, copy);
        }
        else {
            getServer().getScheduler().runTask(this, () -> process(carrier, copy));
        }
    }

    private void process(Player carrier, byte[] message) {
        final ControlRequest request;
        try {
            request = ProtocolCodec.decodeRequest(message, secret);
        }
        catch (ProtocolException | IllegalArgumentException exception) {
            rejected(exception.getMessage());
            return;
        }

        if (!carrier.getUniqueId().equals(request.targetUuid())) {
            respondUncached(carrier, request, BackendStatus.UNAUTHORIZED,
                "Plugin-message carrier does not match the target UUID", ActualState.absent());
            return;
        }

        long now = System.currentTimeMillis();
        SignedResponseCache.Lookup cached = signedResponses.lookup(request, message, now);
        if (cached.match() == SignedResponseCache.Match.READY) {
            sendRawResponse(carrier, cached.response());
            return;
        }
        if (cached.match() == SignedResponseCache.Match.PENDING) {
            return;
        }
        if (cached.match() == SignedResponseCache.Match.CONFLICT) {
            respondUncached(carrier, request, BackendStatus.REPLAYED,
                "Request ID conflicts with a previously processed request", ActualState.absent());
            return;
        }

        BackendStatus replayStatus = replayWindow.validate(request, now);
        if (replayStatus != BackendStatus.OK) {
            respondUncached(carrier, request, replayStatus,
                replayStatus == BackendStatus.EXPIRED ? "Request is outside the allowed clock window"
                    : "Request nonce has already been used",
                ActualState.absent());
            return;
        }
        if (!signedResponses.begin(request, message, now)) {
            respondUncached(carrier, request, BackendStatus.REPLAYED,
                "Request ID conflicts with a previously processed request", ActualState.absent());
            return;
        }

        switch (request.operation()) {
            case PROBE:
            case PROBE_EXT:
                probe(carrier, request);
                break;
            case APPLY_POLICY:
            case APPLY_POLICY_EXT:
                apply(carrier, request);
                break;
            case RESPAWN:
                respawn(carrier, request);
                break;
            case RECOVER:
                recover(carrier, request);
                break;
            default:
                respondProcessed(carrier, request, BackendStatus.UNSUPPORTED,
                    "Unsupported backend operation", safeActualState(carrier));
                break;
        }
    }

    private void probe(Player carrier, ControlRequest request) {
        try {
            respondProcessed(carrier, request, BackendStatus.OK,
                "Bots4VeloPaper protocol " + ProtocolCodec.VERSION + "; "
                    + BackendCapabilities.ADVERTISEMENT + "; collidable=best-effort",
                policyService.actualState(carrier));
        }
        catch (PolicyApplyException exception) {
            respondProcessed(carrier, request, exception.status(), exception.getMessage(), ActualState.absent());
        }
    }

    private void apply(Player carrier, ControlRequest request) {
        try {
            BackendPolicy effective = policyService.apply(carrier, request.policy());
            policies.put(carrier.getUniqueId(), effective);
            respondProcessed(carrier, request, BackendStatus.OK,
                request.operation() == BackendOperation.APPLY_POLICY_EXT
                    ? "Extended policy applied; collidable is best-effort" : "Policy applied",
                policyService.actualState(carrier));
        }
        catch (PolicyApplyException | RuntimeException exception) {
            BackendStatus status = exception instanceof PolicyApplyException
                ? ((PolicyApplyException) exception).status() : BackendStatus.APPLY_FAILED;
            respondProcessed(carrier, request, status, safeMessage(exception), safeActualState(carrier));
        }
    }

    private void respawn(Player carrier, ControlRequest request) {
        if (!carrier.isDead()) {
            respondProcessed(carrier, request, BackendStatus.OK, "Player is already alive", safeActualState(carrier));
            return;
        }
        try {
            carrier.spigot().respawn();
        }
        catch (RuntimeException exception) {
            respondProcessed(carrier, request, BackendStatus.APPLY_FAILED,
                safeMessage(exception), safeActualState(carrier));
            return;
        }
        getServer().getScheduler().runTaskLater(this, () -> {
            if (!carrier.isOnline()) {
                return;
            }
            BackendStatus status = replayCachedPolicy(carrier);
            respondProcessed(carrier, request, status,
                status == BackendStatus.OK ? "Respawn completed" : "Respawn completed but policy replay failed",
                safeActualState(carrier));
        }, 1L);
    }

    private void recover(Player carrier, ControlRequest request) {
        if (!carrier.isDead()) {
            completeRecovery(carrier, request);
            return;
        }
        UUID playerId = carrier.getUniqueId();
        if (!recoveries.add(playerId)) {
            respondProcessed(carrier, request, BackendStatus.APPLY_FAILED,
                "Recovery is already in progress", safeActualState(carrier));
            return;
        }
        try {
            carrier.spigot().respawn();
        }
        catch (RuntimeException exception) {
            recoveries.remove(playerId);
            respondProcessed(carrier, request, BackendStatus.APPLY_FAILED,
                safeMessage(exception), safeActualState(carrier));
            return;
        }
        getServer().getScheduler().runTaskLater(this, () -> {
            try {
                if (!carrier.isOnline()) {
                    respondProcessed(carrier, request, BackendStatus.BOT_NOT_ON_SERVER,
                        "Player disconnected during recovery", ActualState.absent());
                    return;
                }
                completeRecovery(carrier, request);
            }
            finally {
                recoveries.remove(playerId);
            }
        }, 1L);
    }

    private void completeRecovery(Player carrier, ControlRequest request) {
        if (carrier.isDead()) {
            respondProcessed(carrier, request, BackendStatus.APPLY_FAILED,
                "Player is still dead after the respawn attempt", safeActualState(carrier));
            return;
        }
        try {
            policyService.recover(carrier);
            BackendStatus replayStatus = replayCachedPolicy(carrier);
            respondProcessed(carrier, request, replayStatus,
                replayStatus == BackendStatus.OK
                    ? "Recovery completed and cached policy replayed"
                    : "Recovery completed but cached policy replay failed",
                safeActualState(carrier));
        }
        catch (PolicyApplyException | RuntimeException exception) {
            BackendStatus status = exception instanceof PolicyApplyException
                ? ((PolicyApplyException) exception).status() : BackendStatus.APPLY_FAILED;
            respondProcessed(carrier, request, status, safeMessage(exception), safeActualState(carrier));
        }
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onJoin(PlayerJoinEvent event) {
        scheduleReplay(event.getPlayer());
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onRespawn(PlayerRespawnEvent event) {
        if (!recoveries.contains(event.getPlayer().getUniqueId())) {
            scheduleReplay(event.getPlayer());
        }
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onWorldChange(PlayerChangedWorldEvent event) {
        scheduleReplay(event.getPlayer());
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onQuit(PlayerQuitEvent event) {
        policies.remove(event.getPlayer().getUniqueId());
        recoveries.remove(event.getPlayer().getUniqueId());
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = false)
    public void onDamage(EntityDamageEvent event) {
        if (!cancelDamageEvents || !(event.getEntity() instanceof Player)) {
            return;
        }
        Player player = (Player) event.getEntity();
        policies.get(player.getUniqueId()).ifPresent(policy -> {
            if (policy.invulnerability() == BackendInvulnerability.ENABLED) {
                event.setCancelled(true);
            }
        });
    }

    private void scheduleReplay(Player player) {
        getServer().getScheduler().runTaskLater(this, () -> {
            if (player.isOnline()) {
                replayCachedPolicy(player);
            }
        }, 1L);
    }

    private BackendStatus replayCachedPolicy(Player player) {
        BackendPolicy cached = policies.get(player.getUniqueId()).orElse(null);
        if (cached == null) {
            return BackendStatus.OK;
        }
        try {
            policies.put(player.getUniqueId(), policyService.apply(player, cached));
            return BackendStatus.OK;
        }
        catch (PolicyApplyException | RuntimeException exception) {
            getLogger().warning("Could not replay the cached policy for " + player.getName()
                + ": " + safeMessage(exception));
            return exception instanceof PolicyApplyException
                ? ((PolicyApplyException) exception).status() : BackendStatus.APPLY_FAILED;
        }
    }

    private ActualState safeActualState(Player player) {
        try {
            return policyService.actualState(player);
        }
        catch (PolicyApplyException | RuntimeException ignored) {
            return ActualState.absent();
        }
    }

    private void respondProcessed(Player carrier, ControlRequest request, BackendStatus status, String detail,
                                  ActualState actualState) {
        byte[] frame = responseFrame(request, status, detail, actualState);
        if (frame == null) {
            return;
        }
        signedResponses.complete(request, frame, System.currentTimeMillis());
        sendRawResponse(carrier, frame);
    }

    private void respondUncached(Player carrier, ControlRequest request, BackendStatus status, String detail,
                                 ActualState actualState) {
        byte[] frame = responseFrame(request, status, detail, actualState);
        if (frame != null) {
            sendRawResponse(carrier, frame);
        }
    }

    private byte[] responseFrame(ControlRequest request, BackendStatus status, String detail,
                                 ActualState actualState) {
        if (secret == null) {
            return null;
        }
        String safeDetail = detail == null ? "" : detail;
        if (safeDetail.length() > 512) {
            safeDetail = safeDetail.substring(0, 512);
        }
        ControlResponse response = ControlResponse.reply(request, status, safeDetail, actualState);
        return ProtocolCodec.encodeResponse(response, secret);
    }

    private void sendRawResponse(Player carrier, byte[] frame) {
        if (carrier.isOnline() && secret != null && frame != null) {
            carrier.sendPluginMessage(this, BackendChannel.ID, frame);
        }
    }

    private void rejected(String reason) {
        if (!logRejectedMessages) {
            return;
        }
        long now = System.currentTimeMillis();
        if (now < nextRejectedLogAt) {
            return;
        }
        nextRejectedLogAt = now + REJECT_LOG_INTERVAL_MILLIS;
        getLogger().warning("Rejected an unauthenticated backend-control message: "
            + (reason == null ? "invalid frame" : reason));
    }

    private long secondsToMillis(String path, long fallback) {
        long seconds = getConfig().getLong(path, fallback);
        if (seconds < 1 || seconds > Long.MAX_VALUE / 1000L) {
            throw new IllegalArgumentException(path + " must be a positive duration");
        }
        return seconds * 1000L;
    }

    private int positiveInt(String path, int fallback) {
        int value = getConfig().getInt(path, fallback);
        if (value < 1) {
            throw new IllegalArgumentException(path + " must be positive");
        }
        return value;
    }

    private String safeMessage(Throwable throwable) {
        String message = throwable.getMessage();
        return message == null || message.trim().isEmpty()
            ? throwable.getClass().getSimpleName() : message;
    }
}
