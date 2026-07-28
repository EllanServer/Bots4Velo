package dev.nulli0n.vbot.config;

import dev.nulli0n.vbot.config.BotPluginConfig.AuthConfig;
import dev.nulli0n.vbot.config.BotPluginConfig.AuthMode;
import dev.nulli0n.vbot.config.BotPluginConfig.BotDefinition;
import org.yaml.snakeyaml.DumperOptions;
import org.yaml.snakeyaml.Yaml;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Stores bots created through /vbot separately from the operator-maintained
 * config.yml. This keeps comments and hand-edited formatting in config.yml intact.
 */
public final class ManagedBotStore {
    public static final String FILE_NAME = "managed-bots.yml";

    private final Path path;
    private Map<String, BotDefinition> definitions;

    private ManagedBotStore(Path path, Map<String, BotDefinition> definitions) {
        this.path = path;
        this.definitions = new LinkedHashMap<>(definitions);
    }

    public static ManagedBotStore load(Path dataDirectory) throws IOException {
        Files.createDirectories(dataDirectory);
        Path path = dataDirectory.resolve(FILE_NAME);
        if (Files.notExists(path)) {
            return new ManagedBotStore(path, Map.of());
        }
        try (InputStream stream = Files.newInputStream(path)) {
            return new ManagedBotStore(path, ConfigLoader.parseManagedBots(stream));
        }
    }

    public synchronized Map<String, BotDefinition> definitions() {
        return Map.copyOf(new LinkedHashMap<>(definitions));
    }

    public synchronized boolean contains(String id) {
        return definitions.containsKey(normalizeId(id));
    }

    public synchronized void add(BotDefinition definition) throws IOException {
        String key = normalizeId(definition.id());
        if (definitions.containsKey(key)) {
            throw new IllegalArgumentException("Managed bot already exists: " + definition.id());
        }
        Map<String, BotDefinition> replacement = new LinkedHashMap<>(definitions);
        replacement.put(key, definition);
        persist(replacement);
        definitions = replacement;
    }

    public synchronized boolean remove(String id) throws IOException {
        String key = normalizeId(id);
        if (!definitions.containsKey(key)) {
            return false;
        }
        Map<String, BotDefinition> replacement = new LinkedHashMap<>(definitions);
        replacement.remove(key);
        persist(replacement);
        definitions = replacement;
        return true;
    }

    public static BotDefinition createDefinition(String id, String username, String password,
                                                  String targetServer) {
        String trimmedId = id == null ? "" : id.trim();
        String trimmedUsername = username == null ? "" : username.trim();
        String trimmedPassword = password == null ? "" : password;
        String trimmedTarget = targetServer == null ? "" : targetServer.trim();
        if (!trimmedId.matches("[A-Za-z0-9_-]{1,32}")) {
            throw new IllegalArgumentException("Bot id must use 1-32 letters, digits, '_' or '-'");
        }
        if (!trimmedUsername.matches("[A-Za-z0-9_]{3,16}")) {
            throw new IllegalArgumentException("Username must be a valid offline Minecraft name");
        }
        if (!trimmedTarget.isBlank() && !trimmedTarget.matches("[A-Za-z0-9_-]{1,64}")) {
            throw new IllegalArgumentException("Target server contains unsupported characters");
        }

        AuthMode authMode = trimmedPassword.isBlank() ? AuthMode.NONE : AuthMode.AUTO;
        AuthConfig auth = new AuthConfig(
            authMode,
            "login {password}",
            "register {password} {password}",
            1_000,
            2_500,
            1_500,
            List.of("(?i)(please login|/login|请登录|请输入密码)"),
            List.of("(?i)(please register|/register|请注册)"),
            List.of("(?i)(account registered successfully|successfully registered|logged in successfully|login successful|successful login|successfully logged|^authenticated$|登录成功|注册成功|认证成功)")
        );
        return new BotDefinition(
            trimmedId, true, trimmedUsername, trimmedPassword, trimmedTarget, trimmedTarget, 2, auth,
            "server {server}", 3_000, 0, List.of()
        );
    }

    private void persist(Map<String, BotDefinition> replacement) throws IOException {
        Map<String, Object> serializedBots = new LinkedHashMap<>();
        replacement.values().forEach(definition -> serializedBots.put(definition.id(), serialize(definition)));
        Map<String, Object> root = new LinkedHashMap<>();
        root.put("bots", serializedBots);

        DumperOptions options = new DumperOptions();
        options.setDefaultFlowStyle(DumperOptions.FlowStyle.BLOCK);
        options.setPrettyFlow(true);
        options.setIndent(2);
        String yaml = new Yaml(options).dump(root);

        Path temporary = Files.createTempFile(path.getParent(), "managed-bots-", ".tmp");
        try {
            Files.writeString(temporary, yaml, StandardCharsets.UTF_8);
            try {
                Files.move(temporary, path, StandardCopyOption.ATOMIC_MOVE,
                    StandardCopyOption.REPLACE_EXISTING);
            }
            catch (AtomicMoveNotSupportedException exception) {
                Files.move(temporary, path, StandardCopyOption.REPLACE_EXISTING);
            }
        }
        finally {
            Files.deleteIfExists(temporary);
        }
    }

    private static Map<String, Object> serialize(BotDefinition definition) {
        Map<String, Object> auth = new LinkedHashMap<>();
        auth.put("mode", definition.auth().mode().name());
        auth.put("login-command", definition.auth().loginCommand());
        auth.put("register-command", definition.auth().registerCommand());
        auth.put("login-delay-ms", definition.auth().loginDelayMillis());
        auth.put("fallback-register-delay-ms", definition.auth().fallbackRegisterDelayMillis());
        auth.put("after-auth-delay-ms", definition.auth().afterAuthDelayMillis());
        auth.put("login-prompts", definition.auth().loginPrompts());
        auth.put("register-prompts", definition.auth().registerPrompts());
        auth.put("success-messages", definition.auth().successMessages());

        Map<String, Object> bot = new LinkedHashMap<>();
        bot.put("enabled", definition.enabled());
        bot.put("username", definition.username());
        bot.put("password", definition.password());
        bot.put("target-server", definition.targetServer());
        bot.put("protocol-detection-server", definition.protocolDetectionServer());
        bot.put("render-distance", definition.renderDistance());
        bot.put("auth", auth);
        bot.put("server-switch-command", definition.serverSwitchCommand());
        bot.put("server-switch-delay-ms", definition.serverSwitchDelayMillis());
        bot.put("server-switch-maximum-attempts", definition.serverSwitchMaximumAttempts());
        bot.put("after-login-commands", definition.afterLoginCommands());
        return bot;
    }

    private static String normalizeId(String id) {
        return id == null ? "" : id.trim().toLowerCase(Locale.ROOT);
    }
}
