package dev.nulli0n.vbot.bot;

import dev.nulli0n.vbot.config.BotPluginConfig.AuthMode;
import dev.nulli0n.vbot.config.BotPluginConfig.RegistrationSecondArgument;
import dev.nulli0n.vbot.transport.AuthenticationUiChallenge;
import dev.nulli0n.vbot.transport.AuthenticationUiInputPurpose;
import dev.nulli0n.vbot.transport.AuthenticationUiProvider;
import dev.nulli0n.vbot.transport.AuthenticationUiType;

import java.util.HashSet;
import java.util.Set;

/**
 * Connection-local state for AuthMe and AuthMeUI forms.
 *
 * <p>The flow deliberately stores only action identifiers and coarse stages.
 * Passwords, form contents and NBT payloads never enter this state.</p>
 */
final class AuthenticationUiFlow {
    enum Decision {
        SUBMIT,
        IGNORE,
        REJECT
    }

    record Presentation(
        Decision decision,
        long sequence,
        String stage,
        String actionKey,
        boolean credential,
        String reason
    ) {
    }

    private final Set<String> submittedActions = new HashSet<>();
    private long sequence;
    private long meaningfulSequence;
    private long credentialSequence;
    private boolean credentialSubmitted;
    private boolean credentialSubmittedInPlay;
    private boolean active;

    synchronized Presentation present(AuthenticationUiChallenge challenge, AuthMode mode,
                                      boolean acceptRules, String registrationEmail, boolean inPlay) {
        return present(challenge, mode, acceptRules, registrationEmail,
            RegistrationSecondArgument.AUTO, inPlay);
    }

    synchronized Presentation present(AuthenticationUiChallenge challenge, AuthMode mode,
                                      boolean acceptRules, String registrationEmail,
                                      RegistrationSecondArgument registrationSecondArgument,
                                      boolean inPlay) {
        active = true;
        long currentSequence = ++sequence;
        AuthenticationUiType type = challenge.type();
        String provider = challenge.provider() == null ? "UNKNOWN" : challenge.provider().name();
        String typeName = type == null ? "UNKNOWN" : type.name();
        String stage = provider + " " + typeName + " sequence=" + currentSequence;
        boolean credential = type == AuthenticationUiType.LOGIN || type == AuthenticationUiType.REGISTER;

        if (type == null || challenge.provider() == null) {
            meaningfulSequence = currentSequence;
            return reject(currentSequence, stage, credential, "authentication UI has no provider or stage");
        }

        String actionId = safeTrim(challenge.actionId());
        if (actionId.isEmpty()) {
            meaningfulSequence = currentSequence;
            return reject(currentSequence, stage, credential, "authentication UI has no action identifier");
        }
        String actionKey = provider + '\u0000' + actionId;

        if (submittedActions.contains(actionKey)) {
            if (credential) {
                meaningfulSequence = currentSequence;
                return reject(currentSequence, stage, true,
                    "credential action was presented again after submission; AuthMeUI rejected it");
            }
            return new Presentation(Decision.IGNORE, currentSequence, stage, actionKey, false,
                "rules action was already submitted");
        }
        meaningfulSequence = currentSequence;

        if (type == AuthenticationUiType.RULES) {
            if (mode != AuthMode.AUTO && mode != AuthMode.REGISTER) {
                return reject(currentSequence, stage, false,
                    "rules stage is incompatible with auth mode " + mode);
            }
            if (!acceptRules) {
                return reject(currentSequence, stage, false,
                    "rules acceptance is disabled by auth.authmeui.accept-rules");
            }
        }
        else {
            boolean expected = mode == AuthMode.AUTO
                || (mode == AuthMode.LOGIN && type == AuthenticationUiType.LOGIN)
                || (mode == AuthMode.REGISTER && type == AuthenticationUiType.REGISTER);
            if (!expected) {
                return reject(currentSequence, stage, true,
                    typeName + " stage is incompatible with auth mode " + mode);
            }
            if (safeTrim(challenge.passwordInput()).isEmpty()) {
                return reject(currentSequence, stage, true, "credential UI has no password input");
            }
            if (type == AuthenticationUiType.REGISTER
                && challenge.provider() == AuthenticationUiProvider.AUTHME_UI) {
                RegistrationSecondArgument configured = registrationSecondArgument == null
                    ? RegistrationSecondArgument.AUTO
                    : registrationSecondArgument;
                boolean hasSecondaryInput = !safeTrim(challenge.secondaryInput()).isEmpty();
                if (configured == RegistrationSecondArgument.CONFIRMATION && !hasSecondaryInput) {
                    return reject(currentSequence, stage, true,
                        "AuthMeUI registration confirmation is configured but the UI has no second input");
                }
                if (configured == RegistrationSecondArgument.EMAIL_MANDATORY
                    && (!hasSecondaryInput || safeTrim(registrationEmail).isEmpty())) {
                    return reject(currentSequence, stage, true,
                        "registration email is mandatory but auth.authmeui.registration-email is empty or the UI has no second input");
                }
                boolean sendsEmail = challenge.secondaryInputPurpose() == AuthenticationUiInputPurpose.EMAIL
                    && !safeTrim(registrationEmail).isEmpty();
                if (sendsEmail && !inPlay) {
                    return reject(currentSequence, stage, true,
                        "AuthMeUI cannot submit a registration email before entering PLAY");
                }
            }
        }

        return new Presentation(Decision.SUBMIT, currentSequence, stage, actionKey, credential, "");
    }

    synchronized void markSubmitted(Presentation presentation, boolean submittedInPlay) {
        if (presentation.decision() != Decision.SUBMIT) {
            return;
        }
        submittedActions.add(presentation.actionKey());
        if (presentation.credential()) {
            credentialSubmitted = true;
            credentialSubmittedInPlay = submittedInPlay;
            credentialSequence = presentation.sequence();
        }
    }

    synchronized boolean credentialReadyOnPlay() {
        return active && credentialSubmitted && !credentialSubmittedInPlay
            && credentialSequence == meaningfulSequence;
    }

    synchronized boolean active() {
        return active;
    }

    synchronized long sequence() {
        return sequence;
    }

    synchronized int submittedActionCount() {
        return submittedActions.size();
    }

    synchronized void complete() {
        active = false;
    }

    synchronized void reset() {
        submittedActions.clear();
        sequence = 0L;
        meaningfulSequence = 0L;
        credentialSequence = 0L;
        credentialSubmitted = false;
        credentialSubmittedInPlay = false;
        active = false;
    }

    private static Presentation reject(long sequence, String stage, boolean credential, String reason) {
        return new Presentation(Decision.REJECT, sequence, stage, "", credential, reason);
    }

    private static String safeTrim(String value) {
        return value == null ? "" : value.trim();
    }
}
