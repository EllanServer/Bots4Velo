package dev.nulli0n.vbot.message;

import org.slf4j.Logger;
import org.yaml.snakeyaml.LoaderOptions;
import org.yaml.snakeyaml.Yaml;
import org.yaml.snakeyaml.constructor.SafeConstructor;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

/** Small, editable message file with English as the safe fallback. */
public final class PluginMessages {
    private final String language;
    private final Map<String, String> selected;

    private PluginMessages(String language, Map<String, String> selected) {
        this.language = language;
        this.selected = Map.copyOf(selected);
    }

    public static PluginMessages load(Path dataDirectory, Logger logger) {
        Path target = dataDirectory.resolve("messages.yml");
        try {
            Files.createDirectories(dataDirectory);
            if (Files.notExists(target)) {
                try (InputStream input = PluginMessages.class.getClassLoader().getResourceAsStream("messages.yml")) {
                    if (input == null) {
                        throw new IOException("Bundled messages.yml is missing");
                    }
                    Files.copy(input, target, StandardCopyOption.REPLACE_EXISTING);
                }
            }
            LoaderOptions options = new LoaderOptions();
            options.setAllowDuplicateKeys(false);
            try (InputStream input = Files.newInputStream(target)) {
                Object loaded = new Yaml(new SafeConstructor(options)).load(input);
                if (!(loaded instanceof Map<?, ?> root)) {
                    throw new IllegalArgumentException("messages.yml root must be a map");
                }
                Object selectedLanguage = root.containsKey("language") ? root.get("language") : "en_US";
                String language = String.valueOf(selectedLanguage).trim();
                Object translation = root.get(language);
                if (!(translation instanceof Map<?, ?> values)) {
                    throw new IllegalArgumentException("messages.yml has no translation map for " + language);
                }
                Map<String, String> result = new LinkedHashMap<>();
                values.forEach((key, value) -> result.put(String.valueOf(key), String.valueOf(value)));
                return new PluginMessages(language, result);
            }
        }
        catch (Exception exception) {
            logger.warn("Could not load messages.yml; using built-in English", exception);
            return new PluginMessages("en_US", Map.of());
        }
    }

    public String language() {
        return language;
    }

    public String text(String key, String fallback, Object... arguments) {
        String value = selected.getOrDefault(key, fallback);
        return String.format(Locale.ROOT, value, arguments);
    }
}
