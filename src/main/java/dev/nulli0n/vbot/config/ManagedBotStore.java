package dev.nulli0n.vbot.config;

import dev.nulli0n.vbot.config.BotPluginConfig.AuthConfig;
import dev.nulli0n.vbot.config.BotPluginConfig.AuthMode;
import dev.nulli0n.vbot.config.BotPluginConfig.BotDefinition;
import org.yaml.snakeyaml.DumperOptions;
import org.yaml.snakeyaml.LoaderOptions;
import org.yaml.snakeyaml.Yaml;
import org.yaml.snakeyaml.constructor.SafeConstructor;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;

/**
 * Stores bots created through /vbot separately from the operator-maintained
 * config.yml. This keeps comments and hand-edited formatting in config.yml intact.
 */
public final class ManagedBotStore {
    public static final String FILE_NAME = "managed-bots.yml";

    private final Path path;
    private final Map<String, String> environment;
    private Map<String, BotDefinition> definitions;
    private Map<String, StoredCredential> credentials;

    private ManagedBotStore(Path path, Map<String, String> environment,
                            Map<String, BotDefinition> definitions,
                            Map<String, StoredCredential> credentials) {
        this.path = path;
        this.environment = Map.copyOf(environment);
        this.definitions = new LinkedHashMap<>(definitions);
        this.credentials = new LinkedHashMap<>(credentials);
    }

    public static ManagedBotStore load(Path dataDirectory) throws IOException {
        return load(dataDirectory, System.getenv());
    }

    static ManagedBotStore load(Path dataDirectory, Map<String, String> environment) throws IOException {
        Files.createDirectories(dataDirectory);
        Path path = dataDirectory.resolve(FILE_NAME);
        if (Files.notExists(path)) {
            return new ManagedBotStore(path, environment, Map.of(), Map.of());
        }
        byte[] document = Files.readAllBytes(path);
        Map<String, StoredCredential> storedCredentials = parseStoredCredentials(document);
        Map<String, BotDefinition> definitions = ConfigLoader.parseManagedBots(
            new ByteArrayInputStream(document), CredentialResolver.load(dataDirectory), environment);
        if (!storedCredentials.keySet().equals(definitions.keySet())) {
            throw new IllegalArgumentException("managed-bots.yml credential metadata does not match its bots");
        }
        return new ManagedBotStore(path, environment, definitions, storedCredentials);
    }

    public synchronized Map<String, BotDefinition> definitions() {
        return Map.copyOf(new LinkedHashMap<>(definitions));
    }

    public synchronized boolean contains(String id) {
        return definitions.containsKey(normalizeId(id));
    }

    /**
     * Adds a managed bot using a persistable credential reference and returns
     * the runtime definition containing the resolved password.
     */
    public synchronized BotDefinition add(BotDefinition definition,
                                          ManagedCredentialReference credential) throws IOException {
        Objects.requireNonNull(definition, "Managed bot definition must not be null");
        Objects.requireNonNull(credential, "Managed credential reference must not be null");
        String key = normalizeId(definition.id());
        if (definitions.containsKey(key)) {
            throw new IllegalArgumentException("Managed bot already exists: " + definition.id());
        }
        CredentialResolver.ResolvedCredential resolved = CredentialResolver.load(path.getParent())
            .resolve(definition.id(), definition.enabled(), credential, environment);
        BotDefinition runtimeDefinition = withCredential(definition, resolved);
        if (runtimeDefinition.enabled() && runtimeDefinition.auth().mode() != AuthMode.NONE
            && runtimeDefinition.password().isBlank()) {
            throw new IllegalArgumentException("bots." + runtimeDefinition.id()
                + ".password is required for auth mode " + runtimeDefinition.auth().mode());
        }
        Map<String, BotDefinition> replacement = new LinkedHashMap<>(definitions);
        replacement.put(key, runtimeDefinition);
        Map<String, StoredCredential> replacementCredentials = new LinkedHashMap<>(credentials);
        replacementCredentials.put(key, ReferencedCredential.strict(credential));
        persist(replacement, replacementCredentials);
        definitions = replacement;
        credentials = replacementCredentials;
        return runtimeDefinition;
    }

    /** Creates and atomically adds a managed bot without accepting a plaintext password. */
    public synchronized BotDefinition add(String id, String username,
                                          ManagedCredentialReference credential,
                                          String targetServer) throws IOException {
        return add(createDefinition(id, username, credential, targetServer), credential);
    }

    /**
     * Compatibility entry point for passwordless definitions. New managed
     * credentials must use {@link #add(BotDefinition, ManagedCredentialReference)}.
     */
    @Deprecated
    public synchronized void add(BotDefinition definition) throws IOException {
        if (!definition.password().isBlank()) {
            throw new IllegalArgumentException(
                "Inline passwords cannot be added; use a managed credential reference");
        }
        add(definition, ManagedCredentialReference.none());
    }

    public synchronized boolean remove(String id) throws IOException {
        String key = normalizeId(id);
        if (!definitions.containsKey(key)) {
            return false;
        }
        Map<String, BotDefinition> replacement = new LinkedHashMap<>(definitions);
        replacement.remove(key);
        Map<String, StoredCredential> replacementCredentials = new LinkedHashMap<>(credentials);
        replacementCredentials.remove(key);
        persist(replacement, replacementCredentials);
        definitions = replacement;
        credentials = replacementCredentials;
        return true;
    }

    public static BotDefinition createDefinition(String id, String username,
                                                  ManagedCredentialReference credential,
                                                  String targetServer) {
        Objects.requireNonNull(credential, "Managed credential reference must not be null");
        return createDefinition(id, username, "", targetServer,
            credential.kind() == ManagedCredentialReference.Kind.NONE ? AuthMode.NONE : AuthMode.AUTO,
            credential.sourceFingerprint());
    }

    /** Legacy source-compatible factory; inline definitions cannot be newly persisted. */
    @Deprecated
    public static BotDefinition createDefinition(String id, String username, String password,
                                                  String targetServer) {
        String trimmedPassword = password == null ? "" : password;
        return createDefinition(id, username, trimmedPassword, targetServer,
            trimmedPassword.isBlank() ? AuthMode.NONE : AuthMode.AUTO,
            trimmedPassword.isBlank() ? "none" : "inline");
    }

    private static BotDefinition createDefinition(String id, String username, String password,
                                                   String targetServer, AuthMode authMode,
                                                   String credentialSourceFingerprint) {
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
        AuthConfig auth = new AuthConfig(
            authMode,
            "login {password}",
            "register {password} {password}",
            1_000,
            2_500,
            1_500,
            List.of("(?i)(please login|/login|请登录|请输入密码)"),
            List.of("(?i)(please register|/register|请注册)"),
            List.of("(?i)(account registered successfully|successfully registered|logged in successfully|login successful|successful login|successfully logged|logged-in due to session reconnection|login session continued|already logged in|^authenticated$|登录成功|注册成功|认证成功)"),
            List.of("(?i)(incorrect password|wrong password|invalid password|captcha|2fa|two-factor|verification code|banned|blacklisted)")
        );
        return new BotDefinition(
            trimmedId, true, trimmedUsername, trimmedPassword, trimmedTarget, trimmedTarget, 2, auth,
            "server {server}", 3_000, 0, List.of(), List.of(), List.of(), "", "", null, "",
            BotPluginConfig.BehaviorConfig.disabled(), BotPluginConfig.PlayerStateConfig.unchanged(),
            credentialSourceFingerprint
        );
    }

    private void persist(Map<String, BotDefinition> replacement,
                         Map<String, StoredCredential> replacementCredentials) throws IOException {
        Map<String, Object> serializedBots = new LinkedHashMap<>();
        replacement.forEach((key, definition) -> serializedBots.put(
            definition.id(), serialize(definition, replacementCredentials.get(key))));
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

    private static Map<String, Object> serialize(BotDefinition definition, StoredCredential credential) {
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
        auth.put("failure-messages", definition.auth().failureMessages());
        auth.put("timeout-ms", definition.auth().timeoutMillis());
        Map<String, Object> authMeUi = new LinkedHashMap<>();
        authMeUi.put("accept-rules", definition.auth().acceptRules());
        authMeUi.put("registration-email", definition.auth().registrationEmail());
        authMeUi.put("registration-second-argument", definition.auth().registrationSecondArgument().name());
        authMeUi.put("ui-detection-grace-ms", definition.auth().uiDetectionGraceMillis());
        auth.put("authmeui", authMeUi);

        Map<String, Object> respawnPoint = new LinkedHashMap<>();
        respawnPoint.put("mode", definition.playerState().respawnPoint().mode().name());
        respawnPoint.put("world", definition.playerState().respawnPoint().world());
        respawnPoint.put("x", definition.playerState().respawnPoint().x());
        respawnPoint.put("y", definition.playerState().respawnPoint().y());
        respawnPoint.put("z", definition.playerState().respawnPoint().z());
        respawnPoint.put("yaw", definition.playerState().respawnPoint().yaw());

        Map<String, Object> playerState = new LinkedHashMap<>();
        playerState.put("afk-preset", definition.playerState().afkPreset().name());
        playerState.put("invulnerable", definition.playerState().invulnerability().name());
        playerState.put("sleep-ignored", definition.playerState().sleepingIgnored().name());
        playerState.put("affects-spawning", definition.playerState().affectsSpawning().name());
        playerState.put("pickup-items", definition.playerState().pickupItems().name());
        playerState.put("collidable", definition.playerState().collidable().name());
        playerState.put("game-mode", definition.playerState().gameMode().name());
        playerState.put("apply-delay-ms", definition.playerState().applyDelayMillis());
        playerState.put("respawn-point", respawnPoint);

        Map<String, Object> behavior = new LinkedHashMap<>();
        behavior.put("mode", definition.behavior().mode().name());
        behavior.put("enabled", definition.behavior().enabled());
        behavior.put("interval-ms", definition.behavior().intervalMillis());
        behavior.put("movement-radius", definition.behavior().movementRadius());
        behavior.put("yaw-step", definition.behavior().yawStep());
        behavior.put("random-yaw", definition.behavior().randomYaw());
        behavior.put("jump", definition.behavior().jump());
        behavior.put("swing", definition.behavior().swing());
        behavior.put("sneak", definition.behavior().sneak());
        behavior.put("commands", definition.behavior().commands());
        behavior.put("path", definition.behavior().path().stream().map(point -> {
            Map<String, Object> serialized = new LinkedHashMap<>();
            serialized.put("x", point.x());
            serialized.put("y", point.y());
            serialized.put("z", point.z());
            return serialized;
        }).toList());
        behavior.put("server-cycle", definition.behavior().serverCycle());
        behavior.put("server-cycle-every", definition.behavior().serverCycleEvery());
        behavior.put("follow-player", definition.behavior().followPlayer());

        Map<String, Object> bot = new LinkedHashMap<>();
        bot.put("enabled", definition.enabled());
        bot.put("username", definition.username());
        credential.writeTo(bot);
        bot.put("target-server", definition.targetServer());
        bot.put("protocol-detection-server", definition.protocolDetectionServer());
        bot.put("render-distance", definition.renderDistance());
        bot.put("auth", auth);
        bot.put("server-switch-command", definition.serverSwitchCommand());
        bot.put("server-switch-delay-ms", definition.serverSwitchDelayMillis());
        bot.put("server-switch-maximum-attempts", definition.serverSwitchMaximumAttempts());
        bot.put("after-login-commands", definition.afterLoginCommands());
        bot.put("groups", definition.groups());
        bot.put("tags", definition.tags());
        bot.put("display-name", definition.displayName());
        bot.put("tab-group", definition.tabGroup());
        if (definition.protocolOverride() != null) {
            bot.put("protocol-version", definition.protocolOverride().automatic()
                ? "AUTO" : definition.protocolOverride().fixedVersion().displayName());
        }
        if (!definition.templateName().isBlank()) {
            // Managed files contain fully expanded definitions; retain only
            // template provenance rather than trying to resolve config.yml
            // templates from this independent document.
            bot.put("_template-source", definition.templateName());
        }
        bot.put("behavior", behavior);
        bot.put("player-state", playerState);
        return bot;
    }

    private static BotDefinition withCredential(BotDefinition definition,
                                                CredentialResolver.ResolvedCredential credential) {
        return new BotDefinition(
            definition.id(), definition.enabled(), definition.username(), credential.value(),
            definition.targetServer(), definition.protocolDetectionServer(), definition.renderDistance(),
            definition.auth(), definition.serverSwitchCommand(), definition.serverSwitchDelayMillis(),
            definition.serverSwitchMaximumAttempts(), definition.afterLoginCommands(), definition.groups(),
            definition.tags(), definition.displayName(), definition.tabGroup(), definition.protocolOverride(),
            definition.templateName(), definition.behavior(), definition.playerState(),
            credential.sourceFingerprint()
        );
    }

    private static Map<String, StoredCredential> parseStoredCredentials(byte[] document) {
        LoaderOptions options = new LoaderOptions();
        options.setAllowDuplicateKeys(false);
        Object loaded = new Yaml(new SafeConstructor(options))
            .load(new ByteArrayInputStream(document));
        if (loaded == null) {
            return Map.of();
        }
        Map<String, Object> root = stringMap(loaded, "managed-bots.yml root");
        Object rawBots = root.get("bots");
        Map<String, Object> bots = rawBots == null
            ? Map.of() : stringMap(rawBots, "managed-bots.yml bots");
        Map<String, StoredCredential> result = new LinkedHashMap<>();
        for (Map.Entry<String, Object> entry : bots.entrySet()) {
            String id = entry.getKey();
            Map<String, Object> bot = stringMap(entry.getValue(), "managed-bots.yml bots." + id);
            String inlineValue = bot.containsKey("password")
                ? scalar(bot.get("password"), "managed-bots.yml bots." + id + ".password") : "";
            String secretReference = bot.containsKey("password-secret")
                ? scalar(bot.get("password-secret"),
                    "managed-bots.yml bots." + id + ".password-secret").trim() : "";
            String environmentReference = bot.containsKey("password-env")
                ? scalar(bot.get("password-env"),
                    "managed-bots.yml bots." + id + ".password-env").trim() : "";
            boolean inline = !inlineValue.trim().isEmpty();
            boolean secret = !secretReference.isEmpty();
            boolean environment = !environmentReference.isEmpty();
            int sources = (inline ? 1 : 0) + (secret ? 1 : 0) + (environment ? 1 : 0);
            if (sources > 1) {
                throw new IllegalArgumentException("bots." + id
                    + " must define only one of password, password-env or password-secret");
            }
            StoredCredential credential;
            if (inline) {
                credential = new LegacyInlineCredential(inlineValue);
            }
            else if (secret) {
                credential = new ReferencedCredential(
                    ManagedCredentialReference.Kind.SECRET, secretReference);
            }
            else if (environment) {
                credential = new ReferencedCredential(
                    ManagedCredentialReference.Kind.ENVIRONMENT, environmentReference);
            }
            else {
                credential = ReferencedCredential.strict(ManagedCredentialReference.none());
            }
            String key = normalizeId(id);
            if (result.putIfAbsent(key, credential) != null) {
                throw new IllegalArgumentException("Duplicate bot id ignoring case: " + id);
            }
        }
        return result;
    }

    private static Map<String, Object> stringMap(Object value, String path) {
        if (!(value instanceof Map<?, ?> raw)) {
            throw new IllegalArgumentException(path + " must be a mapping");
        }
        Map<String, Object> result = new LinkedHashMap<>();
        raw.forEach((key, item) -> {
            if (!(key instanceof String text)) {
                throw new IllegalArgumentException(path + " keys must be strings");
            }
            result.put(text, item);
        });
        return result;
    }

    private static String scalar(Object value, String path) {
        if (value instanceof String text) {
            return text;
        }
        if (value instanceof Number || value instanceof Boolean || value instanceof Character) {
            return String.valueOf(value);
        }
        throw new IllegalArgumentException(path + " must be a scalar value");
    }

    private sealed interface StoredCredential permits ReferencedCredential, LegacyInlineCredential {
        void writeTo(Map<String, Object> bot);
    }

    private record ReferencedCredential(ManagedCredentialReference.Kind kind,
                                        String reference) implements StoredCredential {
        private ReferencedCredential {
            kind = Objects.requireNonNull(kind, "Managed credential reference kind must not be null");
            reference = Objects.requireNonNull(reference, "Managed credential reference must not be null");
            if (kind == ManagedCredentialReference.Kind.NONE) {
                if (!reference.isEmpty()) {
                    throw new IllegalArgumentException("NONE credential reference must be empty");
                }
            }
            else {
                reference = CredentialResolver.normalizeLegacyReference(reference,
                    kind == ManagedCredentialReference.Kind.SECRET ? "password-secret" : "password-env");
            }
        }

        private static ReferencedCredential strict(ManagedCredentialReference reference) {
            Objects.requireNonNull(reference, "Managed credential reference must not be null");
            return new ReferencedCredential(reference.kind(), reference.reference());
        }

        @Override
        public void writeTo(Map<String, Object> bot) {
            switch (kind) {
                case NONE -> {
                    // Absence is the canonical passwordless representation.
                }
                case SECRET -> bot.put("password-secret", reference);
                case ENVIRONMENT -> bot.put("password-env", reference);
            }
        }
    }

    /** Existing inline values may be retained, but no public add path creates this type. */
    private record LegacyInlineCredential(String password) implements StoredCredential {
        private LegacyInlineCredential {
            if (password == null) {
                throw new IllegalArgumentException("Legacy inline password must not be null");
            }
        }

        @Override
        public void writeTo(Map<String, Object> bot) {
            bot.put("password", password);
        }
    }

    private static String normalizeId(String id) {
        return id == null ? "" : id.trim().toLowerCase(Locale.ROOT);
    }
}
