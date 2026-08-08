package dev.nulli0n.vbot.addon.api;

public enum AddonBotState {
    STOPPED,
    CONNECTING,
    LOGIN,
    CONFIGURATION,
    PLAY,
    RECONNECT_WAIT,
    STOPPING,
    FAILED
}
