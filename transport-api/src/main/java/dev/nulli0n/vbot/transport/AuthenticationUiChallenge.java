package dev.nulli0n.vbot.transport;

/**
 * A recognized AuthMe or AuthMeUI dialog challenge extracted from a modern
 * CONFIGURATION or PLAY packet. Field names and the custom-click action come
 * from the server-owned dialog definition.
 */
public record AuthenticationUiChallenge(
    AuthenticationUiType type,
    AuthenticationUiProvider provider,
    String actionId,
    String passwordInput,
    String secondaryInput,
    AuthenticationUiInputPurpose secondaryInputPurpose,
    String agreementInput
) {
    /** Source-compatible constructor for the built-in AuthMe login/register dialogs. */
    public AuthenticationUiChallenge(
        AuthenticationUiType type,
        String actionId,
        String passwordInput,
        String confirmationInput
    ) {
        this(
            type,
            AuthenticationUiProvider.AUTHME,
            actionId,
            passwordInput,
            confirmationInput,
            confirmationInput == null || confirmationInput.isBlank()
                ? AuthenticationUiInputPurpose.NONE
                : AuthenticationUiInputPurpose.CONFIRMATION,
            null
        );
    }

    public String description() {
        StringBuilder fields = new StringBuilder();
        appendField(fields, passwordInput);
        appendField(fields, secondaryInput);
        appendField(fields, agreementInput);
        return provider + " " + type + " action=" + actionId + " fields=" + fields;
    }

    private static void appendField(StringBuilder target, String value) {
        if (value == null || value.isBlank()) {
            return;
        }
        if (!target.isEmpty()) {
            target.append(',');
        }
        target.append(value);
    }
}
