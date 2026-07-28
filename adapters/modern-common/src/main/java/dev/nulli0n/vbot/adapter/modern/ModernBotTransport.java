package dev.nulli0n.vbot.adapter.modern;

import dev.nulli0n.vbot.transport.AuthenticationUiChallenge;
import dev.nulli0n.vbot.transport.AuthenticationUiType;
import dev.nulli0n.vbot.transport.BotPosition;
import dev.nulli0n.vbot.transport.BotTransport;
import dev.nulli0n.vbot.transport.TransportConfig;
import dev.nulli0n.vbot.transport.TransportListener;
import dev.nulli0n.vbot.transport.TransportState;
import net.kyori.adventure.key.Key;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.cloudburstmc.math.vector.Vector3d;
import org.cloudburstmc.nbt.NbtMap;
import org.geysermc.mcprotocollib.auth.GameProfile;
import org.geysermc.mcprotocollib.network.ClientSession;
import org.geysermc.mcprotocollib.network.Session;
import org.geysermc.mcprotocollib.network.event.session.ConnectedEvent;
import org.geysermc.mcprotocollib.network.event.session.DisconnectedEvent;
import org.geysermc.mcprotocollib.network.event.session.SessionAdapter;
import org.geysermc.mcprotocollib.network.factory.ClientNetworkSessionFactory;
import org.geysermc.mcprotocollib.network.packet.Packet;
import org.geysermc.mcprotocollib.protocol.MinecraftConstants;
import org.geysermc.mcprotocollib.protocol.MinecraftProtocol;
import org.geysermc.mcprotocollib.protocol.data.game.ClientCommand;
import org.geysermc.mcprotocollib.protocol.data.game.ResourcePackStatus;
import org.geysermc.mcprotocollib.protocol.data.game.entity.player.HandPreference;
import org.geysermc.mcprotocollib.protocol.data.game.entity.player.Hand;
import org.geysermc.mcprotocollib.protocol.data.game.entity.player.PositionElement;
import org.geysermc.mcprotocollib.protocol.data.game.setting.ChatVisibility;
import org.geysermc.mcprotocollib.protocol.data.game.setting.ParticleStatus;
import org.geysermc.mcprotocollib.protocol.data.game.setting.SkinPart;
import org.geysermc.mcprotocollib.protocol.packet.common.clientbound.ClientboundResourcePackPushPacket;
import org.geysermc.mcprotocollib.protocol.packet.common.serverbound.ServerboundCustomClickActionPacket;
import org.geysermc.mcprotocollib.protocol.packet.common.serverbound.ServerboundClientInformationPacket;
import org.geysermc.mcprotocollib.protocol.packet.common.serverbound.ServerboundResourcePackPacket;
import org.geysermc.mcprotocollib.protocol.packet.configuration.clientbound.ClientboundFinishConfigurationPacket;
import org.geysermc.mcprotocollib.protocol.packet.configuration.clientbound.ClientboundShowDialogConfigurationPacket;
import org.geysermc.mcprotocollib.protocol.packet.cookie.clientbound.ClientboundCookieRequestPacket;
import org.geysermc.mcprotocollib.protocol.packet.cookie.serverbound.ServerboundCookieResponsePacket;
import org.geysermc.mcprotocollib.protocol.packet.ingame.clientbound.ClientboundLoginPacket;
import org.geysermc.mcprotocollib.protocol.packet.ingame.clientbound.ClientboundShowDialogGamePacket;
import org.geysermc.mcprotocollib.protocol.packet.ingame.clientbound.ClientboundStartConfigurationPacket;
import org.geysermc.mcprotocollib.protocol.packet.ingame.clientbound.ClientboundSystemChatPacket;
import org.geysermc.mcprotocollib.protocol.packet.ingame.clientbound.entity.player.ClientboundPlayerPositionPacket;
import org.geysermc.mcprotocollib.protocol.packet.ingame.clientbound.entity.player.ClientboundSetHealthPacket;
import org.geysermc.mcprotocollib.protocol.packet.ingame.clientbound.title.ClientboundSetSubtitleTextPacket;
import org.geysermc.mcprotocollib.protocol.packet.ingame.clientbound.title.ClientboundSetTitleTextPacket;
import org.geysermc.mcprotocollib.protocol.packet.ingame.serverbound.ServerboundChatCommandPacket;
import org.geysermc.mcprotocollib.protocol.packet.ingame.serverbound.ServerboundClientCommandPacket;
import org.geysermc.mcprotocollib.protocol.packet.ingame.serverbound.level.ServerboundAcceptTeleportationPacket;
import org.geysermc.mcprotocollib.protocol.packet.ingame.serverbound.player.ServerboundMovePlayerPosRotPacket;
import org.geysermc.mcprotocollib.protocol.packet.ingame.serverbound.player.ServerboundSwingPacket;
import org.geysermc.mcprotocollib.protocol.packet.login.clientbound.ClientboundLoginFinishedPacket;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

final class ModernBotTransport implements BotTransport {
    private static final PlainTextComponentSerializer PLAIN = PlainTextComponentSerializer.plainText();
    private static final String AUTHME_LOGIN_ACTION = "authme:prejoin-login/submit";
    private static final String AUTHME_REGISTER_ACTION = "authme:prejoin-register/submit";

    private final TransportConfig config;
    private final TransportListener listener;
    private final ScheduledExecutorService executor;
    private final AtomicReference<TransportState> state = new AtomicReference<>(TransportState.LOGIN);
    private final AtomicBoolean respawnPending = new AtomicBoolean();
    private final AtomicBoolean enteredPlay = new AtomicBoolean();
    private final AtomicBoolean positionKnown = new AtomicBoolean();
    private final Object movementLock = new Object();
    private volatile ClientSession session;
    private volatile double x;
    private volatile double y;
    private volatile double z;
    private volatile float yaw;
    private volatile float pitch;

    ModernBotTransport(TransportConfig config, TransportListener listener, ScheduledExecutorService executor) {
        this.config = config;
        this.listener = listener;
        this.executor = executor;
    }

    @Override
    public void connect() {
        MinecraftProtocol protocol = new MinecraftProtocol(new GameProfile(config.uuid(), config.username()), null);
        ClientSession created = ClientNetworkSessionFactory.factory()
            .setAddress(config.address(), config.port())
            .setProtocol(protocol)
            .setPacketHandlerExecutor(executor)
            .create();
        created.setFlag(MinecraftConstants.CLIENT_HOST, config.virtualHost());
        created.setFlag(MinecraftConstants.CLIENT_PORT, config.virtualPort());
        created.setFlag(MinecraftConstants.AUTOMATIC_KEEP_ALIVE_MANAGEMENT, true);
        created.setFlag(MinecraftConstants.SEND_BLANK_KNOWN_PACKS_RESPONSE, true);
        created.addListener(new PacketListener());
        session = created;
        created.connect(false);
    }

    @Override
    public void disconnect(String reason) {
        ClientSession active = session;
        session = null;
        if (active != null && active.isConnected()) {
            active.disconnect(reason);
        }
    }

    @Override
    public boolean isConnected() {
        ClientSession active = session;
        return active != null && active.isConnected();
    }

    @Override
    public boolean sendCommand(String command) {
        ClientSession active = session;
        if (active == null || !active.isConnected() || state.get() != TransportState.PLAY) {
            return false;
        }
        active.send(new ServerboundChatCommandPacket(command));
        return true;
    }

    @Override
    public boolean submitAuthenticationUi(AuthenticationUiChallenge challenge, String password) {
        ClientSession active = session;
        if (active == null || !active.isConnected() || state.get() != TransportState.CONFIGURATION) {
            return false;
        }
        String safePassword = password == null ? "" : password;
        var payload = NbtMap.builder().putString(challenge.passwordInput(), safePassword);
        if (challenge.type() == AuthenticationUiType.REGISTER
            && challenge.confirmationInput() != null && !challenge.confirmationInput().isBlank()) {
            payload.putString(challenge.confirmationInput(), safePassword);
        }
        active.send(new ServerboundCustomClickActionPacket(Key.key(challenge.actionId()), payload.build()));
        listener.onDiagnostic("Submitted " + challenge.description());
        return true;
    }

    @Override
    public boolean moveTo(double newX, double newY, double newZ) {
        if (!Double.isFinite(newX) || !Double.isFinite(newY) || !Double.isFinite(newZ)) {
            return false;
        }
        synchronized (movementLock) {
            ClientSession active = playableSession();
            if (active == null || !positionKnown.get()) {
                return false;
            }
            x = newX;
            y = newY;
            z = newZ;
            active.send(new ServerboundMovePlayerPosRotPacket(false, false, x, y, z, yaw, pitch));
            return true;
        }
    }

    @Override
    public boolean look(float newYaw, float newPitch) {
        if (!Float.isFinite(newYaw) || !Float.isFinite(newPitch) || newPitch < -90.0F || newPitch > 90.0F) {
            return false;
        }
        synchronized (movementLock) {
            ClientSession active = playableSession();
            if (active == null || !positionKnown.get()) {
                return false;
            }
            yaw = newYaw;
            pitch = newPitch;
            active.send(new ServerboundMovePlayerPosRotPacket(false, false, x, y, z, yaw, pitch));
            return true;
        }
    }

    @Override
    public boolean swingMainHand() {
        ClientSession active = playableSession();
        if (active == null) {
            return false;
        }
        active.send(new ServerboundSwingPacket(Hand.MAIN_HAND));
        return true;
    }

    @Override
    public boolean jump() {
        synchronized (movementLock) {
            ClientSession active = playableSession();
            if (active == null || !positionKnown.get()) {
                return false;
            }
            y += 0.42D;
            active.send(new ServerboundMovePlayerPosRotPacket(false, false, x, y, z, yaw, pitch));
            return true;
        }
    }

    @Override
    public BotPosition position() {
        synchronized (movementLock) {
            return positionKnown.get() ? BotPosition.known(x, y, z, yaw, pitch) : BotPosition.unknown();
        }
    }

    private ClientSession playableSession() {
        ClientSession active = session;
        return active != null && active.isConnected() && state.get() == TransportState.PLAY ? active : null;
    }

    private void onPacket(Session source, Packet packet) {
        if (packet instanceof ClientboundLoginFinishedPacket) {
            changeState(TransportState.CONFIGURATION);
            source.send(clientInformation());
        }
        else if (packet instanceof ClientboundStartConfigurationPacket) {
            changeState(TransportState.CONFIGURATION);
        }
        else if (packet instanceof ClientboundFinishConfigurationPacket) {
            source.send(clientInformation());
            if (enteredPlay.get()) {
                changeState(TransportState.PLAY);
            }
        }
        else if (packet instanceof ClientboundLoginPacket) {
            enteredPlay.set(true);
            changeState(TransportState.PLAY);
        }
        else if (packet instanceof ClientboundCookieRequestPacket cookie) {
            source.send(new ServerboundCookieResponsePacket(cookie.getKey(), null));
        }
        else if (packet instanceof ClientboundShowDialogConfigurationPacket dialog) {
            handleConfigurationDialog(dialog.getDialog());
        }
        else if (packet instanceof ClientboundShowDialogGamePacket dialog && dialog.getDialog().isCustom()) {
            // Post-join AuthMe dialogs use a command-template action. Forwarding
            // the NBT text lets the normal /login and /register prompt matching
            // respond without coupling the transport to Paper internals.
            listener.onSystemMessage(dialog.getDialog().custom().toString());
        }
        else if (packet instanceof ClientboundResourcePackPushPacket resourcePack) {
            handleResourcePack(source, resourcePack);
        }
        else if (packet instanceof ClientboundPlayerPositionPacket position) {
            acknowledgeTeleport(source, position);
        }
        else if (packet instanceof ClientboundSetHealthPacket health
            && health.getHealth() <= 0.0F && config.autoRespawn()) {
            scheduleRespawn(source);
        }
        else if (packet instanceof ClientboundSystemChatPacket chat && !chat.isOverlay()) {
            listener.onSystemMessage(PLAIN.serialize(chat.getContent()));
        }
        else if (packet instanceof ClientboundSetTitleTextPacket title) {
            listener.onSystemMessage(PLAIN.serialize(title.getText()));
        }
        else if (packet instanceof ClientboundSetSubtitleTextPacket subtitle) {
            listener.onSystemMessage(PLAIN.serialize(subtitle.getText()));
        }
    }

    private void handleConfigurationDialog(NbtMap dialog) {
        AuthenticationUiChallenge challenge = parseAuthenticationUi(dialog);
        if (challenge != null) {
            listener.onAuthenticationUi(challenge);
        }
        else {
            listener.onDiagnostic("Ignored a configuration dialog without a recognizable password action");
        }
    }

    static AuthenticationUiChallenge parseAuthenticationUi(NbtMap dialog) {
        List<String> tokens = new ArrayList<>();
        collectDialogStrings(dialog, tokens);
        String combined = String.join("\n", tokens).toLowerCase(Locale.ROOT);
        AuthenticationUiType type;
        if (combined.contains("register") || combined.contains("sign_up") || combined.contains("signup")) {
            type = AuthenticationUiType.REGISTER;
        }
        else if (combined.contains("login") || combined.contains("log_in") || combined.contains("signin")) {
            type = AuthenticationUiType.LOGIN;
        }
        else {
            return null;
        }

        String action = tokens.stream()
            .filter(ModernBotTransport::isPossibleActionId)
            .max(Comparator.comparingInt(value -> actionScore(value, type)))
            .filter(value -> actionScore(value, type) > 0)
            .orElse(type == AuthenticationUiType.REGISTER ? AUTHME_REGISTER_ACTION : AUTHME_LOGIN_ACTION);
        String passwordInput = findInput(tokens, false);
        String confirmationInput = type == AuthenticationUiType.REGISTER ? findInput(tokens, true) : null;
        return new AuthenticationUiChallenge(
            type,
            action,
            passwordInput == null ? "password" : passwordInput,
            type == AuthenticationUiType.REGISTER
                ? (confirmationInput == null ? "confirm" : confirmationInput)
                : null
        );
    }

    private static void collectDialogStrings(Object value, List<String> target) {
        if (value instanceof String text) {
            target.add(text);
        }
        else if (value instanceof Map<?, ?> map) {
            for (Map.Entry<?, ?> entry : map.entrySet()) {
                if (entry.getKey() instanceof String key) {
                    target.add(key);
                }
                collectDialogStrings(entry.getValue(), target);
            }
        }
        else if (value instanceof Collection<?> collection) {
            collection.forEach(entry -> collectDialogStrings(entry, target));
        }
    }

    private static boolean isPossibleActionId(String value) {
        String lower = value.toLowerCase(Locale.ROOT);
        return lower.matches("[a-z0-9_.-]+:[a-z0-9_./-]+")
            && (lower.contains("submit") || lower.contains("auth") || lower.contains("login")
                || lower.contains("register") || lower.contains("signin") || lower.contains("signup"));
    }

    private static int actionScore(String value, AuthenticationUiType type) {
        String lower = value.toLowerCase(Locale.ROOT);
        String typeWord = type == AuthenticationUiType.REGISTER ? "register" : "login";
        int score = lower.contains("submit") ? 8 : 0;
        score += lower.contains(typeWord) ? 4 : 0;
        score += lower.contains("auth") ? 2 : 0;
        return score;
    }

    private static String findInput(List<String> tokens, boolean confirmation) {
        return tokens.stream()
            .filter(value -> value.equals(value.toLowerCase(Locale.ROOT)))
            .filter(value -> value.matches("[a-z0-9_.-]{1,64}"))
            .filter(value -> inputScore(value, confirmation) > 0)
            .max(Comparator.comparingInt(value -> inputScore(value, confirmation)))
            .orElse(null);
    }

    private static int inputScore(String value, boolean confirmation) {
        String lower = value.toLowerCase(Locale.ROOT);
        if (confirmation) {
            if (lower.equals("confirm") || lower.equals("password_confirmation")) {
                return 10;
            }
            return lower.contains("confirm") || lower.contains("repeat") ? 5 : 0;
        }
        if (lower.equals("password")) {
            return 10;
        }
        return (lower.contains("password") || lower.equals("pass"))
            && !lower.contains("confirm") && !lower.contains("repeat") ? 5 : 0;
    }

    private void changeState(TransportState replacement) {
        state.set(replacement);
        listener.onStateChanged(replacement);
    }

    private ServerboundClientInformationPacket clientInformation() {
        return new ServerboundClientInformationPacket(
            "en_us", config.renderDistance(), ChatVisibility.FULL, true,
            List.of(SkinPart.values()), HandPreference.RIGHT_HAND, false, true, ParticleStatus.MINIMAL
        );
    }

    private void handleResourcePack(Session source, ClientboundResourcePackPushPacket resourcePack) {
        UUID id = resourcePack.getId();
        listener.onResourcePackStatus("OFFER_RECEIVED id=" + id);
        if (!config.acceptResourcePacksWithoutDownload()) {
            source.send(new ServerboundResourcePackPacket(id, ResourcePackStatus.DECLINED));
            listener.onResourcePackStatus("DECLINED id=" + id);
            return;
        }
        source.send(new ServerboundResourcePackPacket(id, ResourcePackStatus.ACCEPTED));
        listener.onResourcePackStatus("ACCEPTED id=" + id);
        executor.schedule(() -> sendResourceStatus(source, id, ResourcePackStatus.DOWNLOADED),
            config.resourcePackStepDelayMillis(), TimeUnit.MILLISECONDS);
        executor.schedule(() -> sendResourceStatus(source, id, ResourcePackStatus.SUCCESSFULLY_LOADED),
            config.resourcePackStepDelayMillis() * 2, TimeUnit.MILLISECONDS);
    }

    private void sendResourceStatus(Session source, UUID id, ResourcePackStatus status) {
        if (source.isConnected()) {
            source.send(new ServerboundResourcePackPacket(id, status));
            listener.onResourcePackStatus(status + " id=" + id);
        }
    }

    private void acknowledgeTeleport(Session source, ClientboundPlayerPositionPacket packet) {
        synchronized (movementLock) {
            Vector3d position = packet.getPosition();
            List<PositionElement> relative = packet.getRelatives();
            x = relative.contains(PositionElement.X) ? x + position.getX() : position.getX();
            y = relative.contains(PositionElement.Y) ? y + position.getY() : position.getY();
            z = relative.contains(PositionElement.Z) ? z + position.getZ() : position.getZ();
            yaw = relative.contains(PositionElement.Y_ROT) ? yaw + packet.getYRot() : packet.getYRot();
            pitch = relative.contains(PositionElement.X_ROT) ? pitch + packet.getXRot() : packet.getXRot();
            positionKnown.set(true);
            source.send(new ServerboundAcceptTeleportationPacket(packet.getId()));
            source.send(new ServerboundMovePlayerPosRotPacket(false, false, x, y, z, yaw, pitch));
        }
    }

    private void scheduleRespawn(Session source) {
        if (!respawnPending.compareAndSet(false, true)) {
            return;
        }
        executor.schedule(() -> {
            if (source.isConnected() && state.get() == TransportState.PLAY) {
                // The enum constant was renamed from RESPAWN to PERFORM_RESPAWN
                // in 26.1, but the wire value remains the first entry.
                source.send(new ServerboundClientCommandPacket(ClientCommand.values()[0]));
            }
            respawnPending.set(false);
        }, 1, TimeUnit.SECONDS);
    }

    private final class PacketListener extends SessionAdapter {
        @Override
        public void connected(ConnectedEvent event) {
            changeState(TransportState.LOGIN);
        }

        @Override
        public void packetReceived(Session session, Packet packet) {
            onPacket(session, packet);
        }

        @Override
        public void disconnected(DisconnectedEvent event) {
            session = null;
            listener.onDisconnected(PLAIN.serialize(event.getReason()), event.getCause());
        }
    }
}
