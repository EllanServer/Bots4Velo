package dev.nulli0n.vbot.protocol;

public record ProtocolSelection(boolean automatic, ProtocolVersion fixedVersion) {
    public ProtocolSelection {
        if (automatic == (fixedVersion != null)) {
            throw new IllegalArgumentException("Protocol selection must be either AUTO or one fixed version");
        }
    }

    public static ProtocolSelection autoDetect() {
        return new ProtocolSelection(true, null);
    }

    public static ProtocolSelection fixed(ProtocolVersion version) {
        return new ProtocolSelection(false, version);
    }

    public static ProtocolSelection parse(String raw) {
        return raw.trim().equalsIgnoreCase("AUTO") ? autoDetect() : fixed(ProtocolVersion.parse(raw));
    }
}
