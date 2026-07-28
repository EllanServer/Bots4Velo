package dev.nulli0n.vbot.transport;

import java.util.concurrent.ScheduledExecutorService;

public interface BotTransportFactory {
    int protocolId();

    String versionName();

    BotTransport create(TransportConfig config, TransportListener listener, ScheduledExecutorService executor);
}
