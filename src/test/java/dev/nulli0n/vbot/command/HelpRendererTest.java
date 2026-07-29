package dev.nulli0n.vbot.command;

import dev.nulli0n.vbot.message.PluginMessages;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.event.ClickEvent;
import net.kyori.adventure.text.event.HoverEvent;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Deque;
import java.util.List;
import java.util.Set;
import java.util.function.Predicate;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;

class HelpRendererTest {
    private static final Logger LOGGER = LoggerFactory.getLogger(HelpRendererTest.class);
    private static final PlainTextComponentSerializer PLAIN = PlainTextComponentSerializer.plainText();
    private static final Predicate<String> ALL_PERMISSIONS = ignored -> true;

    @TempDir
    Path temporaryDirectory;

    @Test
    void catalogHasFivePagesWithoutDuplicateCommands() {
        List<HelpRenderer.HelpEntry> entries = allEntries();

        assertThat(HelpRenderer.PAGE_COUNT).isEqualTo(5);
        assertThat(HelpRenderer.entries(1)).hasSize(6);
        assertThat(HelpRenderer.entries(2)).hasSize(7);
        assertThat(HelpRenderer.entries(3)).hasSize(5);
        assertThat(HelpRenderer.entries(4)).hasSize(5);
        assertThat(HelpRenderer.entries(5)).hasSize(7);
        assertThat(HelpRenderer.entries(0)).isEmpty();
        assertThat(HelpRenderer.entries(6)).isEmpty();
        assertThat(entries).hasSize(30);
        assertThat(entries).extracting(HelpRenderer.HelpEntry::syntax).doesNotHaveDuplicates();
        assertThat(entries).extracting(HelpRenderer.HelpEntry::descriptionKey).doesNotHaveDuplicates();
        assertThat(entries).allSatisfy(entry ->
            assertThat(entry.page()).isBetween(1, HelpRenderer.PAGE_COUNT));
    }

    @Test
    void bundledEnglishAndChineseCoverEveryCatalogDescription() throws Exception {
        PluginMessages english = bundledMessages("bundled-english", "en_US");
        PluginMessages chinese = bundledMessages("bundled-chinese", "zh_CN");

        assertThat(allEntries()).allSatisfy(entry -> {
            assertThat(english.text(entry.descriptionKey(), "missing: " + entry.descriptionKey()))
                .as("bundled English %s", entry.descriptionKey())
                .isEqualTo(entry.englishDescription());
            assertThat(chinese.text(entry.descriptionKey(), entry.englishDescription()))
                .as("bundled Chinese %s", entry.descriptionKey())
                .isNotEqualTo(entry.englishDescription());
        });
    }

    @Test
    void catalogPermissionsMatchTheCommandPermissionModel() {
        assertThat(allEntries()).allSatisfy(entry -> {
            String[] arguments = invocationArguments(entry);
            String action = arguments[0];
            assertThat(entry.permission())
                .as("permission for %s", entry.syntax())
                .isEqualTo(VBotCommand.permissionFor(action, arguments));
        });
    }

    @Test
    void filtersEveryPageByPermissionAndShowsAnEmptyState() throws Exception {
        PluginMessages messages = englishMessages();
        Set<String> viewOnly = Set.of("bots4velo.view");

        for (int page = 1; page <= HelpRenderer.PAGE_COUNT; page++) {
            List<Component> rendered = HelpRenderer.render(messages, page, viewOnly::contains);
            List<Component> commandLines = commandLines(rendered);
            List<HelpRenderer.HelpEntry> expected = HelpRenderer.entries(page).stream()
                .filter(entry -> viewOnly.contains(entry.permission()))
                .toList();

            assertThat(commandLines).hasSameSizeAs(expected);
            assertThat(suggestedCommands(commandLines))
                .containsExactlyElementsOf(expected.stream().map(HelpRenderer.HelpEntry::suggestion).toList());
        }

        List<Component> empty = HelpRenderer.render(messages, 3, ignored -> false);
        assertThat(commandLines(empty)).isEmpty();
        assertThat(visibleText(empty)).contains("No commands on this test page.");
    }

    @Test
    void everyVisibleCommandSuggestsItsCommandAndShowsHoverText() throws Exception {
        PluginMessages messages = englishMessages();

        for (int page = 1; page <= HelpRenderer.PAGE_COUNT; page++) {
            List<Component> commandLines = commandLines(HelpRenderer.render(messages, page, ALL_PERMISSIONS));
            assertThat(commandLines).hasSameSizeAs(HelpRenderer.entries(page));

            for (int index = 0; index < commandLines.size(); index++) {
                HelpRenderer.HelpEntry entry = HelpRenderer.entries(page).get(index);
                List<Component> interactive = descendants(commandLines.get(index)).stream()
                    .filter(component -> component.clickEvent() != null)
                    .toList();

                assertThat(interactive).as(entry.syntax()).hasSize(1);
                Component command = interactive.getFirst();
                assertThat(command.clickEvent().action()).isEqualTo(ClickEvent.Action.SUGGEST_COMMAND);
                assertThat(command.clickEvent().value()).isEqualTo(entry.suggestion());
                assertThat(command.hoverEvent()).isNotNull();
                assertThat(command.hoverEvent().action()).isEqualTo(HoverEvent.Action.SHOW_TEXT);
                assertThat(hoverText(command))
                    .contains("Insert this command: " + entry.suggestion())
                    .contains("Requires " + entry.permission());
            }
        }
    }

    @Test
    void navigationRunsOnlyValidHelpPagesAndHasHoverText() throws Exception {
        PluginMessages messages = englishMessages();

        for (int page = 1; page <= HelpRenderer.PAGE_COUNT; page++) {
            int currentPage = page;
            List<Component> rendered = HelpRenderer.render(messages, page, ALL_PERMISSIONS);
            Component navigation = rendered.getLast();
            List<Component> buttons = descendants(navigation).stream()
                .filter(component -> component.clickEvent() != null)
                .toList();

            assertThat(buttons).isNotEmpty();
            assertThat(buttons).allSatisfy(button -> {
                assertThat(button.clickEvent().action()).isEqualTo(ClickEvent.Action.RUN_COMMAND);
                assertThat(button.clickEvent().value()).matches("/vbot help [1-5]");
                assertThat(button.clickEvent().value()).isNotEqualTo("/vbot help " + currentPage);
                assertThat(button.hoverEvent()).isNotNull();
                assertThat(button.hoverEvent().action()).isEqualTo(HoverEvent.Action.SHOW_TEXT);
                assertThat(hoverText(button)).matches("Open test page [1-5]");
            });
        }
    }

    @Test
    void rejectsPagesOutsideThePublishedRange() throws Exception {
        PluginMessages messages = englishMessages();

        assertThat(HelpRenderer.render(messages, 0, ALL_PERMISSIONS)).isEmpty();
        assertThat(HelpRenderer.render(messages, -1, ALL_PERMISSIONS)).isEmpty();
        assertThat(HelpRenderer.render(messages, HelpRenderer.PAGE_COUNT + 1, ALL_PERMISSIONS)).isEmpty();
    }

    @Test
    void rendersEnglishAndChineseCatalogTextFromMessagesFiles() throws Exception {
        PluginMessages english = englishMessages();
        PluginMessages chinese = chineseMessages();

        List<Component> englishPage = HelpRenderer.render(english, 1, ALL_PERMISSIONS);
        assertThat(english.language()).isEqualTo("en_US");
        assertThat(visibleText(englishPage))
            .contains("English test overview")
            .contains("English list description")
            .contains("English selector hint");
        assertThat(hoverText(findClickTarget(englishPage, "/vbot list ")))
            .contains("Insert this command: /vbot list")
            .contains("Requires bots4velo.view");

        List<Component> chinesePage = HelpRenderer.render(chinese, 1, ALL_PERMISSIONS);
        assertThat(chinese.language()).isEqualTo("zh_CN");
        assertThat(visibleText(chinesePage))
            .contains("中文测试概览")
            .contains("中文列表说明")
            .contains("中文选择器提示");
        assertThat(hoverText(findClickTarget(chinesePage, "/vbot list ")))
            .contains("填入命令：/vbot list")
            .contains("所需权限：bots4velo.view");
    }

    private PluginMessages englishMessages() throws Exception {
        return messages("english", "en_US");
    }

    private PluginMessages chineseMessages() throws Exception {
        return messages("chinese", "zh_CN");
    }

    private PluginMessages messages(String directoryName, String language) throws Exception {
        Path directory = temporaryDirectory.resolve(directoryName);
        Files.createDirectories(directory);
        Files.writeString(directory.resolve("messages.yml"), """
            language: __LANGUAGE__
            en_US:
              help-section-v29-1: "English test overview"
              help-empty: "No commands on this test page."
              help-selectors: "English selector hint"
              help-click-command: "Insert this command: %s"
              help-permission: "Requires %s"
              help-open-page: "Open test page %s"
              help-list-v29: "English list description"
            zh_CN:
              help-section-v29-1: "中文测试概览"
              help-empty: "此测试页面没有命令。"
              help-selectors: "中文选择器提示"
              help-click-command: "填入命令：%s"
              help-permission: "所需权限：%s"
              help-open-page: "打开测试页面 %s"
              help-list-v29: "中文列表说明"
            """.replace("__LANGUAGE__", language), StandardCharsets.UTF_8);
        return PluginMessages.load(directory, LOGGER);
    }

    private PluginMessages bundledMessages(String directoryName, String language) throws Exception {
        Path directory = temporaryDirectory.resolve(directoryName);
        Files.createDirectories(directory);
        Files.writeString(directory.resolve("messages.yml"), """
            language: __LANGUAGE__
            en_US: {}
            zh_CN: {}
            """.replace("__LANGUAGE__", language), StandardCharsets.UTF_8);
        return PluginMessages.load(directory, LOGGER);
    }

    private static List<HelpRenderer.HelpEntry> allEntries() {
        return IntStream.rangeClosed(1, HelpRenderer.PAGE_COUNT)
            .mapToObj(HelpRenderer::entries)
            .flatMap(List::stream)
            .toList();
    }

    private static String[] invocationArguments(HelpRenderer.HelpEntry entry) {
        if (entry.descriptionKey().equals("help-afk-status")) {
            return new String[]{"afk", "all", "status"};
        }
        List<String> tokens = new ArrayList<>(Arrays.asList(entry.suggestion().trim().split("\\s+")));
        assertThat(tokens).as(entry.syntax()).hasSizeGreaterThanOrEqualTo(2);
        tokens.removeFirst();
        if (entry.suggestion().endsWith(" ")) {
            tokens.add("value");
        }
        return tokens.toArray(String[]::new);
    }

    private static List<Component> commandLines(List<Component> rendered) {
        return rendered.stream()
            .filter(line -> descendants(line).stream().anyMatch(component ->
                component.clickEvent() != null
                    && component.clickEvent().action() == ClickEvent.Action.SUGGEST_COMMAND))
            .toList();
    }

    private static List<String> suggestedCommands(List<Component> commandLines) {
        return commandLines.stream()
            .map(line -> descendants(line).stream()
                .map(Component::clickEvent)
                .filter(click -> click != null && click.action() == ClickEvent.Action.SUGGEST_COMMAND)
                .findFirst()
                .orElseThrow()
                .value())
            .toList();
    }

    private static Component findClickTarget(List<Component> rendered, String value) {
        return rendered.stream()
            .flatMap(component -> descendants(component).stream())
            .filter(component -> component.clickEvent() != null)
            .filter(component -> component.clickEvent().value().equals(value))
            .findFirst()
            .orElseThrow();
    }

    private static List<Component> descendants(Component root) {
        List<Component> result = new ArrayList<>();
        Deque<Component> remaining = new ArrayDeque<>();
        remaining.add(root);
        while (!remaining.isEmpty()) {
            Component component = remaining.removeFirst();
            result.add(component);
            remaining.addAll(component.children());
        }
        return result;
    }

    private static String hoverText(Component component) {
        HoverEvent<?> hover = component.hoverEvent();
        assertThat(hover).isNotNull();
        assertThat(hover.action()).isEqualTo(HoverEvent.Action.SHOW_TEXT);
        return PLAIN.serialize((Component) hover.value());
    }

    private static String visibleText(List<Component> components) {
        return components.stream().map(PLAIN::serialize).reduce("", (left, right) -> left + "\n" + right);
    }
}
