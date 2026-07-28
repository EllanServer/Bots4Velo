package dev.nulli0n.vbot.adapter.legacy;

import com.github.steveice10.mc.protocol.MinecraftConstants;
import dev.nulli0n.vbot.transport.BotTransport;
import dev.nulli0n.vbot.transport.BotTransportFactory;
import dev.nulli0n.vbot.transport.TransportConfig;
import dev.nulli0n.vbot.transport.TransportListener;

import java.util.concurrent.ScheduledExecutorService;

public final class LegacyTransportFactory implements BotTransportFactory {
    @Override
    public int protocolId() {
        return MinecraftConstants.PROTOCOL_VERSION;
    }

    @Override
    public String versionName() {
        return MinecraftConstants.GAME_VERSION;
    }

    @Override
    public BotTransport create(TransportConfig config, TransportListener listener,
                               ScheduledExecutorService executor) {
        return new LegacyBotTransport(config, listener, executor);
    }
}
