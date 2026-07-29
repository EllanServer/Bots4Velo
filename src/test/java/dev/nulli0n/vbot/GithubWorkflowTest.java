package dev.nulli0n.vbot;

import org.junit.jupiter.api.Test;
import org.yaml.snakeyaml.LoaderOptions;
import org.yaml.snakeyaml.Yaml;
import org.yaml.snakeyaml.constructor.SafeConstructor;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class GithubWorkflowTest {
    @Test
    void majorReleaseWorkflowIsValidYamlAndKeepsReleaseGuards() throws Exception {
        String workflow = Files.readString(Path.of(".github/workflows/build-major-release.yml"));
        Object parsed = new Yaml(new SafeConstructor(new LoaderOptions())).load(workflow);

        assertThat(parsed).isInstanceOf(Map.class);
        assertThat(workflow)
            .contains("- \"v[0-9]+.0.0\"")
            .contains("permissions:\n  contents: write")
            .contains("java-version: \"21\"")
            .contains("run: chmod +x gradlew")
            .contains("run: ./gradlew clean test shadowJar -PpluginVersion=\"${GITHUB_REF_NAME#v}\"")
            .contains("run: ./gradlew writeArtifactChecksum -PpluginVersion=\"${GITHUB_REF_NAME#v}\"")
            .contains("path: build/libs/*")
            .contains("GH_TOKEN: ${{ github.token }}")
            .contains("gh release create \"${GITHUB_REF_NAME}\" build/libs/*.jar --title \"${GITHUB_REF_NAME}\" --verify-tag --generate-notes")
            .contains("gh release upload \"${GITHUB_REF_NAME}\" build/libs/*.sha256 --clobber");
    }

    @Test
    void normalBuildWorkflowValidatesCommitsAndPullRequests() throws Exception {
        String workflow = Files.readString(Path.of(".github/workflows/build.yml"));
        Object parsed = new Yaml(new SafeConstructor(new LoaderOptions())).load(workflow);

        assertThat(parsed).isInstanceOf(Map.class);
        assertThat(workflow)
            .contains("pull_request:")
            .contains("branches:")
            .contains("java-version: \"21\"")
            .contains("run: chmod +x gradlew")
            .contains("run: ./gradlew clean check shadowJar writeArtifactChecksum")
            .contains("protocol-contract:")
            .contains("protocol: [\"1.16.5\", \"1.21.11\", \"26.1.2\", \"26.2\"]")
            .contains("run: ./gradlew :test --tests dev.nulli0n.vbot.protocol.ProtocolVersionTest "
                + "-PciProtocol=\"${{ matrix.protocol }}\"")
            .contains("integration-network:")
            .contains("minecraft: \"1.16.5\"")
            .contains("server-java: \"16\"")
            .contains("minecraft: \"1.21.11\"")
            .contains("minecraft: \"26.1.2\"")
            .contains("minecraft: \"26.2\"")
            .contains("VELOCITY_JAVA=\"${JAVA_HOME_21_X64}/bin/java\"")
            .contains("scripts/ci/run-network-integration.sh");
    }

    @Test
    void integrationScriptUsesAValidVelocityAndPresenceConfiguration() throws Exception {
        String script = Files.readString(Path.of("scripts/ci/run-network-integration.sh"));

        assertThat(script)
            .contains("[forced-hosts]")
            .contains("VELOCITY_JAVA")
            .contains("VELOCITY_PLUGIN_JAR")
            .contains("PAPER_COMPANION_JAR")
            .contains("bots4velo-[0-9]*.jar")
            .contains("bots4velo-paper-*.jar")
            .contains("Expected exactly one Velocity plugin JAR")
            .contains("Expected exactly one Paper companion JAR")
            .contains("plugins/Bots4VeloPaper/config.yml")
            .contains("shared-secret: \"$BACKEND_SHARED_SECRET\"")
            .contains("Listening for authenticated Bots4Velo policies on bots4velo:control")
            .contains("backend-control:")
            .contains("player-state:")
            .contains("invulnerable: \"ENABLED\"")
            .contains("Paper backend control APPLY_POLICY for bot IntegrationBot on afk: OK")
            .contains("--retry-all-errors")
            .contains("download_authme '5.6.0' '-legacy.jar'")
            .contains("fallback-register-delay-ms: 1500")
            .contains("TIMEOUT_FIXTURE_SELECTOR")
            .contains("successfully registered")
            .contains("start-timeout-fixture")
            .contains("initial-delay-ms: 15000")
            .contains("login-delay-ms: 5000")
            .contains("presence-rules:")
            .contains("interval-ms: 1000")
            .contains("-> afk has connected")
            .contains("wait_for_new_log");
    }
}
