package dev.nulli0n.vbot.transport;

import java.util.UUID;

public record TransportConfig(
    String username,
    UUID uuid,
    String address,
    int port,
    String virtualHost,
    int virtualPort,
    int renderDistance,
    boolean acceptResourcePacksWithoutDownload,
    long resourcePackStepDelayMillis,
    boolean autoRespawn
) {
}
