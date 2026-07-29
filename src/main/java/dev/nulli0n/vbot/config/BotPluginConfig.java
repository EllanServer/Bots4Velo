package dev.nulli0n.vbot.config;

import dev.nulli0n.vbot.protocol.ProtocolSelection;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public record BotPluginConfig(
    ProxyEndpoint proxy,
    RuntimeConfig runtime,
    Map<String, BotDefinition> bots
) {
    public BotPluginConfig {
        bots = Map.copyOf(new LinkedHashMap<>(bots));
    }

    public record ProxyEndpoint(
        String address,
        int port,
        String virtualHost,
        int virtualPort,
        ProtocolSelection protocol,
        int protocolDetectionTimeoutMillis
    ) {
    }

    public record RuntimeConfig(
        long autoStartDelayMillis,
        long spawnIntervalMillis,
        int maximumBots,
        long commandIntervalMillis,
        long resourcePackStepDelayMillis,
        ResourcePackMode resourcePackMode,
        boolean autoRespawn,
        ReconnectConfig reconnect,
        List<ScheduledAction> schedules,
        String webhookUrl,
        List<PresenceRule> presenceRules,
        String prometheusAddress,
        int prometheusPort,
        BackendControlConfig backendControl
    ) {
        public RuntimeConfig {
            schedules = List.copyOf(schedules);
            webhookUrl = webhookUrl == null ? "" : webhookUrl.trim();
            presenceRules = List.copyOf(presenceRules);
            prometheusAddress = prometheusAddress == null ? "127.0.0.1" : prometheusAddress.trim();
            backendControl = backendControl == null ? BackendControlConfig.disabled() : backendControl;
        }

        /** Source-compatible constructor for the complete 2.4 runtime model. */
        public RuntimeConfig(
            long autoStartDelayMillis,
            long spawnIntervalMillis,
            int maximumBots,
            long commandIntervalMillis,
            long resourcePackStepDelayMillis,
            ResourcePackMode resourcePackMode,
            boolean autoRespawn,
            ReconnectConfig reconnect,
            List<ScheduledAction> schedules,
            String webhookUrl,
            List<PresenceRule> presenceRules,
            String prometheusAddress,
            int prometheusPort
        ) {
            this(autoStartDelayMillis, spawnIntervalMillis, maximumBots, commandIntervalMillis,
                resourcePackStepDelayMillis, resourcePackMode, autoRespawn, reconnect, schedules, webhookUrl,
                presenceRules, prometheusAddress, prometheusPort, BackendControlConfig.disabled());
        }

        public RuntimeConfig(
            long autoStartDelayMillis,
            long spawnIntervalMillis,
            int maximumBots,
            long commandIntervalMillis,
            long resourcePackStepDelayMillis,
            ResourcePackMode resourcePackMode,
            boolean autoRespawn,
            ReconnectConfig reconnect
        ) {
            this(autoStartDelayMillis, spawnIntervalMillis, maximumBots, commandIntervalMillis,
                resourcePackStepDelayMillis, resourcePackMode, autoRespawn, reconnect, List.of(), "", List.of(),
                "127.0.0.1", 0, BackendControlConfig.disabled());
        }
    }

    public record BackendControlConfig(
        boolean enabled,
        String secret,
        String secretEnv,
        long timeoutMillis
    ) {
        public BackendControlConfig {
            secret = secret == null ? "" : secret;
            secretEnv = secretEnv == null ? "" : secretEnv.trim();
        }

        public static BackendControlConfig disabled() {
            return new BackendControlConfig(false, "", "", 3_000L);
        }
    }

    public record ReconnectConfig(
        long initialDelayMillis,
        long maximumDelayMillis,
        double multiplier,
        double jitter,
        int maximumAttempts
    ) {
    }

    public record ScheduledAction(
        String id,
        String action,
        String selector,
        String server,
        long initialDelayMillis,
        long intervalMillis,
        String at,
        String timezone
    ) {
        public ScheduledAction {
            at = at == null ? "" : at.trim();
            timezone = timezone == null || timezone.isBlank() ? "UTC" : timezone.trim();
        }

        public ScheduledAction(
            String id,
            String action,
            String selector,
            String server,
            long initialDelayMillis,
            long intervalMillis
        ) {
            this(id, action, selector, server, initialDelayMillis, intervalMillis, "", "UTC");
        }

        public boolean runsDailyAtConfiguredTime() {
            return !at.isBlank();
        }
    }

    public record PresenceRule(
        String id,
        String server,
        String selector,
        int minimumBots,
        int maximumHumans,
        long intervalMillis
    ) {
    }

    public record BotDefinition(
        String id,
        boolean enabled,
        String username,
        String password,
        String targetServer,
        String protocolDetectionServer,
        int renderDistance,
        AuthConfig auth,
        String serverSwitchCommand,
        long serverSwitchDelayMillis,
        int serverSwitchMaximumAttempts,
        List<String> afterLoginCommands,
        List<String> groups,
        List<String> tags,
        String displayName,
        String tabGroup,
        ProtocolSelection protocolOverride,
        String templateName,
        BehaviorConfig behavior,
        PlayerStateConfig playerState
    ) {
        public BotDefinition {
            afterLoginCommands = List.copyOf(afterLoginCommands);
            groups = List.copyOf(groups);
            tags = List.copyOf(tags);
            displayName = displayName == null ? "" : displayName.trim();
            tabGroup = tabGroup == null ? "" : tabGroup.trim();
            templateName = templateName == null ? "" : templateName;
            behavior = behavior == null ? BehaviorConfig.disabled() : behavior;
            playerState = playerState == null ? PlayerStateConfig.unchanged() : playerState;
        }

        /** Source-compatible constructor for the complete 2.4 bot model. */
        public BotDefinition(
            String id,
            boolean enabled,
            String username,
            String password,
            String targetServer,
            String protocolDetectionServer,
            int renderDistance,
            AuthConfig auth,
            String serverSwitchCommand,
            long serverSwitchDelayMillis,
            int serverSwitchMaximumAttempts,
            List<String> afterLoginCommands,
            List<String> groups,
            List<String> tags,
            String displayName,
            String tabGroup,
            ProtocolSelection protocolOverride,
            String templateName,
            BehaviorConfig behavior
        ) {
            this(id, enabled, username, password, targetServer, protocolDetectionServer, renderDistance, auth,
                serverSwitchCommand, serverSwitchDelayMillis, serverSwitchMaximumAttempts, afterLoginCommands,
                groups, tags, displayName, tabGroup, protocolOverride, templateName, behavior,
                PlayerStateConfig.unchanged());
        }

        /**
         * Source-compatible constructor for integrations compiled against the
         * 2.0 configuration model.
         */
        public BotDefinition(
            String id,
            boolean enabled,
            String username,
            String password,
            String targetServer,
            String protocolDetectionServer,
            int renderDistance,
            AuthConfig auth,
            String serverSwitchCommand,
            long serverSwitchDelayMillis,
            int serverSwitchMaximumAttempts,
            List<String> afterLoginCommands
        ) {
            this(id, enabled, username, password, targetServer, protocolDetectionServer, renderDistance, auth,
                serverSwitchCommand, serverSwitchDelayMillis, serverSwitchMaximumAttempts, afterLoginCommands,
                List.of(), List.of(), "", "", null, "", BehaviorConfig.disabled(), PlayerStateConfig.unchanged());
        }
    }

    public record PlayerStateConfig(
        InvulnerabilityMode invulnerability,
        ManagedGameMode gameMode,
        long applyDelayMillis,
        RespawnPointConfig respawnPoint
    ) {
        public PlayerStateConfig {
            invulnerability = invulnerability == null ? InvulnerabilityMode.KEEP : invulnerability;
            gameMode = gameMode == null ? ManagedGameMode.KEEP : gameMode;
            respawnPoint = respawnPoint == null ? RespawnPointConfig.unchanged() : respawnPoint;
        }

        public static PlayerStateConfig unchanged() {
            return new PlayerStateConfig(InvulnerabilityMode.KEEP, ManagedGameMode.KEEP, 0L,
                RespawnPointConfig.unchanged());
        }
    }

    public record RespawnPointConfig(
        RespawnPointMode mode,
        String world,
        double x,
        double y,
        double z,
        float yaw
    ) {
        public RespawnPointConfig {
            mode = mode == null ? RespawnPointMode.UNCHANGED : mode;
            world = world == null ? "" : world.trim();
        }

        public static RespawnPointConfig unchanged() {
            return new RespawnPointConfig(RespawnPointMode.UNCHANGED, "", 0.0D, 0.0D, 0.0D, 0.0F);
        }
    }

    public enum InvulnerabilityMode {
        KEEP,
        ENABLED,
        DISABLED
    }

    public enum ManagedGameMode {
        KEEP,
        SURVIVAL,
        CREATIVE,
        ADVENTURE,
        SPECTATOR
    }

    public enum RespawnPointMode {
        UNCHANGED,
        CURRENT,
        FIXED,
        WORLD_SPAWN,
        CLEAR
    }

    public record BehaviorConfig(
        BehaviorMode mode,
        boolean enabled,
        long intervalMillis,
        double movementRadius,
        float yawStep,
        boolean randomYaw,
        boolean jump,
        boolean swing,
        boolean sneak,
        List<String> commands,
        List<BehaviorPoint> path,
        List<String> serverCycle,
        int serverCycleEvery,
        String followPlayer
    ) {
        public BehaviorConfig {
            mode = mode == null ? BehaviorMode.STATIC : mode;
            commands = List.copyOf(commands);
            path = List.copyOf(path);
            serverCycle = List.copyOf(serverCycle);
            followPlayer = followPlayer == null ? "" : followPlayer.trim();
        }

        public static BehaviorConfig disabled() {
            return new BehaviorConfig(BehaviorMode.STATIC, false, 5_000L, 0.0D, 15.0F, false, false, false, false,
                List.of(), List.of(), List.of(), 0, "");
        }
    }

    public record BehaviorPoint(double x, double y, double z) {
    }

    public enum BehaviorMode {
        STATIC,
        FARM,
        PATROL,
        COMMAND,
        FOLLOW
    }

    public record AuthConfig(
        AuthMode mode,
        String loginCommand,
        String registerCommand,
        long loginDelayMillis,
        long fallbackRegisterDelayMillis,
        long afterAuthDelayMillis,
        List<String> loginPrompts,
        List<String> registerPrompts,
        List<String> successMessages,
        List<String> failureMessages,
        long timeoutMillis
    ) {
        public AuthConfig {
            loginPrompts = List.copyOf(loginPrompts);
            registerPrompts = List.copyOf(registerPrompts);
            successMessages = List.copyOf(successMessages);
            failureMessages = List.copyOf(failureMessages);
        }

        public AuthConfig(
            AuthMode mode,
            String loginCommand,
            String registerCommand,
            long loginDelayMillis,
            long fallbackRegisterDelayMillis,
            long afterAuthDelayMillis,
            List<String> loginPrompts,
            List<String> registerPrompts,
            List<String> successMessages,
            List<String> failureMessages
        ) {
            this(mode, loginCommand, registerCommand, loginDelayMillis, fallbackRegisterDelayMillis,
                afterAuthDelayMillis, loginPrompts, registerPrompts, successMessages, failureMessages, 30_000);
        }

        public AuthConfig(
            AuthMode mode,
            String loginCommand,
            String registerCommand,
            long loginDelayMillis,
            long fallbackRegisterDelayMillis,
            long afterAuthDelayMillis,
            List<String> loginPrompts,
            List<String> registerPrompts,
            List<String> successMessages
        ) {
            this(mode, loginCommand, registerCommand, loginDelayMillis, fallbackRegisterDelayMillis,
                afterAuthDelayMillis, loginPrompts, registerPrompts, successMessages, List.of());
        }
    }

    public enum AuthMode {
        NONE,
        LOGIN,
        REGISTER,
        AUTO
    }

    public enum ResourcePackMode {
        ACCEPT_WITHOUT_DOWNLOAD,
        DECLINE
    }
}
