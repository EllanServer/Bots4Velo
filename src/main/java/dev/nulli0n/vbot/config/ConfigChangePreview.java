package dev.nulli0n.vbot.config;

import dev.nulli0n.vbot.config.BotPluginConfig.AuthConfig;
import dev.nulli0n.vbot.config.BotPluginConfig.BackendControlConfig;
import dev.nulli0n.vbot.config.BotPluginConfig.BehaviorConfig;
import dev.nulli0n.vbot.config.BotPluginConfig.BotDefinition;
import dev.nulli0n.vbot.config.BotPluginConfig.PlayerStateConfig;
import dev.nulli0n.vbot.config.BotPluginConfig.ProxyEndpoint;
import dev.nulli0n.vbot.config.BotPluginConfig.ReconnectConfig;
import dev.nulli0n.vbot.config.BotPluginConfig.RespawnPointConfig;
import dev.nulli0n.vbot.config.BotPluginConfig.RuntimeConfig;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Builds a bounded, side-effect-free preview of a configuration replacement.
 *
 * <p>The preview deliberately retains only change categories, bot identifiers,
 * and fixed field names. It never retains old or new configuration values. This
 * makes rendered output, record accessors, and generated {@code toString()}
 * representations safe for credentials, webhook tokens, and expanded command
 * contents.</p>
 */
public final class ConfigChangePreview {
    public static final int DEFAULT_MAX_CHANGES = 50;
    public static final int MAX_CHANGES = 200;

    private static final Comparator<String> BOT_ID_ORDER =
        String.CASE_INSENSITIVE_ORDER.thenComparing(Comparator.naturalOrder());

    private ConfigChangePreview() {
    }

    public static Preview compare(BotPluginConfig current, BotPluginConfig candidate) {
        return compare(current, candidate, DEFAULT_MAX_CHANGES);
    }

    public static Preview compare(BotPluginConfig current, BotPluginConfig candidate, int maximumChanges) {
        Objects.requireNonNull(current, "current");
        Objects.requireNonNull(candidate, "candidate");
        if (maximumChanges < 1 || maximumChanges > MAX_CHANGES) {
            throw new IllegalArgumentException(
                "maximumChanges must be between 1 and " + MAX_CHANGES);
        }

        List<Change> allChanges = new ArrayList<>();

        List<String> proxyFields = changedProxyFields(current.proxy(), candidate.proxy());
        if (!proxyFields.isEmpty()) {
            allChanges.add(new Change(ChangeType.PROXY_CHANGED, "", proxyFields));
        }

        List<String> runtimeFields = changedRuntimeFields(current.runtime(), candidate.runtime());
        if (!runtimeFields.isEmpty()) {
            allChanges.add(new Change(ChangeType.RUNTIME_CHANGED, "", runtimeFields));
        }

        addBotChanges(allChanges, current.bots(), candidate.bots(), ChangeType.BOT_ADDED);
        addBotChanges(allChanges, current.bots(), candidate.bots(), ChangeType.BOT_REMOVED);
        addBotChanges(allChanges, current.bots(), candidate.bots(), ChangeType.BOT_CHANGED);

        int retainedCount = Math.min(allChanges.size(), maximumChanges);
        return new Preview(
            allChanges.subList(0, retainedCount),
            allChanges.size(),
            allChanges.size() - retainedCount
        );
    }

    private static void addBotChanges(List<Change> changes, Map<String, BotDefinition> current,
                                      Map<String, BotDefinition> candidate, ChangeType type) {
        List<String> botIds = switch (type) {
            case BOT_ADDED -> candidate.keySet().stream()
                .filter(id -> !current.containsKey(id))
                .sorted(BOT_ID_ORDER)
                .toList();
            case BOT_REMOVED -> current.keySet().stream()
                .filter(id -> !candidate.containsKey(id))
                .sorted(BOT_ID_ORDER)
                .toList();
            case BOT_CHANGED -> current.keySet().stream()
                .filter(candidate::containsKey)
                .filter(id -> !current.get(id).equals(candidate.get(id)))
                .sorted(BOT_ID_ORDER)
                .toList();
            default -> throw new IllegalArgumentException("Not a bot change type: " + type);
        };

        for (String botId : botIds) {
            List<String> fields = type == ChangeType.BOT_CHANGED
                ? changedBotFields(current.get(botId), candidate.get(botId))
                : List.of();
            changes.add(new Change(type, botId, fields));
        }
    }

    private static List<String> changedProxyFields(ProxyEndpoint current, ProxyEndpoint candidate) {
        List<String> fields = new ArrayList<>();
        addIfChanged(fields, "address", current.address(), candidate.address());
        addIfChanged(fields, "port", current.port(), candidate.port());
        addIfChanged(fields, "virtual-host", current.virtualHost(), candidate.virtualHost());
        addIfChanged(fields, "virtual-port", current.virtualPort(), candidate.virtualPort());
        addIfChanged(fields, "protocol-version", current.protocol(), candidate.protocol());
        addIfChanged(fields, "protocol-detection-timeout-ms", current.protocolDetectionTimeoutMillis(),
            candidate.protocolDetectionTimeoutMillis());
        return List.copyOf(fields);
    }

    private static List<String> changedRuntimeFields(RuntimeConfig current, RuntimeConfig candidate) {
        List<String> fields = new ArrayList<>();
        addIfChanged(fields, "auto-start-delay-ms", current.autoStartDelayMillis(),
            candidate.autoStartDelayMillis());
        addIfChanged(fields, "spawn-interval-ms", current.spawnIntervalMillis(),
            candidate.spawnIntervalMillis());
        addIfChanged(fields, "maximum-bots", current.maximumBots(), candidate.maximumBots());
        addIfChanged(fields, "command-interval-ms", current.commandIntervalMillis(),
            candidate.commandIntervalMillis());
        addIfChanged(fields, "resource-pack-step-delay-ms", current.resourcePackStepDelayMillis(),
            candidate.resourcePackStepDelayMillis());
        addIfChanged(fields, "resource-pack-mode", current.resourcePackMode(), candidate.resourcePackMode());
        addIfChanged(fields, "auto-respawn", current.autoRespawn(), candidate.autoRespawn());

        changedReconnectFields(fields, current.reconnect(), candidate.reconnect());
        addIfChanged(fields, "schedules", current.schedules(), candidate.schedules());
        addIfChanged(fields, "webhook-url", current.webhookUrl(), candidate.webhookUrl());
        addIfChanged(fields, "presence-rules", current.presenceRules(), candidate.presenceRules());
        addIfChanged(fields, "prometheus-address", current.prometheusAddress(), candidate.prometheusAddress());
        addIfChanged(fields, "prometheus-port", current.prometheusPort(), candidate.prometheusPort());
        changedBackendControlFields(fields, current.backendControl(), candidate.backendControl());
        return List.copyOf(fields);
    }

    private static void changedReconnectFields(List<String> fields, ReconnectConfig current,
                                               ReconnectConfig candidate) {
        addIfChanged(fields, "reconnect.initial-delay-ms", current.initialDelayMillis(),
            candidate.initialDelayMillis());
        addIfChanged(fields, "reconnect.maximum-delay-ms", current.maximumDelayMillis(),
            candidate.maximumDelayMillis());
        addIfChanged(fields, "reconnect.multiplier", current.multiplier(), candidate.multiplier());
        addIfChanged(fields, "reconnect.jitter", current.jitter(), candidate.jitter());
        addIfChanged(fields, "reconnect.maximum-attempts", current.maximumAttempts(),
            candidate.maximumAttempts());
    }

    private static void changedBackendControlFields(List<String> fields, BackendControlConfig current,
                                                    BackendControlConfig candidate) {
        addIfChanged(fields, "backend-control.enabled", current.enabled(), candidate.enabled());
        addIfChanged(fields, "backend-control.secret", current.secret(), candidate.secret());
        addIfChanged(fields, "backend-control.secret-env", current.secretEnv(), candidate.secretEnv());
        addIfChanged(fields, "backend-control.timeout-ms", current.timeoutMillis(), candidate.timeoutMillis());
    }

    private static List<String> changedBotFields(BotDefinition current, BotDefinition candidate) {
        List<String> fields = new ArrayList<>();
        addIfChanged(fields, "id", current.id(), candidate.id());
        addIfChanged(fields, "enabled", current.enabled(), candidate.enabled());
        addIfChanged(fields, "username", current.username(), candidate.username());
        addIfChanged(fields, "password", current.password(), candidate.password());
        addIfChanged(fields, "credential-source", current.credentialSourceFingerprint(),
            candidate.credentialSourceFingerprint());
        addIfChanged(fields, "target-server", current.targetServer(), candidate.targetServer());
        addIfChanged(fields, "protocol-detection-server", current.protocolDetectionServer(),
            candidate.protocolDetectionServer());
        addIfChanged(fields, "render-distance", current.renderDistance(), candidate.renderDistance());

        changedAuthFields(fields, current.auth(), candidate.auth());
        addIfChanged(fields, "server-switch-command", current.serverSwitchCommand(),
            candidate.serverSwitchCommand());
        addIfChanged(fields, "server-switch-delay-ms", current.serverSwitchDelayMillis(),
            candidate.serverSwitchDelayMillis());
        addIfChanged(fields, "server-switch-maximum-attempts", current.serverSwitchMaximumAttempts(),
            candidate.serverSwitchMaximumAttempts());
        addIfChanged(fields, "after-login-commands", current.afterLoginCommands(), candidate.afterLoginCommands());
        addIfChanged(fields, "groups", current.groups(), candidate.groups());
        addIfChanged(fields, "tags", current.tags(), candidate.tags());
        addIfChanged(fields, "display-name", current.displayName(), candidate.displayName());
        addIfChanged(fields, "tab-group", current.tabGroup(), candidate.tabGroup());
        addIfChanged(fields, "protocol-version", current.protocolOverride(), candidate.protocolOverride());
        addIfChanged(fields, "template", current.templateName(), candidate.templateName());

        changedBehaviorFields(fields, current.behavior(), candidate.behavior());
        changedPlayerStateFields(fields, current.playerState(), candidate.playerState());
        return List.copyOf(fields);
    }

    private static void changedAuthFields(List<String> fields, AuthConfig current, AuthConfig candidate) {
        addIfChanged(fields, "auth.mode", current.mode(), candidate.mode());
        addIfChanged(fields, "auth.login-command", current.loginCommand(), candidate.loginCommand());
        addIfChanged(fields, "auth.register-command", current.registerCommand(), candidate.registerCommand());
        addIfChanged(fields, "auth.login-delay-ms", current.loginDelayMillis(), candidate.loginDelayMillis());
        addIfChanged(fields, "auth.fallback-register-delay-ms", current.fallbackRegisterDelayMillis(),
            candidate.fallbackRegisterDelayMillis());
        addIfChanged(fields, "auth.after-auth-delay-ms", current.afterAuthDelayMillis(),
            candidate.afterAuthDelayMillis());
        addIfChanged(fields, "auth.login-prompts", current.loginPrompts(), candidate.loginPrompts());
        addIfChanged(fields, "auth.register-prompts", current.registerPrompts(), candidate.registerPrompts());
        addIfChanged(fields, "auth.success-messages", current.successMessages(), candidate.successMessages());
        addIfChanged(fields, "auth.failure-messages", current.failureMessages(), candidate.failureMessages());
        addIfChanged(fields, "auth.timeout-ms", current.timeoutMillis(), candidate.timeoutMillis());
        addIfChanged(fields, "auth.authmeui.accept-rules", current.acceptRules(), candidate.acceptRules());
        addIfChanged(fields, "auth.authmeui.registration-email", current.registrationEmail(),
            candidate.registrationEmail());
        addIfChanged(fields, "auth.authmeui.registration-second-argument", current.registrationSecondArgument(),
            candidate.registrationSecondArgument());
        addIfChanged(fields, "auth.authmeui.ui-detection-grace-ms", current.uiDetectionGraceMillis(),
            candidate.uiDetectionGraceMillis());
    }

    private static void changedBehaviorFields(List<String> fields, BehaviorConfig current,
                                               BehaviorConfig candidate) {
        addIfChanged(fields, "behavior.mode", current.mode(), candidate.mode());
        addIfChanged(fields, "behavior.enabled", current.enabled(), candidate.enabled());
        addIfChanged(fields, "behavior.interval-ms", current.intervalMillis(), candidate.intervalMillis());
        addIfChanged(fields, "behavior.movement-radius", current.movementRadius(), candidate.movementRadius());
        addIfChanged(fields, "behavior.yaw-step", current.yawStep(), candidate.yawStep());
        addIfChanged(fields, "behavior.random-yaw", current.randomYaw(), candidate.randomYaw());
        addIfChanged(fields, "behavior.jump", current.jump(), candidate.jump());
        addIfChanged(fields, "behavior.swing", current.swing(), candidate.swing());
        addIfChanged(fields, "behavior.sneak", current.sneak(), candidate.sneak());
        addIfChanged(fields, "behavior.commands", current.commands(), candidate.commands());
        addIfChanged(fields, "behavior.path", current.path(), candidate.path());
        addIfChanged(fields, "behavior.server-cycle", current.serverCycle(), candidate.serverCycle());
        addIfChanged(fields, "behavior.server-cycle-every", current.serverCycleEvery(),
            candidate.serverCycleEvery());
        addIfChanged(fields, "behavior.follow-player", current.followPlayer(), candidate.followPlayer());
    }

    private static void changedPlayerStateFields(List<String> fields, PlayerStateConfig current,
                                                  PlayerStateConfig candidate) {
        addIfChanged(fields, "player-state.invulnerable", current.invulnerability(),
            candidate.invulnerability());
        addIfChanged(fields, "player-state.game-mode", current.gameMode(), candidate.gameMode());
        addIfChanged(fields, "player-state.apply-delay-ms", current.applyDelayMillis(),
            candidate.applyDelayMillis());
        addIfChanged(fields, "player-state.afk-preset", current.afkPreset(), candidate.afkPreset());
        addIfChanged(fields, "player-state.sleep-ignored", current.sleepingIgnored(), candidate.sleepingIgnored());
        addIfChanged(fields, "player-state.affects-spawning", current.affectsSpawning(),
            candidate.affectsSpawning());
        addIfChanged(fields, "player-state.pickup-items", current.pickupItems(), candidate.pickupItems());
        addIfChanged(fields, "player-state.collidable", current.collidable(), candidate.collidable());
        changedRespawnPointFields(fields, current.respawnPoint(), candidate.respawnPoint());
    }

    private static void changedRespawnPointFields(List<String> fields, RespawnPointConfig current,
                                                  RespawnPointConfig candidate) {
        addIfChanged(fields, "player-state.respawn-point.mode", current.mode(), candidate.mode());
        addIfChanged(fields, "player-state.respawn-point.world", current.world(), candidate.world());
        addIfChanged(fields, "player-state.respawn-point.x", current.x(), candidate.x());
        addIfChanged(fields, "player-state.respawn-point.y", current.y(), candidate.y());
        addIfChanged(fields, "player-state.respawn-point.z", current.z(), candidate.z());
        addIfChanged(fields, "player-state.respawn-point.yaw", current.yaw(), candidate.yaw());
    }

    private static void addIfChanged(List<String> fields, String field, Object current, Object candidate) {
        if (!Objects.equals(current, candidate)) {
            fields.add(field);
        }
    }

    public enum ChangeType {
        PROXY_CHANGED("reload-check-proxy-changed"),
        RUNTIME_CHANGED("reload-check-runtime-changed"),
        BOT_ADDED("reload-check-bot-added"),
        BOT_REMOVED("reload-check-bot-removed"),
        BOT_CHANGED("reload-check-bot-changed");

        private final String localizationKey;

        ChangeType(String localizationKey) {
            this.localizationKey = localizationKey;
        }

        public String localizationKey() {
            return localizationKey;
        }
    }

    /** A value-free, localization-ready description of one logical change. */
    public record Change(ChangeType type, String botId, List<String> fields) {
        public Change {
            type = Objects.requireNonNull(type, "type");
            botId = Objects.requireNonNull(botId, "botId");
            fields = List.copyOf(fields);
        }

        public String localizationKey() {
            return type.localizationKey();
        }

        /**
         * Safe arguments for a future localized renderer. No configuration
         * values are included.
         */
        public List<String> localizationArguments() {
            return switch (type) {
                case PROXY_CHANGED, RUNTIME_CHANGED -> List.of(String.join(", ", fields));
                case BOT_ADDED, BOT_REMOVED -> List.of(botId);
                case BOT_CHANGED -> List.of(botId, String.join(", ", fields));
            };
        }

        private String renderEnglish() {
            return switch (type) {
                case PROXY_CHANGED -> "Proxy settings changed: " + String.join(", ", fields) + ".";
                case RUNTIME_CHANGED -> "Runtime settings changed: " + String.join(", ", fields) + ".";
                case BOT_ADDED -> "Bot added: " + botId + ".";
                case BOT_REMOVED -> "Bot removed: " + botId + ".";
                case BOT_CHANGED -> "Bot changed: " + botId + " (fields: "
                    + String.join(", ", fields) + ").";
            };
        }
    }

    public record Preview(List<Change> changes, int totalChanges, int omittedChanges) {
        public Preview {
            changes = List.copyOf(changes);
            if (totalChanges < 0 || omittedChanges < 0
                || totalChanges != changes.size() + omittedChanges) {
                throw new IllegalArgumentException("Invalid preview change counts");
            }
        }

        public boolean hasChanges() {
            return totalChanges > 0;
        }

        public boolean truncated() {
            return omittedChanges > 0;
        }

        /** Returns stable English output containing at most the retained changes plus one notice. */
        public List<String> renderEnglishLines() {
            if (!hasChanges()) {
                return List.of("No configuration changes.");
            }
            List<String> lines = new ArrayList<>(changes.size() + (truncated() ? 1 : 0));
            changes.stream().map(Change::renderEnglish).forEach(lines::add);
            if (truncated()) {
                lines.add("... " + omittedChanges + " additional changes omitted.");
            }
            return List.copyOf(lines);
        }
    }
}
