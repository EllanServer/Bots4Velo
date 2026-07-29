package dev.nulli0n.vbot.message;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.yaml.snakeyaml.LoaderOptions;
import org.yaml.snakeyaml.Yaml;
import org.yaml.snakeyaml.constructor.SafeConstructor;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class PluginMessagesTest {
    private static final Logger LOGGER = LoggerFactory.getLogger(PluginMessagesTest.class);

    @TempDir
    Path temporaryDirectory;

    @Test
    void copiesBundledMessagesAndReportsBundledLanguages() {
        PluginMessages messages = PluginMessages.load(temporaryDirectory, LOGGER);

        assertThat(messages.language()).isEqualTo("en_US");
        assertThat(messages.availableLanguages()).containsExactly("en_US", "zh_CN");
        assertThat(messages.text("no-permission", "fallback %s", "start"))
            .isEqualTo("You do not have permission for /vbot start.");
        assertThat(temporaryDirectory.resolve("messages.yml")).isRegularFile();
    }

    @Test
    @SuppressWarnings("unchecked")
    void bundledLanguagesHaveMatchingKeysAndFormatArguments() throws Exception {
        try (InputStream input = PluginMessagesTest.class.getClassLoader()
            .getResourceAsStream("messages.yml")) {
            assertThat(input).isNotNull();
            Object loaded = new Yaml(new SafeConstructor(new LoaderOptions())).load(input);
            Map<String, Object> root = (Map<String, Object>) loaded;
            Map<String, Object> english = (Map<String, Object>) root.get("en_US");
            Map<String, Object> chinese = (Map<String, Object>) root.get("zh_CN");

            assertThat(chinese.keySet()).containsExactlyInAnyOrderElementsOf(english.keySet());
            english.forEach((key, englishValue) -> assertThat(PluginMessages.hasSameFormatArguments(
                    String.valueOf(chinese.get(key)), String.valueOf(englishValue)))
                .as("format arguments for %s", key)
                .isTrue());
        }
    }

    @Test
    void normalizesLanguageAndMergesBundledLocaleBeforeDiskOverrides() throws Exception {
        Files.writeString(temporaryDirectory.resolve("messages.yml"), """
            language: ZH-cn
            zh_CN:
              no-permission: "自定义：%s"
            """, StandardCharsets.UTF_8);

        PluginMessages messages = PluginMessages.load(temporaryDirectory, LOGGER);

        assertThat(messages.language()).isEqualTo("zh_CN");
        assertThat(messages.text("no-permission", "fallback %s", "start"))
            .isEqualTo("自定义：start");
        assertThat(messages.text("config-ok", "fallback"))
            .isEqualTo("配置：正常（验证完成，未替换正在运行的机器人）。");
    }

    @Test
    void preservesCustomLocalesAndUsesBundledEnglishForTheirMissingKeys() throws Exception {
        Files.writeString(temporaryDirectory.resolve("messages.yml"), """
            language: pIrAtE
            Pirate:
              no-permission: "Arrr, no %s"
            """, StandardCharsets.UTF_8);

        PluginMessages messages = PluginMessages.load(temporaryDirectory, LOGGER);

        assertThat(messages.language()).isEqualTo("Pirate");
        assertThat(messages.availableLanguages()).containsExactly("en_US", "zh_CN", "Pirate");
        assertThat(messages.text("no-permission", "fallback %s", "start"))
            .isEqualTo("Arrr, no start");
        assertThat(messages.text("config-ok", "fallback"))
            .isEqualTo("Config: OK (validation passed without replacing live bots).");
    }

    @Test
    void invalidSelectedFormatFallsBackToBundledEnglish() throws Exception {
        Files.writeString(temporaryDirectory.resolve("messages.yml"), """
            language: en_US
            en_US:
              no-permission: "Broken %q"
            """, StandardCharsets.UTF_8);
        PluginMessages messages = PluginMessages.load(temporaryDirectory, LOGGER);

        assertThat(messages.text("no-permission", "fallback %s", "start"))
            .isEqualTo("You do not have permission for /vbot start.");
        assertThat(messages.text("no-permission", "fallback %s", "stop"))
            .isEqualTo("You do not have permission for /vbot stop.");
    }

    @Test
    void missingFormatArgumentsFallBackButIndexedReorderingIsAllowed() throws Exception {
        Files.writeString(temporaryDirectory.resolve("messages.yml"), """
            language: zh_CN
            zh_CN:
              unknown-bot: "机器人不存在"
              selection-result: "结果：%2$s/%3$s，操作=%1$s"
            """, StandardCharsets.UTF_8);

        PluginMessages messages = PluginMessages.loadStrict(temporaryDirectory, LOGGER);

        assertThat(messages.text("unknown-bot", "fallback %s", "Farm01"))
            .isEqualTo("Unknown bot: Farm01");
        assertThat(messages.text("selection-result", "fallback %s %s %s", "启动", 2, 3))
            .isEqualTo("结果：2/3，操作=启动");
    }

    @Test
    void strictLoadRejectsMalformedCatalogWhileStartupLoadFallsBack() throws Exception {
        Files.writeString(temporaryDirectory.resolve("messages.yml"), """
            language: [broken
            en_US:
              no-permission: "broken"
            """, StandardCharsets.UTF_8);

        assertThatThrownBy(() -> PluginMessages.loadStrict(temporaryDirectory, LOGGER))
            .isInstanceOf(IllegalArgumentException.class);
        assertThat(PluginMessages.load(temporaryDirectory, LOGGER).language()).isEqualTo("en_US");
        assertThat(PluginMessages.load(temporaryDirectory, LOGGER)
            .text("no-permission", "fallback %s", "start"))
            .isEqualTo("You do not have permission for /vbot start.");
    }

    @Test
    void selectingLanguagePreservesCommentsTranslationsAndCrLfBytes() throws Exception {
        String original = "# custom header\r\n"
            + "language: en_US  # selected locale\r\n"
            + "en_US:\r\n"
            + "  custom: \"language: zh_CN\"\r\n"
            + "zh_CN:\r\n"
            + "  custom: \"保留\"\r\n";
        Path file = temporaryDirectory.resolve("messages.yml");
        Files.writeString(file, original, StandardCharsets.UTF_8);

        PluginMessages selected = PluginMessages.selectLanguage(temporaryDirectory, "ZH-cn", LOGGER);

        assertThat(selected.language()).isEqualTo("zh_CN");
        assertThat(Files.readString(file, StandardCharsets.UTF_8)).isEqualTo(
            original.replace("language: en_US  # selected locale",
                "language: zh_CN  # selected locale"));
        assertThat(PluginMessages.load(temporaryDirectory, LOGGER).language()).isEqualTo("zh_CN");
    }

    @Test
    void selectingCustomLanguageUsesItsAuthoredName() throws Exception {
        Path file = temporaryDirectory.resolve("messages.yml");
        Files.writeString(file, """
            # keep me
            language: en_US
            Pirate:
              greeting: "Ahoy"
            """, StandardCharsets.UTF_8);

        assertThat(PluginMessages.selectLanguage(temporaryDirectory, "PIRATE", LOGGER).language())
            .isEqualTo("Pirate");
        assertThat(Files.readString(file, StandardCharsets.UTF_8))
            .contains("# keep me\nlanguage: Pirate\nPirate:");
    }

    @Test
    void selectingQuotedCustomLanguageDoesNotTreatHashAsAComment() throws Exception {
        Path file = temporaryDirectory.resolve("messages.yml");
        String original = """
            language: en_US # keep this comment
            "Pir#ate":
              greeting: "Ahoy"
            """;
        Files.writeString(file, original, StandardCharsets.UTF_8);

        PluginMessages selected = PluginMessages.selectLanguage(
            temporaryDirectory, "pir#ATE", LOGGER);

        assertThat(selected.language()).isEqualTo("Pir#ate");
        assertThat(Files.readString(file, StandardCharsets.UTF_8))
            .isEqualTo(original.replace("en_US", "\"Pir#ate\""));
    }

    @Test
    void unknownSelectionDoesNotModifyMessagesFile() throws Exception {
        Path file = temporaryDirectory.resolve("messages.yml");
        String original = """
            language: en_US
            en_US:
              greeting: "Hello"
            """;
        Files.writeString(file, original, StandardCharsets.UTF_8);

        assertThatThrownBy(() -> PluginMessages.selectLanguage(
            temporaryDirectory, "missing_LOCALE", LOGGER))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("Available languages");
        assertThat(Files.readString(file, StandardCharsets.UTF_8)).isEqualTo(original);
    }

    @Test
    void duplicateTopLevelLanguageLinesAreRejectedWithoutWriting() throws Exception {
        Path file = temporaryDirectory.resolve("messages.yml");
        String original = """
            language: en_US
            language: zh_CN
            en_US:
              greeting: "Hello"
            """;
        Files.writeString(file, original, StandardCharsets.UTF_8);

        assertThatThrownBy(() -> PluginMessages.selectLanguage(
            temporaryDirectory, "en-us", LOGGER))
            .isInstanceOf(IllegalArgumentException.class);
        assertThat(Files.readString(file, StandardCharsets.UTF_8)).isEqualTo(original);
    }
}
