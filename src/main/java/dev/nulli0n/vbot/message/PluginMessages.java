package dev.nulli0n.vbot.message;

import org.slf4j.Logger;
import org.yaml.snakeyaml.LoaderOptions;
import org.yaml.snakeyaml.Yaml;
import org.yaml.snakeyaml.constructor.SafeConstructor;
import org.yaml.snakeyaml.error.YAMLException;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Collections;
import java.util.IllegalFormatException;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Small, editable message file with English as the safe fallback. */
public final class PluginMessages {
    private static final String FILE_NAME = "messages.yml";
    private static final String DEFAULT_LANGUAGE = "en_US";
    private static final Pattern LANGUAGE_LINE = Pattern.compile(
        "(?m)^(\\uFEFF?language[\\t ]*:[\\t ]*)([^\\r\\n]*)(?=\\r?$)");
    private static final Pattern PLAIN_YAML_VALUE = Pattern.compile("[A-Za-z][A-Za-z0-9_.-]*");
    private static final Pattern FORMAT_SPECIFIER = Pattern.compile(
        "%(?:(\\d+)\\$)?([-#+ 0,(<]*)(\\d+)?(?:\\.(\\d+))?([tT])?([a-zA-Z%])");

    private final String language;
    private final Map<String, String> selected;
    private final Map<String, String> bundledEnglish;
    private final Set<String> availableLanguages;
    private final Logger logger;
    private final Set<String> warnedFormatKeys = ConcurrentHashMap.newKeySet();

    private PluginMessages(String language, Map<String, String> selected,
                           Map<String, String> bundledEnglish, Set<String> availableLanguages,
                           Logger logger) {
        this.language = language;
        this.selected = immutableMap(selected);
        this.bundledEnglish = immutableMap(bundledEnglish);
        this.availableLanguages = Collections.unmodifiableSet(new LinkedHashSet<>(availableLanguages));
        this.logger = logger;
    }

    public static PluginMessages load(Path dataDirectory, Logger logger) {
        try {
            return loadStrict(dataDirectory, logger);
        }
        catch (Exception exception) {
            warn(logger, "Could not load messages.yml; using built-in English", exception);
            Catalog bundled;
            try {
                bundled = loadBundledCatalog();
            }
            catch (Exception ignored) {
                bundled = Catalog.empty();
            }
            return compose(bundled, Catalog.empty(), DEFAULT_LANGUAGE, logger);
        }
    }

    /** Loads the configured catalog without silently accepting a malformed or unknown locale. */
    public static PluginMessages loadStrict(Path dataDirectory, Logger logger) throws IOException {
        Catalog bundled = loadBundledCatalog();
        Files.createDirectories(dataDirectory);
        Path target = dataDirectory.resolve(FILE_NAME);
        if (Files.notExists(target)) {
            copyBundledMessages(target);
        }
        Catalog disk = loadCatalog(target);
        String requested = disk.configuredLanguage() == null
            ? DEFAULT_LANGUAGE
            : disk.configuredLanguage();
        if (resolveLanguage(bundled, disk, requested) == null) {
            throw new UnknownLanguageException("Unknown message language '" + requested
                + "'. Available languages: " + availableLanguageNames(bundled, disk));
        }
        return compose(bundled, disk, requested, logger);
    }

    /**
     * Atomically changes only the unindented, top-level {@code language:} line.
     * Existing comments and user-provided translations are otherwise left untouched.
     *
     * @return messages reloaded with the selected language
     */
    public static PluginMessages selectLanguage(Path dataDirectory, String requestedLanguage, Logger logger)
        throws IOException {
        Catalog bundled = loadBundledCatalog();
        Files.createDirectories(dataDirectory);
        Path target = dataDirectory.resolve(FILE_NAME);
        Catalog disk = Files.exists(target) ? loadCatalog(target) : Catalog.empty();
        LanguageSection resolved = resolveLanguage(bundled, disk, requestedLanguage);
        if (resolved == null) {
            throw new UnknownLanguageException("Unknown message language '" + requestedLanguage
                + "'. Available languages: " + availableLanguageNames(bundled, disk));
        }

        if (Files.notExists(target)) {
            copyBundledMessages(target);
        }
        String original = Files.readString(target, StandardCharsets.UTF_8);
        Matcher matcher = LANGUAGE_LINE.matcher(original);
        if (!matcher.find()) {
            throw new IllegalArgumentException(
                "messages.yml must contain exactly one unindented top-level language line");
        }
        int start = matcher.start();
        int end = matcher.end();
        String replacement = matcher.group(1) + yamlScalar(resolved.name())
            + preservedLineSuffix(matcher.group(2));
        if (matcher.find()) {
            throw new IllegalArgumentException(
                "messages.yml must contain exactly one unindented top-level language line");
        }

        String updated = original.substring(0, start) + replacement + original.substring(end);
        if (!updated.equals(original)) {
            writeAtomically(target, updated);
        }
        return loadStrict(dataDirectory, logger);
    }

    public String language() {
        return language;
    }

    public Set<String> availableLanguages() {
        return availableLanguages;
    }

    public String text(String key, String fallback, Object... arguments) {
        String safeFallback = fallback == null ? "" : fallback;
        String primary = selected.getOrDefault(key, safeFallback);
        String english = bundledEnglish.get(key);
        if (english != null && !primary.equals(english)
            && !hasSameFormatArguments(primary, english)) {
            warnFormatMismatchOnce(key);
            primary = english;
        }
        Object[] safeArguments = arguments == null ? new Object[0] : arguments;
        try {
            return String.format(Locale.ROOT, primary, safeArguments);
        }
        catch (IllegalFormatException exception) {
            warnInvalidFormatOnce(key, exception);
        }

        if (english != null && !english.equals(primary)) {
            try {
                return String.format(Locale.ROOT, english, safeArguments);
            }
            catch (IllegalFormatException ignored) {
                // The call-site fallback below is the final formatted fallback.
            }
        }
        if (!safeFallback.equals(primary) && !safeFallback.equals(english)) {
            try {
                return String.format(Locale.ROOT, safeFallback, safeArguments);
            }
            catch (IllegalFormatException ignored) {
                // Returning the literal is safer than allowing a bad translation to break a command.
            }
        }
        return safeFallback;
    }

    private void warnInvalidFormatOnce(String key, IllegalFormatException exception) {
        if (logger != null && warnedFormatKeys.add(key)) {
            logger.warn("Invalid format string for message key '" + key
                + "'; using the bundled English fallback", exception);
        }
    }

    private void warnFormatMismatchOnce(String key) {
        if (logger != null && warnedFormatKeys.add(key)) {
            logger.warn("Message key '" + key
                + "' does not preserve the bundled English format arguments; using English");
        }
    }

    private static Map<Integer, Map<String, Integer>> formatSignature(String format) {
        Map<Integer, Map<String, Integer>> signature = new LinkedHashMap<>();
        Matcher matcher = FORMAT_SPECIFIER.matcher(format);
        int nextImplicitIndex = 1;
        int lastIndex = -1;
        while (matcher.find()) {
            String conversion = matcher.group(6).toLowerCase(Locale.ROOT);
            if (conversion.equals("%") || conversion.equals("n")) {
                continue;
            }

            int index;
            if (matcher.group(1) != null) {
                index = Integer.parseInt(matcher.group(1));
            }
            else if (matcher.group(2).contains("<")) {
                index = lastIndex;
            }
            else {
                index = nextImplicitIndex++;
            }
            lastIndex = index;
            String type = matcher.group(5) == null ? conversion : "t" + conversion;
            signature.computeIfAbsent(index, ignored -> new LinkedHashMap<>())
                .merge(type, 1, Integer::sum);
        }
        return signature;
    }

    static boolean hasSameFormatArguments(String candidate, String reference) {
        return formatSignature(candidate).equals(formatSignature(reference));
    }

    private static PluginMessages compose(Catalog bundled, Catalog disk, String requested,
                                          Logger logger) {
        LanguageSection englishSection = bundled.languages().get(normalizeLanguage(DEFAULT_LANGUAGE));
        Map<String, String> english = englishSection == null ? Map.of() : englishSection.messages();
        LanguageSection resolved = resolveLanguage(bundled, disk, requested);
        if (resolved == null) {
            warn(logger, "Unknown message language '" + requested
                + "'; using built-in English", null);
            resolved = englishSection == null
                ? new LanguageSection(DEFAULT_LANGUAGE, Map.of())
                : englishSection;
        }

        String normalized = normalizeLanguage(resolved.name());
        Map<String, String> merged = new LinkedHashMap<>(english);
        LanguageSection bundledSelected = bundled.languages().get(normalized);
        if (bundledSelected != null) {
            merged.putAll(bundledSelected.messages());
        }
        LanguageSection diskSelected = disk.languages().get(normalized);
        if (diskSelected != null) {
            merged.putAll(diskSelected.messages());
        }
        return new PluginMessages(resolved.name(), merged, english,
            availableLanguageNames(bundled, disk), logger);
    }

    private static LanguageSection resolveLanguage(Catalog bundled, Catalog disk, String requested) {
        String normalized = normalizeLanguage(requested);
        if (normalized.isEmpty()) {
            return null;
        }
        LanguageSection bundledSection = bundled.languages().get(normalized);
        if (bundledSection != null) {
            return bundledSection;
        }
        return disk.languages().get(normalized);
    }

    private static Set<String> availableLanguageNames(Catalog bundled, Catalog disk) {
        Map<String, String> names = new LinkedHashMap<>();
        bundled.languages().forEach((normalized, section) -> names.put(normalized, section.name()));
        disk.languages().forEach((normalized, section) -> names.putIfAbsent(normalized, section.name()));
        return new LinkedHashSet<>(names.values());
    }

    private static Catalog loadBundledCatalog() throws IOException {
        try (InputStream input = PluginMessages.class.getClassLoader().getResourceAsStream(FILE_NAME)) {
            if (input == null) {
                throw new IOException("Bundled messages.yml is missing");
            }
            return loadCatalog(input, "bundled messages.yml");
        }
    }

    private static Catalog loadCatalog(Path path) throws IOException {
        try (InputStream input = Files.newInputStream(path)) {
            return loadCatalog(input, path.toString());
        }
    }

    private static Catalog loadCatalog(InputStream input, String source) {
        LoaderOptions options = new LoaderOptions();
        options.setAllowDuplicateKeys(false);
        Object loaded;
        try {
            loaded = new Yaml(new SafeConstructor(options)).load(input);
        }
        catch (YAMLException exception) {
            throw new IllegalArgumentException("Could not parse " + source, exception);
        }
        if (!(loaded instanceof Map<?, ?> root)) {
            throw new IllegalArgumentException(source + " root must be a map");
        }

        Object configuredValue = root.get("language");
        String configuredLanguage = configuredValue == null ? null : String.valueOf(configuredValue).trim();
        Map<String, LanguageSection> languages = new LinkedHashMap<>();
        for (Map.Entry<?, ?> entry : root.entrySet()) {
            String name = String.valueOf(entry.getKey()).trim();
            if ("language".equals(name) || !(entry.getValue() instanceof Map<?, ?> values)) {
                continue;
            }
            String normalized = normalizeLanguage(name);
            if (normalized.isEmpty()) {
                continue;
            }
            Map<String, String> messages = new LinkedHashMap<>();
            values.forEach((key, value) -> messages.put(String.valueOf(key), String.valueOf(value)));
            LanguageSection previous = languages.putIfAbsent(
                normalized, new LanguageSection(name, messages));
            if (previous != null) {
                throw new IllegalArgumentException(source + " contains ambiguous language sections '"
                    + previous.name() + "' and '" + name + "'");
            }
        }
        return new Catalog(configuredLanguage, languages);
    }

    private static void copyBundledMessages(Path target) throws IOException {
        try (InputStream input = PluginMessages.class.getClassLoader().getResourceAsStream(FILE_NAME)) {
            if (input == null) {
                throw new IOException("Bundled messages.yml is missing");
            }
            Files.copy(input, target, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    private static void writeAtomically(Path target, String content) throws IOException {
        Path absoluteTarget = target.toAbsolutePath();
        Path parent = absoluteTarget.getParent();
        if (parent == null) {
            throw new IOException("messages.yml has no parent directory");
        }
        Path temporary = Files.createTempFile(parent, ".messages-", ".tmp");
        try {
            Files.writeString(temporary, content, StandardCharsets.UTF_8);
            try {
                Files.move(temporary, absoluteTarget,
                    StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            }
            catch (AtomicMoveNotSupportedException exception) {
                Files.move(temporary, absoluteTarget, StandardCopyOption.REPLACE_EXISTING);
            }
        }
        finally {
            Files.deleteIfExists(temporary);
        }
    }

    private static String normalizeLanguage(String language) {
        return language == null
            ? ""
            : language.trim().replace('-', '_').toLowerCase(Locale.ROOT);
    }

    private static String yamlScalar(String value) {
        if (PLAIN_YAML_VALUE.matcher(value).matches() && !isReservedYamlScalar(value)) {
            return value;
        }
        return '"' + value
            .replace("\\", "\\\\")
            .replace("\"", "\\\"")
            .replace("\r", "\\r")
            .replace("\n", "\\n")
            .replace("\t", "\\t") + '"';
    }

    private static String preservedLineSuffix(String lineValueAndComment) {
        int commentStart = yamlCommentStart(lineValueAndComment);
        int suffixStart = commentStart < 0 ? lineValueAndComment.length() : commentStart;
        while (suffixStart > 0 && isHorizontalWhitespace(lineValueAndComment.charAt(suffixStart - 1))) {
            suffixStart--;
        }
        if (commentStart == 0) {
            return " " + lineValueAndComment;
        }
        if (commentStart < 0) {
            while (suffixStart > 0 && isHorizontalWhitespace(lineValueAndComment.charAt(suffixStart - 1))) {
                suffixStart--;
            }
        }
        return lineValueAndComment.substring(suffixStart);
    }

    private static int yamlCommentStart(String value) {
        boolean singleQuoted = false;
        boolean doubleQuoted = false;
        boolean escaped = false;
        for (int index = 0; index < value.length(); index++) {
            char character = value.charAt(index);
            if (doubleQuoted) {
                if (escaped) {
                    escaped = false;
                }
                else if (character == '\\') {
                    escaped = true;
                }
                else if (character == '"') {
                    doubleQuoted = false;
                }
                continue;
            }
            if (singleQuoted) {
                if (character == '\'' && index + 1 < value.length()
                    && value.charAt(index + 1) == '\'') {
                    index++;
                }
                else if (character == '\'') {
                    singleQuoted = false;
                }
                continue;
            }
            if (character == '"') {
                doubleQuoted = true;
            }
            else if (character == '\'') {
                singleQuoted = true;
            }
            else if (character == '#'
                && (index == 0 || isHorizontalWhitespace(value.charAt(index - 1)))) {
                return index;
            }
        }
        return -1;
    }

    private static boolean isHorizontalWhitespace(char character) {
        return character == ' ' || character == '\t';
    }

    private static boolean isReservedYamlScalar(String value) {
        return switch (value.toLowerCase(Locale.ROOT)) {
            case "null", "true", "false", "yes", "no", "on", "off" -> true;
            default -> false;
        };
    }

    private static Map<String, String> immutableMap(Map<String, String> source) {
        return Collections.unmodifiableMap(new LinkedHashMap<>(source));
    }

    private static void warn(Logger logger, String message, Throwable exception) {
        if (logger == null) {
            return;
        }
        if (exception == null) {
            logger.warn(message);
        }
        else {
            logger.warn(message, exception);
        }
    }

    private record LanguageSection(String name, Map<String, String> messages) {
        private LanguageSection {
            messages = immutableMap(messages);
        }
    }

    private record Catalog(String configuredLanguage, Map<String, LanguageSection> languages) {
        private Catalog {
            languages = Collections.unmodifiableMap(new LinkedHashMap<>(languages));
        }

        private static Catalog empty() {
            return new Catalog(null, Map.of());
        }
    }

    /** Distinguishes an unsupported locale from malformed messages.yml content. */
    public static final class UnknownLanguageException extends IllegalArgumentException {
        private UnknownLanguageException(String message) {
            super(message);
        }
    }
}
