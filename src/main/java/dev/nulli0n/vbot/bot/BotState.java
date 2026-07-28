package dev.nulli0n.vbot.bot;

public enum BotState {
    STOPPED,
    CONNECTING,
    LOGIN,
    CONFIGURATION,
    PLAY,
    RECONNECT_WAIT,
    STOPPING,
    FAILED
}
