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
            .contains("path: build/libs/*.jar")
            .contains("GH_TOKEN: ${{ github.token }}")
            .contains("gh release create \"${GITHUB_REF_NAME}\" build/libs/*.jar --title \"${GITHUB_REF_NAME}\" --verify-tag --generate-notes");
    }
}
