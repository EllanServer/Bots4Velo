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
        ReconnectConfig reconnect
    ) {
    }

    public record ReconnectConfig(
        long initialDelayMillis,
        long maximumDelayMillis,
        double multiplier,
        double jitter,
        int maximumAttempts
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
        ProtocolSelection protocolOverride,
        String templateName,
        BehaviorConfig behavior
    ) {
        public BotDefinition {
            afterLoginCommands = List.copyOf(afterLoginCommands);
            groups = List.copyOf(groups);
            tags = List.copyOf(tags);
            templateName = templateName == null ? "" : templateName;
            behavior = behavior == null ? BehaviorConfig.disabled() : behavior;
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
                List.of(), List.of(), null, "", BehaviorConfig.disabled());
        }
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
        List<String> successMessages
    ) {
        public AuthConfig {
            loginPrompts = List.copyOf(loginPrompts);
            registerPrompts = List.copyOf(registerPrompts);
            successMessages = List.copyOf(successMessages);
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
