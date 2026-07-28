package dev.nulli0n.vbot.transport;

public interface BotTransport extends AutoCloseable {
    void connect();

    void disconnect(String reason);

    boolean isConnected();

    boolean sendCommand(String command);

    /**
     * Submits a modern pre-join authentication dialog. Legacy transports and
     * servers without dialog authentication leave this unsupported.
     */
    default boolean submitAuthenticationUi(AuthenticationUiChallenge challenge, String password) {
        return false;
    }

    boolean moveTo(double x, double y, double z);

    boolean look(float yaw, float pitch);

    BotPosition position();

    @Override
    default void close() {
        disconnect("Transport closed");
    }
}
