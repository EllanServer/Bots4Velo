package dev.nulli0n.vbot.transport;

public interface TransportListener {
    void onStateChanged(TransportState state);

    void onSystemMessage(String message);

    default void onAuthenticationUi(AuthenticationUiChallenge challenge) {
    }

    void onDisconnected(String reason, Throwable cause);

    default void onResourcePackStatus(String status) {
    }

    default void onDiagnostic(String message) {
    }
}
