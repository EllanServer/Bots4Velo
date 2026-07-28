package dev.nulli0n.vbot.config;

import dev.nulli0n.vbot.config.BotPluginConfig.BotDefinition;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ManagedBotStoreTest {
    @TempDir
    Path temporaryDirectory;

    @Test
    void atomicallyRoundTripsAndRemovesManagedBot() throws Exception {
        ManagedBotStore store = ManagedBotStore.load(temporaryDirectory);
        BotDefinition definition = ManagedBotStore.createDefinition(
            "Dynamic01", "AFK_Dynamic01", "p:a#ss", "survival");

        store.add(definition);

        ManagedBotStore reloaded = ManagedBotStore.load(temporaryDirectory);
        assertThat(reloaded.contains("dynamic01")).isTrue();
        assertThat(reloaded.definitions().get("dynamic01").password()).isEqualTo("p:a#ss");
        assertThat(reloaded.definitions().get("dynamic01").targetServer()).isEqualTo("survival");
        assertThat(reloaded.definitions().get("dynamic01").protocolDetectionServer()).isEqualTo("survival");
        assertThat(reloaded.definitions().get("dynamic01").enabled()).isTrue();

        assertThat(reloaded.remove("DYNAMIC01")).isTrue();
        assertThat(ManagedBotStore.load(temporaryDirectory).definitions()).isEmpty();
        assertThat(Files.readString(temporaryDirectory.resolve(ManagedBotStore.FILE_NAME)))
            .contains("bots:");
    }

    @Test
    void dashPasswordCreatesAuthenticationDisabledBot() {
        BotDefinition definition = ManagedBotStore.createDefinition(
            "NoAuth", "AFK_NoAuth", "", "");

        assertThat(definition.auth().mode()).isEqualTo(BotPluginConfig.AuthMode.NONE);
        assertThat(definition.targetServer()).isBlank();
    }

    @Test
    void managedBotDefaultsRecognizeAuthMeRegistrationAndLoginSuccess() {
        BotDefinition definition = ManagedBotStore.createDefinition(
            "AuthBot", "AFK_AuthBot", "secret", "lobby");

        assertThat(matchesAny(definition, "You have successfully registered! ")).isTrue();
        assertThat(matchesAny(definition, "Successful login!")).isTrue();
    }

    @Test
    void rejectsUnsafeIdentifiersBeforeWriting() {
        assertThatThrownBy(() -> ManagedBotStore.createDefinition(
            "bad id", "AFK_Good", "secret", "survival"))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("Bot id");
    }

    private static boolean matchesAny(BotDefinition definition, String message) {
        return definition.auth().successMessages().stream()
            .map(Pattern::compile)
            .anyMatch(pattern -> pattern.matcher(message).find());
    }
}
