package dev.nulli0n.vbot.adapter.legacy;

import com.github.steveice10.mc.auth.data.GameProfile;
import com.github.steveice10.mc.protocol.MinecraftConstants;
import com.github.steveice10.mc.protocol.MinecraftProtocol;
import com.github.steveice10.mc.protocol.data.game.ClientRequest;
import com.github.steveice10.mc.protocol.data.game.MessageType;
import com.github.steveice10.mc.protocol.data.game.ResourcePackStatus;
import com.github.steveice10.mc.protocol.data.game.entity.player.HandPreference;
import com.github.steveice10.mc.protocol.data.game.entity.player.Hand;
import com.github.steveice10.mc.protocol.data.game.entity.player.PositionElement;
import com.github.steveice10.mc.protocol.data.game.setting.ChatVisibility;
import com.github.steveice10.mc.protocol.data.game.setting.SkinPart;
import com.github.steveice10.mc.protocol.packet.ingame.client.ClientChatPacket;
import com.github.steveice10.mc.protocol.packet.ingame.client.ClientRequestPacket;
import com.github.steveice10.mc.protocol.packet.ingame.client.ClientResourcePackStatusPacket;
import com.github.steveice10.mc.protocol.packet.ingame.client.ClientSettingsPacket;
import com.github.steveice10.mc.protocol.packet.ingame.client.player.ClientPlayerPositionRotationPacket;
import com.github.steveice10.mc.protocol.packet.ingame.client.player.ClientPlayerSwingArmPacket;
import com.github.steveice10.mc.protocol.packet.ingame.client.world.ClientTeleportConfirmPacket;
import com.github.steveice10.mc.protocol.packet.ingame.server.ServerChatPacket;
import com.github.steveice10.mc.protocol.packet.ingame.server.ServerJoinGamePacket;
import com.github.steveice10.mc.protocol.packet.ingame.server.ServerResourcePackSendPacket;
import com.github.steveice10.mc.protocol.packet.ingame.server.entity.player.ServerPlayerHealthPacket;
import com.github.steveice10.mc.protocol.packet.ingame.server.entity.player.ServerPlayerPositionRotationPacket;
import com.github.steveice10.packetlib.Session;
import com.github.steveice10.packetlib.event.session.ConnectedEvent;
import com.github.steveice10.packetlib.event.session.DisconnectedEvent;
import com.github.steveice10.packetlib.event.session.PacketReceivedEvent;
import com.github.steveice10.packetlib.event.session.SessionAdapter;
import com.github.steveice10.packetlib.tcp.TcpClientSession;
import dev.nulli0n.vbot.transport.BotTransport;
import dev.nulli0n.vbot.transport.BotPosition;
import dev.nulli0n.vbot.transport.TransportConfig;
import dev.nulli0n.vbot.transport.TransportListener;
import dev.nulli0n.vbot.transport.TransportState;
import net.kyori.adventure.text.serializer.plain.PlainComponentSerializer;

import java.util.List;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

final class LegacyBotTransport implements BotTransport {
    private static final PlainComponentSerializer PLAIN = PlainComponentSerializer.plain();

    private final TransportConfig config;
    private final TransportListener listener;
    private final ScheduledExecutorService executor;
    private final AtomicReference<TransportState> state = new AtomicReference<>(TransportState.LOGIN);
    private final AtomicBoolean respawnPending = new AtomicBoolean();
    private final AtomicBoolean positionKnown = new AtomicBoolean();
    private final Object movementLock = new Object();
    private volatile Session session;
    private volatile double x;
    private volatile double y;
    private volatile double z;
    private volatile float yaw;
    private volatile float pitch;

    LegacyBotTransport(TransportConfig config, TransportListener listener, ScheduledExecutorService executor) {
        this.config = config;
        this.listener = listener;
        this.executor = executor;
    }

    @Override
    public void connect() {
        MinecraftProtocol protocol = new MinecraftProtocol(new GameProfile(config.uuid(), config.username()), null);
        Session created = new VirtualHostSession(config.address(), config.port(), protocol,
            config.virtualHost(), config.virtualPort());
        created.setFlag(MinecraftConstants.AUTOMATIC_KEEP_ALIVE_MANAGEMENT, true);
        created.addListener(new PacketListener());
        session = created;
        created.connect(false);
    }

    @Override
    public void disconnect(String reason) {
        Session active = session;
        session = null;
        if (active != null && active.isConnected()) {
            active.disconnect(reason);
        }
    }

    @Override
    public boolean isConnected() {
        Session active = session;
        return active != null && active.isConnected();
    }

    @Override
    public boolean sendCommand(String command) {
        Session active = session;
        if (active == null || !active.isConnected() || state.get() != TransportState.PLAY) {
            return false;
        }
        active.send(new ClientChatPacket("/" + command));
        return true;
    }

    @Override
    public boolean moveTo(double newX, double newY, double newZ) {
        if (!Double.isFinite(newX) || !Double.isFinite(newY) || !Double.isFinite(newZ)) {
            return false;
        }
        synchronized (movementLock) {
            Session active = playableSession();
            if (active == null || !positionKnown.get()) {
                return false;
            }
            x = newX;
            y = newY;
            z = newZ;
            active.send(new ClientPlayerPositionRotationPacket(false, x, y, z, yaw, pitch));
            return true;
        }
    }

    @Override
    public boolean look(float newYaw, float newPitch) {
        if (!Float.isFinite(newYaw) || !Float.isFinite(newPitch) || newPitch < -90.0F || newPitch > 90.0F) {
            return false;
        }
        synchronized (movementLock) {
            Session active = playableSession();
            if (active == null || !positionKnown.get()) {
                return false;
            }
            yaw = newYaw;
            pitch = newPitch;
            active.send(new ClientPlayerPositionRotationPacket(false, x, y, z, yaw, pitch));
            return true;
        }
    }

    @Override
    public boolean swingMainHand() {
        Session active = playableSession();
        if (active == null) {
            return false;
        }
        active.send(new ClientPlayerSwingArmPacket(Hand.MAIN_HAND));
        return true;
    }

    @Override
    public boolean jump() {
        synchronized (movementLock) {
            Session active = playableSession();
            if (active == null || !positionKnown.get()) {
                return false;
            }
            y += 0.42D;
            active.send(new ClientPlayerPositionRotationPacket(false, x, y, z, yaw, pitch));
            return true;
        }
    }

    @Override
    public BotPosition position() {
        synchronized (movementLock) {
            return positionKnown.get() ? BotPosition.known(x, y, z, yaw, pitch) : BotPosition.unknown();
        }
    }

    private Session playableSession() {
        Session active = session;
        return active != null && active.isConnected() && state.get() == TransportState.PLAY ? active : null;
    }

    private void onPacket(PacketReceivedEvent event) {
        if (event.getPacket() instanceof ServerJoinGamePacket) {
            changeState(TransportState.PLAY);
            event.getSession().send(new ClientSettingsPacket(
                "en_us", config.renderDistance(), ChatVisibility.FULL, true,
                List.of(SkinPart.values()), HandPreference.RIGHT_HAND
            ));
        }
        else if (event.getPacket() instanceof ServerResourcePackSendPacket) {
            handleResourcePack(event.getSession());
        }
        else if (event.getPacket() instanceof ServerPlayerPositionRotationPacket position) {
            acknowledgeTeleport(event.getSession(), position);
        }
        else if (event.getPacket() instanceof ServerPlayerHealthPacket health
            && health.getHealth() <= 0.0F && config.autoRespawn()) {
            scheduleRespawn(event.getSession());
        }
        else if (event.getPacket() instanceof ServerChatPacket chat
            && chat.getType() != MessageType.NOTIFICATION) {
            listener.onSystemMessage(PLAIN.serialize(chat.getMessage()));
        }
    }

    private void changeState(TransportState replacement) {
        state.set(replacement);
        listener.onStateChanged(replacement);
    }

    private void handleResourcePack(Session source) {
        listener.onResourcePackStatus("OFFER_RECEIVED");
        if (!config.acceptResourcePacksWithoutDownload()) {
            source.send(new ClientResourcePackStatusPacket(ResourcePackStatus.DECLINED));
            listener.onResourcePackStatus("DECLINED");
            return;
        }
        source.send(new ClientResourcePackStatusPacket(ResourcePackStatus.ACCEPTED));
        listener.onResourcePackStatus("ACCEPTED");
        executor.schedule(() -> {
            if (source.isConnected()) {
                source.send(new ClientResourcePackStatusPacket(ResourcePackStatus.SUCCESSFULLY_LOADED));
                listener.onResourcePackStatus("SUCCESSFULLY_LOADED");
            }
        }, config.resourcePackStepDelayMillis(), TimeUnit.MILLISECONDS);
    }

    private void acknowledgeTeleport(Session source, ServerPlayerPositionRotationPacket packet) {
        synchronized (movementLock) {
            List<PositionElement> relative = packet.getRelative();
            x = relative.contains(PositionElement.X) ? x + packet.getX() : packet.getX();
            y = relative.contains(PositionElement.Y) ? y + packet.getY() : packet.getY();
            z = relative.contains(PositionElement.Z) ? z + packet.getZ() : packet.getZ();
            pitch = relative.contains(PositionElement.PITCH) ? pitch + packet.getPitch() : packet.getPitch();
            yaw = relative.contains(PositionElement.YAW) ? yaw + packet.getYaw() : packet.getYaw();
            positionKnown.set(true);
            source.send(new ClientTeleportConfirmPacket(packet.getTeleportId()));
            source.send(new ClientPlayerPositionRotationPacket(false, x, y, z, yaw, pitch));
        }
    }

    private void scheduleRespawn(Session source) {
        if (!respawnPending.compareAndSet(false, true)) {
            return;
        }
        executor.schedule(() -> {
            if (source.isConnected() && state.get() == TransportState.PLAY) {
                source.send(new ClientRequestPacket(ClientRequest.RESPAWN));
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
        public void packetReceived(PacketReceivedEvent event) {
            onPacket(event);
        }

        @Override
        public void disconnected(DisconnectedEvent event) {
            session = null;
            listener.onDisconnected(event.getReason(), event.getCause());
        }
    }

    /**
     * PacketLib uses Session#getHost/getPort for both the socket and handshake.
     * Once connected, returning the virtual endpoint gives Velocity the correct
     * forced-host value without changing the TCP destination.
     */
    private static final class VirtualHostSession extends TcpClientSession {
        private final String virtualHost;
        private final int virtualPort;

        private VirtualHostSession(String address, int port, MinecraftProtocol protocol,
                                   String virtualHost, int virtualPort) {
            super(address, port, protocol);
            this.virtualHost = virtualHost;
            this.virtualPort = virtualPort;
        }

        @Override
        public String getHost() {
            return isConnected() ? virtualHost : super.getHost();
        }

        @Override
        public int getPort() {
            return isConnected() ? virtualPort : super.getPort();
        }
    }
}
