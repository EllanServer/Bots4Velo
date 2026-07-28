package dev.nulli0n.vbot.transport;

/**
 * A password form sent while a modern client is still in CONFIGURATION.
 * Field names and the custom-click action are taken from the received dialog
 * instead of assuming one fixed AuthMe build.
 */
public record AuthenticationUiChallenge(
    AuthenticationUiType type,
    String actionId,
    String passwordInput,
    String confirmationInput
) {
    public String description() {
        String fields = confirmationInput == null || confirmationInput.isBlank()
            ? passwordInput
            : passwordInput + "," + confirmationInput;
        return type + " action=" + actionId + " fields=" + fields;
    }
}
