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

    /**
     * Submits a recognized authentication dialog, including an optional email
     * when the registration form explicitly requests one.
     */
    default boolean submitAuthenticationUi(
        AuthenticationUiChallenge challenge,
        String password,
        String registrationEmail
    ) {
        return submitAuthenticationUi(challenge, password);
    }

    boolean moveTo(double x, double y, double z);

    boolean look(float yaw, float pitch);

    /** Sends a main-hand swing when the active protocol supports it. */
    default boolean swingMainHand() {
        return false;
    }

    /** Performs one conservative client-side jump movement when possible. */
    default boolean jump() {
        return false;
    }

    /** Updates the client sneaking/shift state when supported by the protocol. */
    default boolean setSneaking(boolean sneaking) {
        return false;
    }

    BotPosition position();

    @Override
    default void close() {
        disconnect("Transport closed");
    }
}
