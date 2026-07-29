package dev.nulli0n.vbot.transport;

public interface TransportListener {
    void onStateChanged(TransportState state);

    void onSystemMessage(String message);

    default void onAuthenticationUi(AuthenticationUiChallenge challenge) {
    }

    /**
     * Reports a structurally recognized AuthMe post-join command-template UI.
     * The transport exposes only the authentication intent, never dialog text
     * or the command template itself.
     */
    default void onAuthenticationCommandUi(AuthenticationUiType type) {
    }

    void onDisconnected(String reason, Throwable cause);

    default void onResourcePackStatus(String status) {
    }

    default void onDiagnostic(String message) {
    }
}
