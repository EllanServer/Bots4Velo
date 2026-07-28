package dev.nulli0n.vbot.config;

import dev.nulli0n.vbot.config.BotPluginConfig.AuthConfig;
import dev.nulli0n.vbot.config.BotPluginConfig.AuthMode;
import dev.nulli0n.vbot.config.BotPluginConfig.BotDefinition;
import dev.nulli0n.vbot.config.BotPluginConfig.ProxyEndpoint;
import dev.nulli0n.vbot.config.BotPluginConfig.ReconnectConfig;
import dev.nulli0n.vbot.config.BotPluginConfig.ResourcePackMode;
import dev.nulli0n.vbot.config.BotPluginConfig.RuntimeConfig;
import dev.nulli0n.vbot.protocol.ProtocolSelection;
import org.yaml.snakeyaml.LoaderOptions;
import org.yaml.snakeyaml.Yaml;
import org.yaml.snakeyaml.constructor.SafeConstructor;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

public final class ConfigLoader {
    private ConfigLoader() {
    }

    public static BotPluginConfig load(Path dataDirectory) throws IOException {
        ManagedBotStore managedBotStore = ManagedBotStore.load(dataDirectory);
        return load(dataDirectory, managedBotStore.definitions());
    }

    public static BotPluginConfig load(Path dataDirectory, Map<String, BotDefinition> managedBots) throws IOException {
        Files.createDirectories(dataDirectory);
        Path target = dataDirectory.resolve("config.yml");
        if (Files.notExists(target)) {
            try (InputStream stream = ConfigLoader.class.getClassLoader().getResourceAsStream("config.yml")) {
                if (stream == null) {
                    throw new IOException("Bundled config.yml is missing");
                }
                Files.copy(stream, target, StandardCopyOption.REPLACE_EXISTING);
            }
        }

        try (InputStream stream = Files.newInputStream(target)) {
            BotPluginConfig base = parse(stream);
            Map<String, BotDefinition> merged = new LinkedHashMap<>(base.bots());
            for (Map.Entry<String, BotDefinition> entry : managedBots.entrySet()) {
                if (merged.putIfAbsent(entry.getKey(), entry.getValue()) != null) {
                    throw new IllegalArgumentException("Managed bot conflicts with config.yml: "
                        + entry.getValue().id());
                }
            }
            if (merged.size() > base.runtime().maximumBots()) {
                throw new IllegalArgumentException("Configured bot count " + merged.size()
                    + " exceeds runtime.maximum-bots " + base.runtime().maximumBots());
            }
            return new BotPluginConfig(base.proxy(), base.runtime(), merged);
        }
    }

    static BotPluginConfig parse(InputStream stream) {
        LoaderOptions options = new LoaderOptions();
        options.setAllowDuplicateKeys(false);
        Yaml yaml = new Yaml(new SafeConstructor(options));
        Map<String, Object> root = castMap(yaml.load(stream), "root");

        Map<String, Object> proxy = section(root, "proxy");
        ProxyEndpoint endpoint = new ProxyEndpoint(
            text(proxy, "address", "127.0.0.1"),
            integer(proxy, "port", 25565, 1, 65535),
            text(proxy, "virtual-host", "localhost"),
            integer(proxy, "virtual-port", 25565, 1, 65535),
            ProtocolSelection.parse(text(proxy, "protocol-version", "AUTO")),
            integer(proxy, "protocol-detection-timeout-ms", 3_000, 250, 30_000)
        );

        Map<String, Object> runtime = section(root, "runtime");
        Map<String, Object> reconnect = section(runtime, "reconnect");
        ReconnectConfig reconnectConfig = new ReconnectConfig(
            longValue(reconnect, "initial-delay-ms", 5_000, 0, 3_600_000),
            longValue(reconnect, "maximum-delay-ms", 60_000, 100, 3_600_000),
            doubleValue(reconnect, "multiplier", 2.0, 1.0, 10.0),
            doubleValue(reconnect, "jitter", 0.15, 0.0, 1.0),
            integer(reconnect, "maximum-attempts", 0, 0, 1_000_000)
        );
        if (reconnectConfig.maximumDelayMillis() < reconnectConfig.initialDelayMillis()) {
            throw new IllegalArgumentException("reconnect.maximum-delay-ms must be >= initial-delay-ms");
        }
        RuntimeConfig runtimeConfig = new RuntimeConfig(
            longValue(runtime, "auto-start-delay-ms", 3_000, 0, 3_600_000),
            longValue(runtime, "spawn-interval-ms", 1_500, 0, 60_000),
            integer(runtime, "maximum-bots", 32, 1, 1_000),
            longValue(runtime, "command-interval-ms", 750, 0, 60_000),
            longValue(runtime, "resource-pack-step-delay-ms", 150, 0, 60_000),
            enumValue(ResourcePackMode.class, text(runtime, "resource-pack-mode", "ACCEPT_WITHOUT_DOWNLOAD"),
                "runtime.resource-pack-mode"),
            bool(runtime, "auto-respawn", true),
            reconnectConfig
        );

        Map<String, BotDefinition> bots = parseBotDefinitions(section(root, "bots"));
        if (bots.size() > runtimeConfig.maximumBots()) {
            throw new IllegalArgumentException("Configured bot count " + bots.size()
                + " exceeds runtime.maximum-bots " + runtimeConfig.maximumBots());
        }

        return new BotPluginConfig(endpoint, runtimeConfig, bots);
    }

    static Map<String, BotDefinition> parseManagedBots(InputStream stream) {
        LoaderOptions options = new LoaderOptions();
        options.setAllowDuplicateKeys(false);
        Yaml yaml = new Yaml(new SafeConstructor(options));
        Object loaded = yaml.load(stream);
        if (loaded == null) {
            return Map.of();
        }
        Map<String, Object> root = castMap(loaded, "root");
        return parseBotDefinitions(section(root, "bots"));
    }

    private static Map<String, BotDefinition> parseBotDefinitions(Map<String, Object> source) {
        Map<String, BotDefinition> bots = new LinkedHashMap<>();
        for (Map.Entry<String, Object> entry : source.entrySet()) {
            String id = entry.getKey();
            Map<String, Object> bot = castMap(entry.getValue(), "bots." + id);
            Map<String, Object> auth = section(bot, "auth");
            AuthConfig authConfig = new AuthConfig(
                enumValue(AuthMode.class, text(auth, "mode", "AUTO"), "bots." + id + ".auth.mode"),
                text(auth, "login-command", "login {password}"),
                text(auth, "register-command", "register {password} {password}"),
                longValue(auth, "login-delay-ms", 1_000, 0, 60_000),
                longValue(auth, "fallback-register-delay-ms", 2_500, 0, 60_000),
                longValue(auth, "after-auth-delay-ms", 1_500, 0, 60_000),
                stringList(auth.get("login-prompts")),
                stringList(auth.get("register-prompts")),
                stringList(auth.get("success-messages"))
            );
            String username = text(bot, "username", id);
            validateUsername(username, id);
            String password = text(bot, "password", "");
            if (authConfig.mode() != AuthMode.NONE && password.isBlank()) {
                throw new IllegalArgumentException("bots." + id + ".password is required for auth mode " + authConfig.mode());
            }
            String targetServer = text(bot, "target-server", "");
            String serverSwitchCommand = text(bot, "server-switch-command", "server {server}");
            if (!targetServer.isBlank() && serverSwitchCommand.isBlank()) {
                throw new IllegalArgumentException("bots." + id
                    + ".server-switch-command is required when target-server is configured");
            }
            BotDefinition definition = new BotDefinition(
                id,
                bool(bot, "enabled", false),
                username,
                password,
                targetServer,
                text(bot, "protocol-detection-server", ""),
                integer(bot, "render-distance", 2, 2, 32),
                authConfig,
                serverSwitchCommand,
                longValue(bot, "server-switch-delay-ms", 3_000, 0, 60_000),
                integer(bot, "server-switch-maximum-attempts", 0, 0, 1_000_000),
                stringList(bot.get("after-login-commands"))
            );
            if (bots.putIfAbsent(id.toLowerCase(Locale.ROOT), definition) != null) {
                throw new IllegalArgumentException("Duplicate bot id ignoring case: " + id);
            }
        }
        return bots;
    }

    private static void validateUsername(String username, String id) {
        if (!username.matches("[A-Za-z0-9_]{3,16}")) {
            throw new IllegalArgumentException("bots." + id + ".username must be a valid offline Minecraft name");
        }
    }

    private static Map<String, Object> section(Map<String, Object> root, String name) {
        Object value = root.get(name);
        return value == null ? Map.of() : castMap(value, name);
    }

    private static Map<String, Object> castMap(Object value, String name) {
        if (!(value instanceof Map<?, ?> map)) {
            throw new IllegalArgumentException("Expected YAML map at " + name);
        }
        Map<String, Object> result = new LinkedHashMap<>();
        map.forEach((key, item) -> result.put(String.valueOf(key), item));
        return result;
    }

    private static String text(Map<String, Object> map, String key, String fallback) {
        Object value = map.get(key);
        return value == null ? fallback : String.valueOf(value).trim();
    }

    private static boolean bool(Map<String, Object> map, String key, boolean fallback) {
        Object value = map.get(key);
        return value == null ? fallback : Boolean.parseBoolean(String.valueOf(value));
    }

    private static int integer(Map<String, Object> map, String key, int fallback, int min, int max) {
        Object value = map.get(key);
        int parsed = value == null ? fallback : Integer.parseInt(String.valueOf(value));
        if (parsed < min || parsed > max) {
            throw new IllegalArgumentException(key + " must be between " + min + " and " + max);
        }
        return parsed;
    }

    private static long longValue(Map<String, Object> map, String key, long fallback, long min, long max) {
        Object value = map.get(key);
        long parsed = value == null ? fallback : Long.parseLong(String.valueOf(value));
        if (parsed < min || parsed > max) {
            throw new IllegalArgumentException(key + " must be between " + min + " and " + max);
        }
        return parsed;
    }

    private static double doubleValue(Map<String, Object> map, String key, double fallback, double min, double max) {
        Object value = map.get(key);
        double parsed = value == null ? fallback : Double.parseDouble(String.valueOf(value));
        if (!Double.isFinite(parsed) || parsed < min || parsed > max) {
            throw new IllegalArgumentException(key + " must be between " + min + " and " + max);
        }
        return parsed;
    }

    private static List<String> stringList(Object value) {
        if (!(value instanceof List<?> list)) {
            return List.of();
        }
        List<String> result = new ArrayList<>();
        list.forEach(item -> result.add(String.valueOf(item)));
        return result;
    }

    private static <E extends Enum<E>> E enumValue(Class<E> type, String raw, String path) {
        try {
            return Enum.valueOf(type, raw.toUpperCase(Locale.ROOT));
        }
        catch (IllegalArgumentException exception) {
            throw new IllegalArgumentException("Invalid value at " + path + ": " + raw, exception);
        }
    }
}
