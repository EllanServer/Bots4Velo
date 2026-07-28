package dev.nulli0n.vbot.adapter.modern;

import dev.nulli0n.vbot.transport.BotTransport;
import dev.nulli0n.vbot.transport.BotTransportFactory;
import dev.nulli0n.vbot.transport.TransportConfig;
import dev.nulli0n.vbot.transport.TransportListener;
import org.geysermc.mcprotocollib.protocol.codec.MinecraftCodec;

import java.util.concurrent.ScheduledExecutorService;

public final class ModernTransportFactory implements BotTransportFactory {
    @Override
    public int protocolId() {
        return MinecraftCodec.CODEC.getProtocolVersion();
    }

    @Override
    public String versionName() {
        return MinecraftCodec.CODEC.getMinecraftVersion();
    }

    @Override
    public BotTransport create(TransportConfig config, TransportListener listener,
                               ScheduledExecutorService executor) {
        return new ModernBotTransport(config, listener, executor);
    }
}
