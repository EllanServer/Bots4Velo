package dev.nulli0n.vbot.protocol;

public record DetectedProtocol(String advertisedName, int protocolId, String source) {
    public DetectedProtocol(String advertisedName, int protocolId) {
        this(advertisedName, protocolId, "unknown");
    }

    public ProtocolVersion requireSupported() {
        return ProtocolVersion.byProtocolId(protocolId)
            .orElseThrow(() -> new IllegalArgumentException(
                "Detection source " + source + " advertised unsupported Minecraft protocol "
                    + protocolId + " (" + advertisedName + ")"));
    }
}
