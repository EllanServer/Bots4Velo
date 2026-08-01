package dev.nulli0n.vbot.config;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ManagedCredentialReferenceTest {
    @Test
    void createsStrictNoneSecretAndEnvironmentReferences() {
        assertThat(ManagedCredentialReference.none()).satisfies(reference -> {
            assertThat(reference.kind()).isEqualTo(ManagedCredentialReference.Kind.NONE);
            assertThat(reference.reference()).isEmpty();
            assertThat(reference.sourceFingerprint()).isEqualTo("none");
        });
        assertThat(ManagedCredentialReference.secret("farm.bot-01")).satisfies(reference -> {
            assertThat(reference.kind()).isEqualTo(ManagedCredentialReference.Kind.SECRET);
            assertThat(reference.reference()).isEqualTo("farm.bot-01");
            assertThat(reference.sourceFingerprint()).startsWith("secret:").doesNotContain("farm.bot-01");
        });
        assertThat(ManagedCredentialReference.environment("BOT_FARM_01_PASSWORD")).satisfies(reference -> {
            assertThat(reference.kind()).isEqualTo(ManagedCredentialReference.Kind.ENVIRONMENT);
            assertThat(reference.reference()).isEqualTo("BOT_FARM_01_PASSWORD");
            assertThat(reference.sourceFingerprint()).startsWith("environment:")
                .doesNotContain("BOT_FARM_01_PASSWORD");
        });
    }

    @Test
    void rejectsInvalidOrAmbiguousReferenceShapes() {
        assertThatThrownBy(() -> new ManagedCredentialReference(
            ManagedCredentialReference.Kind.NONE, "unexpected"))
            .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("NONE");
        assertThatThrownBy(() -> ManagedCredentialReference.secret(""))
            .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("Secret alias");
        assertThatThrownBy(() -> ManagedCredentialReference.secret(" farm"))
            .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("whitespace");
        assertThatThrownBy(() -> ManagedCredentialReference.secret("farm/one"))
            .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("Secret alias");
        assertThatThrownBy(() -> ManagedCredentialReference.environment("1PASSWORD"))
            .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("Environment name");
        assertThatThrownBy(() -> ManagedCredentialReference.environment("BOT-PASSWORD"))
            .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("Environment name");
        assertThatThrownBy(() -> ManagedCredentialReference.environment("BOT_PASSWORD "))
            .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("whitespace");
    }

    @Test
    void secretFingerprintsAreCaseInsensitiveButEnvironmentFingerprintsAreNot() {
        assertThat(ManagedCredentialReference.secret("Farm01").sourceFingerprint())
            .isEqualTo(ManagedCredentialReference.secret("farm01").sourceFingerprint());
        assertThat(ManagedCredentialReference.environment("BOT_PASSWORD").sourceFingerprint())
            .isNotEqualTo(ManagedCredentialReference.environment("bot_password").sourceFingerprint());
    }
}
