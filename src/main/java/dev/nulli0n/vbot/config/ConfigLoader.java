package dev.nulli0n.vbot.config;

import dev.nulli0n.vbot.config.BotPluginConfig.AuthConfig;
import dev.nulli0n.vbot.config.BotPluginConfig.AuthMode;
import dev.nulli0n.vbot.config.BotPluginConfig.BehaviorConfig;
import dev.nulli0n.vbot.config.BotPluginConfig.BehaviorMode;
import dev.nulli0n.vbot.config.BotPluginConfig.BehaviorPoint;
import dev.nulli0n.vbot.config.BotPluginConfig.BotDefinition;
import dev.nulli0n.vbot.config.BotPluginConfig.ProxyEndpoint;
import dev.nulli0n.vbot.config.BotPluginConfig.ReconnectConfig;
import dev.nulli0n.vbot.config.BotPluginConfig.ResourcePackMode;
import dev.nulli0n.vbot.config.BotPluginConfig.RuntimeConfig;
import dev.nulli0n.vbot.protocol.ProtocolSelection;
import dev.nulli0n.vbot.schedule.DailySchedule;
import org.yaml.snakeyaml.LoaderOptions;
import org.yaml.snakeyaml.Yaml;
import org.yaml.snakeyaml.constructor.SafeConstructor;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.ZoneId;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

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
            copyBundledResource("config.yml", target);
            Path example = dataDirectory.resolve("secrets.yml.example");
            if (Files.notExists(example)) {
                copyBundledResource("secrets.yml.example", example);
            }
        }

        try (InputStream stream = Files.newInputStream(target)) {
            BotPluginConfig base = parse(stream, CredentialResolver.load(dataDirectory));
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

    private static void copyBundledResource(String resource, Path target) throws IOException {
        try (InputStream stream = ConfigLoader.class.getClassLoader().getResourceAsStream(resource)) {
            if (stream == null) {
                throw new IOException("Bundled " + resource + " is missing");
            }
            Files.copy(stream, target, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    static BotPluginConfig parse(InputStream stream) {
        return parse(stream, CredentialResolver.empty());
    }

    private static BotPluginConfig parse(InputStream stream, CredentialResolver credentials) {
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
            reconnectConfig,
            parseSchedules(runtime.get("schedules")),
            text(runtime, "webhook-url", ""),
            parsePresenceRules(runtime.get("presence-rules")),
            text(runtime, "prometheus-address", "127.0.0.1"),
            integer(runtime, "prometheus-port", 0, 0, 65_535)
        );

        Map<String, Object> templates = section(root, "templates");
        Map<String, BotDefinition> bots = parseBotDefinitions(section(root, "bots"), templates, credentials);
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
        return parseBotDefinitions(section(root, "bots"), Map.of(), CredentialResolver.empty());
    }

    private static Map<String, BotDefinition> parseBotDefinitions(Map<String, Object> source,
                                                                    Map<String, Object> templates,
                                                                    CredentialResolver credentials) {
        Map<String, BotDefinition> bots = new LinkedHashMap<>();
        for (Map.Entry<String, Object> entry : source.entrySet()) {
            String id = entry.getKey();
            Map<String, Object> bot = resolveBot(entry.getValue(), id, templates);
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
                stringList(auth.get("success-messages")),
                stringList(auth.get("failure-messages")),
                longValue(auth, "timeout-ms", 30_000, 0, 3_600_000)
            );
            String username = text(bot, "username", id);
            validateUsername(username, id);
            String password = credentials.resolve(id, bot);
            if (bool(bot, "enabled", false) && authConfig.mode() != AuthMode.NONE && password.isBlank()) {
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
                stringList(bot.get("after-login-commands")),
                identifiers(bot.get("groups"), "bots." + id + ".groups"),
                identifiers(bot.get("tags"), "bots." + id + ".tags"),
                text(bot, "display-name", ""),
                text(bot, "tab-group", ""),
                optionalProtocol(bot, id),
                templateDescription(bot),
                parseBehavior(section(bot, "behavior"), id)
            );
            if (bots.putIfAbsent(id.toLowerCase(Locale.ROOT), definition) != null) {
                throw new IllegalArgumentException("Duplicate bot id ignoring case: " + id);
            }
        }
        return bots;
    }

    private static Map<String, Object> resolveBot(Object rawBot, String id, Map<String, Object> templates) {
        Map<String, Object> bot = castMap(rawBot, "bots." + id);
        Map<String, Object> result = new LinkedHashMap<>();
        List<String> names = templateNames(bot.get("template"), bot.get("templates"), "bots." + id);
        for (String template : names) {
            mergeInto(result, resolveTemplate(template, templates, new LinkedHashSet<>()));
        }
        mergeInto(result, bot);
        result.remove("template");
        result.remove("templates");
        result.put("_template-source", String.join(",", names));
        return result;
    }

    private static Map<String, Object> resolveTemplate(String name, Map<String, Object> templates,
                                                        Set<String> trail) {
        String key = name.toLowerCase(Locale.ROOT);
        if (!trail.add(key)) {
            throw new IllegalArgumentException("Template inheritance cycle: " + String.join(" -> ", trail)
                + " -> " + name);
        }
        Object raw = templates.entrySet().stream()
            .filter(entry -> entry.getKey().equalsIgnoreCase(name))
            .map(Map.Entry::getValue)
            .findFirst()
            .orElseThrow(() -> new IllegalArgumentException("Unknown bot template: " + name));
        Map<String, Object> template = castMap(raw, "templates." + name);
        Map<String, Object> result = new LinkedHashMap<>();
        for (String parent : templateNames(template.get("template"), template.get("templates"),
            "templates." + name)) {
            mergeInto(result, resolveTemplate(parent, templates, new LinkedHashSet<>(trail)));
        }
        mergeInto(result, template);
        result.remove("template");
        result.remove("templates");
        return result;
    }

    @SuppressWarnings("unchecked")
    private static void mergeInto(Map<String, Object> target, Map<String, Object> override) {
        for (Map.Entry<String, Object> entry : override.entrySet()) {
            Object existing = target.get(entry.getKey());
            Object replacement = entry.getValue();
            if (existing instanceof Map<?, ?> && replacement instanceof Map<?, ?>) {
                Map<String, Object> merged = new LinkedHashMap<>((Map<String, Object>) existing);
                mergeInto(merged, castMap(replacement, entry.getKey()));
                target.put(entry.getKey(), merged);
            }
            else {
                target.put(entry.getKey(), replacement);
            }
        }
    }

    private static List<String> templateNames(Object one, Object many, String path) {
        List<String> names = new ArrayList<>();
        if (one != null) {
            names.add(String.valueOf(one).trim());
        }
        if (many instanceof List<?> list) {
            list.forEach(value -> names.add(String.valueOf(value).trim()));
        }
        else if (many != null) {
            names.add(String.valueOf(many).trim());
        }
        for (String name : names) {
            if (name.isBlank()) {
                throw new IllegalArgumentException(path + " contains an empty template name");
            }
        }
        return names;
    }

    private static List<String> identifiers(Object value, String path) {
        List<String> result = new ArrayList<>();
        for (String candidate : stringList(value)) {
            String normalized = candidate.trim().toLowerCase(Locale.ROOT);
            if (!normalized.matches("[a-z0-9][a-z0-9_-]{0,31}")) {
                throw new IllegalArgumentException(path + " contains an invalid identifier: " + candidate);
            }
            if (!result.contains(normalized)) {
                result.add(normalized);
            }
        }
        return result;
    }

    private static ProtocolSelection optionalProtocol(Map<String, Object> bot, String id) {
        String raw = text(bot, "protocol-version", "");
        return raw.isBlank() ? null : ProtocolSelection.parse(raw);
    }

    private static String templateDescription(Map<String, Object> bot) {
        return text(bot, "_template-source", "");
    }

    private static BehaviorConfig parseBehavior(Map<String, Object> behavior, String id) {
        BehaviorMode mode = enumValue(BehaviorMode.class, text(behavior, "mode", "STATIC"),
            "bots." + id + ".behavior.mode");
        boolean enabled = bool(behavior, "enabled", false);
        long interval = longValue(behavior, "interval-ms", 5_000L, 250L, 3_600_000L);
        double radius = doubleValue(behavior, "movement-radius", 0.0D, 0.0D, 16.0D);
        float yawStep = (float) doubleValue(behavior, "yaw-step", 15.0D, 0.0D, 360.0D);
        boolean randomYaw = bool(behavior, "random-yaw", false);
        boolean jump = bool(behavior, "jump", false);
        boolean swing = bool(behavior, "swing", false);
        boolean sneak = bool(behavior, "sneak", false);
        List<String> commands = stringList(behavior.get("commands"));
        List<BehaviorPoint> path = behaviorPath(behavior.get("path"), id);
        List<String> serverCycle = stringList(behavior.get("server-cycle"));
        int serverCycleEvery = integer(behavior, "server-cycle-every", 0, 0, 1_000_000);
        String followPlayer = text(behavior, "follow-player", "");
        if (mode == BehaviorMode.COMMAND && enabled && commands.isEmpty()) {
            throw new IllegalArgumentException("bots." + id + ".behavior.commands is required for COMMAND mode");
        }
        if (mode == BehaviorMode.FARM && enabled && radius <= 0.0D
            && yawStep <= 0.0F) {
            throw new IllegalArgumentException("bots." + id
                + ".behavior needs movement-radius or yaw-step for " + mode);
        }
        if (mode == BehaviorMode.PATROL && enabled && path.isEmpty() && radius <= 0.0D) {
            throw new IllegalArgumentException("bots." + id
                + ".behavior.path or movement-radius is required for PATROL");
        }
        if (mode == BehaviorMode.FOLLOW && enabled && followPlayer.isBlank()) {
            throw new IllegalArgumentException("bots." + id + ".behavior.follow-player is required for FOLLOW");
        }
        return new BehaviorConfig(mode, enabled, interval, radius, yawStep, randomYaw, jump, swing, sneak, commands, path,
            serverCycle, serverCycleEvery, followPlayer);
    }

    private static List<BotPluginConfig.ScheduledAction> parseSchedules(Object value) {
        if (!(value instanceof List<?> list)) {
            return List.of();
        }
        List<BotPluginConfig.ScheduledAction> result = new ArrayList<>();
        Set<String> ids = new HashSet<>();
        for (Object entry : list) {
            Map<String, Object> schedule = castMap(entry, "runtime.schedules");
            String id = text(schedule, "id", "");
            if (!id.matches("[A-Za-z0-9_-]{1,32}") || !ids.add(id.toLowerCase(Locale.ROOT))) {
                throw new IllegalArgumentException("runtime.schedules requires unique id values");
            }
            String action = text(schedule, "action", "").toLowerCase(Locale.ROOT);
            if (!List.of("start", "stop", "reconnect", "server").contains(action)) {
                throw new IllegalArgumentException("runtime.schedules." + id + ".action is invalid");
            }
            String selector = text(schedule, "selector", "");
            if (selector.isBlank()) {
                throw new IllegalArgumentException("runtime.schedules." + id + ".selector is required");
            }
            String server = text(schedule, "server", "");
            if (action.equals("server") && server.isBlank()) {
                throw new IllegalArgumentException("runtime.schedules." + id + ".server is required for server action");
            }
            String at = text(schedule, "at", "");
            String timezone = text(schedule, "timezone", "UTC");
            if (!at.isBlank()) {
                try {
                    DailySchedule.parseTime(at);
                }
                catch (DateTimeParseException exception) {
                    throw new IllegalArgumentException("runtime.schedules." + id + ".at must use HH:mm", exception);
                }
                try {
                    ZoneId.of(timezone);
                }
                catch (java.time.DateTimeException exception) {
                    throw new IllegalArgumentException("runtime.schedules." + id + ".timezone is invalid", exception);
                }
            }
            result.add(new BotPluginConfig.ScheduledAction(id, action, selector, server,
                longValue(schedule, "initial-delay-ms", 0L, 0L, 86_400_000L),
                longValue(schedule, "interval-ms", 60_000L, 1_000L, 604_800_000L), at, timezone));
        }
        return List.copyOf(result);
    }

    private static List<BotPluginConfig.PresenceRule> parsePresenceRules(Object value) {
        if (!(value instanceof List<?> list)) {
            return List.of();
        }
        List<BotPluginConfig.PresenceRule> result = new ArrayList<>();
        Set<String> ids = new HashSet<>();
        for (Object entry : list) {
            Map<String, Object> rule = castMap(entry, "runtime.presence-rules");
            String id = text(rule, "id", "");
            if (!id.matches("[A-Za-z0-9_-]{1,32}") || !ids.add(id.toLowerCase(Locale.ROOT))) {
                throw new IllegalArgumentException("runtime.presence-rules requires unique id values");
            }
            String server = text(rule, "server", "");
            String selector = text(rule, "selector", "");
            if (server.isBlank() || selector.isBlank()) {
                throw new IllegalArgumentException("runtime.presence-rules." + id
                    + " requires server and selector");
            }
            result.add(new BotPluginConfig.PresenceRule(id, server, selector,
                integer(rule, "minimum-bots", 1, 0, 1_000),
                integer(rule, "maximum-humans", 0, 0, 1_000),
                longValue(rule, "interval-ms", 30_000L, 1_000L, 3_600_000L)));
        }
        return List.copyOf(result);
    }

    private static List<BehaviorPoint> behaviorPath(Object value, String id) {
        if (!(value instanceof List<?> list)) {
            return List.of();
        }
        List<BehaviorPoint> result = new ArrayList<>();
        for (Object item : list) {
            Map<String, Object> point = castMap(item, "bots." + id + ".behavior.path");
            result.add(new BehaviorPoint(
                doubleValue(point, "x", 0.0D, -30_000_000D, 30_000_000D),
                doubleValue(point, "y", 0.0D, -2_048D, 2_048D),
                doubleValue(point, "z", 0.0D, -30_000_000D, 30_000_000D)
            ));
        }
        return List.copyOf(result);
    }

    private static final class CredentialResolver {
        private final Map<String, String> secrets;

        private CredentialResolver(Map<String, String> secrets) {
            this.secrets = Map.copyOf(secrets);
        }

        static CredentialResolver empty() {
            return new CredentialResolver(Map.of());
        }

        static CredentialResolver load(Path dataDirectory) throws IOException {
            Path secretsPath = dataDirectory.resolve("secrets.yml");
            if (Files.notExists(secretsPath)) {
                return empty();
            }
            LoaderOptions options = new LoaderOptions();
            options.setAllowDuplicateKeys(false);
            try (InputStream stream = Files.newInputStream(secretsPath)) {
                Object loaded = new Yaml(new SafeConstructor(options)).load(stream);
                if (loaded == null) {
                    return empty();
                }
                Map<String, Object> root = castMap(loaded, "secrets.yml root");
                Map<String, String> values = new LinkedHashMap<>();
                Map<String, Object> passwords = section(root, "passwords");
                passwords.forEach((name, value) -> values.put(normalizeSecretName(name), String.valueOf(value)));
                Map<String, Object> bots = section(root, "bots");
                bots.forEach((name, value) -> {
                    if (value instanceof Map<?, ?>) {
                        String password = text(castMap(value, "secrets.yml bots." + name), "password", "");
                        if (!password.isBlank()) {
                            values.putIfAbsent(normalizeSecretName(name), password);
                        }
                    }
                });
                return new CredentialResolver(values);
            }
        }

        String resolve(String botId, Map<String, Object> bot) {
            String inline = text(bot, "password", "");
            String environment = text(bot, "password-env", "");
            String secret = text(bot, "password-secret", "");
            int sources = (inline.isBlank() ? 0 : 1) + (environment.isBlank() ? 0 : 1) + (secret.isBlank() ? 0 : 1);
            if (sources > 1) {
                throw new IllegalArgumentException("bots." + botId
                    + " must define only one of password, password-env or password-secret");
            }
            if (!environment.isBlank()) {
                String value = System.getenv(environment);
                if (value == null || value.isBlank()) {
                    if (!bool(bot, "enabled", false)) {
                        return "";
                    }
                    throw new IllegalArgumentException("bots." + botId + ".password-env is not set: " + environment);
                }
                return value;
            }
            if (!secret.isBlank()) {
                String value = secrets.get(normalizeSecretName(secret));
                if (value == null || value.isBlank()) {
                    if (!bool(bot, "enabled", false)) {
                        return "";
                    }
                    throw new IllegalArgumentException("bots." + botId + ".password-secret was not found: " + secret);
                }
                return value;
            }
            return inline;
        }

        private static String normalizeSecretName(String value) {
            return value == null ? "" : value.trim().toLowerCase(Locale.ROOT);
        }
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
