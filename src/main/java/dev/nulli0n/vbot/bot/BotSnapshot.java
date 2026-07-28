package dev.nulli0n.vbot.bot;

import dev.nulli0n.vbot.transport.BotPosition;

import java.time.Instant;

public record BotSnapshot(
    String id,
    String username,
    String protocolVersion,
    String protocolSource,
    BotState state,
    int reconnectAttempts,
    Instant connectedAt,
    long playEntries,
    long disconnects,
    long resourcePacksLoaded,
    Instant lastPlayAt,
    Instant lastDisconnectAt,
    BotPosition position,
    String authenticationUi,
    long authenticationUiPresentations,
    long authenticationUiSubmissions,
    String lastDisconnectReason,
    BehaviorSnapshot behavior
) {
}
