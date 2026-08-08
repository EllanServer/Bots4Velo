package dev.nulli0n.vbot.addon.api;

public record AddonServerSwitchResult(
    AddonServerSwitchStatus status,
    String botId,
    String username,
    String server,
    String detail
) {
    public boolean successful() {
        return status == AddonServerSwitchStatus.SWITCHED
            || status == AddonServerSwitchStatus.ALREADY_CONNECTED;
    }
}
