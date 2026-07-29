package dev.nulli0n.vbot.command;

import dev.nulli0n.vbot.backend.BackendControlPatch;
import dev.nulli0n.vbot.backend.BackendControlResult;
import dev.nulli0n.vbot.backend.BackendControlService;
import dev.nulli0n.vbot.backend.InvulnerabilityChange;
import dev.nulli0n.vbot.backend.protocol.BackendGameMode;
import dev.nulli0n.vbot.backend.protocol.BackendStatus;
import dev.nulli0n.vbot.backend.protocol.ManagedBoolean;
import dev.nulli0n.vbot.backend.protocol.RespawnPoint;
import dev.nulli0n.vbot.message.PluginMessages;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.CompletionStage;
import java.util.function.Function;
import java.util.function.Supplier;

/** Parses player-state commands and summarizes asynchronous backend ACKs. */
final class PlayerStateCommandHandler {
    private static final int MAXIMUM_FAILURE_LINES = 10;

    private final BackendControlService backend;
    private final Function<String, List<String>> selector;
    private final Supplier<PluginMessages> messages;

    PlayerStateCommandHandler(BackendControlService backend, Function<String, List<String>> selector) {
        this(backend, selector, () -> null);
    }

    PlayerStateCommandHandler(BackendControlService backend, Function<String, List<String>> selector,
                              Supplier<PluginMessages> messages) {
        this.backend = Objects.requireNonNull(backend, "backend");
        this.selector = Objects.requireNonNull(selector, "selector");
        this.messages = Objects.requireNonNull(messages, "messages");
    }

    CompletableFuture<Reply> execute(String action, String[] args) {
        try {
            ParsedOperation operation = parse(action, args);
            List<String> targets = select(operation.selector());
            if (targets.isEmpty()) {
                return CompletableFuture.completedFuture(Reply.error(
                    text("no-matches", "No bots matched selector: %s", operation.selector())));
            }
            return invokeAll(targets, operation);
        }
        catch (CommandInputException exception) {
            return CompletableFuture.completedFuture(new Reply(exception.severity(), List.of(exception.getMessage())));
        }
    }

    private CompletableFuture<Reply> invokeAll(List<String> targets, ParsedOperation operation) {
        List<CompletableFuture<BackendControlResult>> acknowledgements = targets.stream()
            .map(botId -> invoke(botId, operation)).toList();
        CompletableFuture<Void> completed = CompletableFuture.allOf(
            acknowledgements.toArray(CompletableFuture[]::new));
        return completed.thenApply(ignored -> summarize(operation, acknowledgements));
    }

    private CompletableFuture<BackendControlResult> invoke(String botId, ParsedOperation operation) {
        CompletionStage<BackendControlResult> stage;
        try {
            stage = switch (operation.kind()) {
                case APPLY -> backend.apply(botId, operation.patch());
                case PROBE -> backend.probe(botId);
                case RESPAWN -> backend.respawn(botId);
                case RECOVER -> backend.recover(botId);
            };
        }
        catch (Exception exception) {
            return CompletableFuture.completedFuture(failed(botId, exception));
        }
        if (stage == null) {
            return CompletableFuture.completedFuture(BackendControlResult.failure(botId,
                BackendStatus.APPLY_FAILED, "Backend control returned no acknowledgement future."));
        }
        return stage.handle((result, failure) -> {
            if (failure != null) {
                return failed(botId, failure);
            }
            if (result == null) {
                return BackendControlResult.failure(botId, BackendStatus.APPLY_FAILED,
                    "Backend control returned an empty acknowledgement.");
            }
            if (!result.botId().equalsIgnoreCase(botId)) {
                return BackendControlResult.failure(botId, BackendStatus.APPLY_FAILED,
                    "Backend acknowledgement target mismatch: " + result.botId());
            }
            return result;
        }).toCompletableFuture();
    }

    private Reply summarize(ParsedOperation operation,
                            List<CompletableFuture<BackendControlResult>> acknowledgements) {
        List<BackendControlResult> results = acknowledgements.stream().map(CompletableFuture::join).toList();
        long succeeded = results.stream().filter(BackendControlResult::successful).count();
        Severity severity = succeeded == results.size()
            ? Severity.SUCCESS : (succeeded == 0 ? Severity.ERROR : Severity.WARNING);
        List<String> lines = new ArrayList<>();
        lines.add(text("backend-summary", "%s acknowledged by %s/%s bot(s).",
            operation.label(), succeeded, results.size()));
        if (operation.kind() == OperationKind.PROBE) {
            List<BackendControlResult> statusResults = results.stream()
                .filter(BackendControlResult::successful)
                .filter(result -> result.actualState().present())
                .toList();
            statusResults.stream()
                .limit(MAXIMUM_FAILURE_LINES)
                .forEach(result -> lines.add(actualStateLine(result.botId(), result.actualState(), true)));
            if (statusResults.size() > MAXIMUM_FAILURE_LINES) {
                lines.add(text("backend-more-status", "... and %s more status result(s).",
                    statusResults.size() - MAXIMUM_FAILURE_LINES));
            }
        }
        if (results.size() == 1 && results.getFirst().successful()
            && results.getFirst().actualState().present() && operation.kind() != OperationKind.PROBE) {
            lines.add(actualStateLine("", results.getFirst().actualState(), false));
        }
        List<BackendControlResult> failures = results.stream()
            .filter(result -> !result.successful()).toList();
        failures.stream().limit(MAXIMUM_FAILURE_LINES).forEach(result -> lines.add(
            result.botId() + ": " + result.status() + detailSuffix(result.detail())));
        if (failures.size() > MAXIMUM_FAILURE_LINES) {
            lines.add(text("backend-more-failures", "... and %s more failure(s).",
                failures.size() - MAXIMUM_FAILURE_LINES));
        }
        return new Reply(severity, lines);
    }

    private List<String> select(String expression) {
        List<String> selected = selector.apply(expression);
        if (selected == null || selected.isEmpty()) {
            return List.of();
        }
        Map<String, String> unique = new LinkedHashMap<>();
        selected.stream().filter(Objects::nonNull).map(String::trim).filter(value -> !value.isEmpty())
            .forEach(value -> unique.putIfAbsent(value.toLowerCase(Locale.ROOT), value));
        return List.copyOf(unique.values());
    }

    private ParsedOperation parse(String action, String[] args) {
        return switch (action.toLowerCase(Locale.ROOT)) {
            case "invulnerable" -> parseInvulnerable(args);
            case "gamemode" -> parseGameMode(args);
            case "spawnpoint" -> parseSpawnPoint(args);
            case "respawn" -> parseRespawn(args);
            case "afk" -> parseAfk(args);
            case "recover" -> parseRecover(args);
            default -> throw new IllegalArgumentException(text("state-unsupported",
                "Unsupported player-state action: %s", action));
        };
    }

    private ParsedOperation parseInvulnerable(String[] args) {
        if (args.length != 3) {
            throw usage(text("usage-invulnerable",
                "Usage: /vbot invulnerable <id|selector> <on|off|keep>"));
        }
        InvulnerabilityChange change = switch (args[2].toLowerCase(Locale.ROOT)) {
            case "on" -> InvulnerabilityChange.ENABLED;
            case "off" -> InvulnerabilityChange.DISABLED;
            case "keep" -> InvulnerabilityChange.KEEP;
            default -> throw input(text("input-invulnerable", "Invulnerability must be on, off or keep."));
        };
        return ParsedOperation.apply(args[1], text("label-invulnerable", "Invulnerability update"),
            BackendControlPatch.invulnerability(change));
    }

    private ParsedOperation parseGameMode(String[] args) {
        if (args.length != 3) {
            throw usage(text("usage-gamemode", "Usage: /vbot gamemode <id|selector> "
                + "<survival|creative|adventure|spectator|unchanged>"));
        }
        BackendGameMode gameMode = switch (args[2].toLowerCase(Locale.ROOT)) {
            case "survival" -> BackendGameMode.SURVIVAL;
            case "creative" -> BackendGameMode.CREATIVE;
            case "adventure" -> BackendGameMode.ADVENTURE;
            case "spectator" -> BackendGameMode.SPECTATOR;
            case "unchanged" -> BackendGameMode.UNCHANGED;
            default -> throw input(text("input-gamemode",
                "Game mode must be survival, creative, adventure, spectator or unchanged."));
        };
        return ParsedOperation.apply(args[1], text("label-gamemode", "Game mode update"),
            BackendControlPatch.gameMode(gameMode));
    }

    private ParsedOperation parseSpawnPoint(String[] args) {
        if (args.length < 3) {
            throw usage(spawnPointUsage());
        }
        String mode = args[2].toLowerCase(Locale.ROOT);
        RespawnPoint point;
        switch (mode) {
            case "current" -> {
                requireLength(args, 3, spawnPointUsage());
                point = RespawnPoint.current();
            }
            case "worldspawn" -> {
                requireLength(args, 3, spawnPointUsage());
                point = RespawnPoint.worldSpawn("");
            }
            case "clear" -> {
                requireLength(args, 3, spawnPointUsage());
                point = RespawnPoint.clear();
            }
            case "set" -> {
                if (args.length != 7 && args.length != 8) {
                    throw usage(spawnPointUsage());
                }
                String world = world(args[3]);
                double x = finiteDouble(args[4], "x");
                double y = finiteDouble(args[5], "y");
                double z = finiteDouble(args[6], "z");
                float yaw = args.length == 8 ? finiteFloat(args[7], "yaw") : 0.0F;
                point = RespawnPoint.fixed(world, x, y, z, yaw, 0.0F);
            }
            default -> throw input(text("input-spawnpoint",
                "Spawn point mode must be current, worldspawn, clear or set."));
        }
        return ParsedOperation.apply(args[1], text("label-spawnpoint", "Respawn point update"),
            BackendControlPatch.respawnPoint(point));
    }

    private ParsedOperation parseRespawn(String[] args) {
        if (args.length != 2) {
            throw usage(text("usage-respawn", "Usage: /vbot respawn <id|selector>"));
        }
        return ParsedOperation.respawn(args[1], text("label-respawn", "Respawn request"));
    }

    private ParsedOperation parseRecover(String[] args) {
        if (args.length != 2) {
            throw usage(text("usage-recover", "Usage: /vbot recover <id|selector>"));
        }
        return ParsedOperation.recover(args[1], text("label-recover", "Recovery request"));
    }

    private ParsedOperation parseAfk(String[] args) {
        if (args.length < 3) {
            throw usage(afkUsage());
        }
        String selector = args[1];
        return switch (args[2].toLowerCase(Locale.ROOT)) {
            case "status" -> {
                requireLength(args, 3, afkUsage());
                yield ParsedOperation.probe(selector, text("label-afk-status", "AFK status"));
            }
            case "unmanage" -> {
                requireLength(args, 3, afkUsage());
                yield ParsedOperation.apply(selector, text("label-afk-unmanage", "AFK policy removal"), BackendControlPatch.afkPreset(
                    InvulnerabilityChange.KEEP, ManagedBoolean.UNCHANGED, ManagedBoolean.UNCHANGED,
                    ManagedBoolean.UNCHANGED, ManagedBoolean.UNCHANGED));
            }
            case "preset" -> parseAfkPreset(args);
            case "set" -> parseAfkFlag(args);
            default -> throw input(text("input-afk-action",
                "AFK action must be status, preset, set or unmanage."));
        };
    }

    private ParsedOperation parseAfkPreset(String[] args) {
        requireLength(args, 4, afkUsage());
        return switch (args[3].toLowerCase(Locale.ROOT)) {
            case "safe" -> ParsedOperation.apply(args[1], text("label-afk-safe", "SAFE AFK preset"), BackendControlPatch.afkPreset(
                InvulnerabilityChange.ENABLED, ManagedBoolean.ENABLED, ManagedBoolean.UNCHANGED,
                ManagedBoolean.DISABLED, ManagedBoolean.DISABLED));
            case "farm" -> ParsedOperation.apply(args[1], text("label-afk-farm", "FARM AFK preset"), BackendControlPatch.afkPreset(
                InvulnerabilityChange.ENABLED, ManagedBoolean.ENABLED, ManagedBoolean.ENABLED,
                ManagedBoolean.DISABLED, ManagedBoolean.DISABLED));
            case "normal" -> ParsedOperation.apply(args[1], text("label-afk-normal", "NORMAL AFK preset"), BackendControlPatch.afkPreset(
                InvulnerabilityChange.DISABLED, ManagedBoolean.DISABLED, ManagedBoolean.ENABLED,
                ManagedBoolean.ENABLED, ManagedBoolean.ENABLED));
            default -> throw input(text("input-afk-preset", "AFK preset must be safe, farm or normal."));
        };
    }

    private ParsedOperation parseAfkFlag(String[] args) {
        requireLength(args, 5, afkUsage());
        ManagedBoolean value = managedBoolean(args[4]);
        BackendControlPatch patch = switch (args[3].toLowerCase(Locale.ROOT)) {
            case "sleep-ignored" -> BackendControlPatch.sleepingIgnored(value);
            case "affects-spawning" -> BackendControlPatch.affectsSpawning(value);
            case "pickup" -> BackendControlPatch.pickupItems(value);
            case "collision" -> BackendControlPatch.collidable(value);
            default -> throw input(text("input-afk-property",
                "AFK property must be sleep-ignored, affects-spawning, pickup or collision."));
        };
        return ParsedOperation.apply(args[1], text("label-afk-property", "AFK property update"), patch);
    }

    private ManagedBoolean managedBoolean(String raw) {
        return switch (raw.toLowerCase(Locale.ROOT)) {
            case "on" -> ManagedBoolean.ENABLED;
            case "off" -> ManagedBoolean.DISABLED;
            case "keep" -> ManagedBoolean.UNCHANGED;
            default -> throw input(text("input-afk-value", "AFK property value must be on, off or keep."));
        };
    }

    private static void requireLength(String[] args, int expected, String usage) {
        if (args.length != expected) {
            throw usage(usage);
        }
    }

    private String spawnPointUsage() {
        return text("usage-spawnpoint", "Usage: /vbot spawnpoint <id|selector> "
            + "<current|worldspawn|clear|set <world> <x> <y> <z> [yaw]>");
    }

    private String afkUsage() {
        return text("usage-afk", "Usage: /vbot afk <id|selector> <status|preset <safe|farm|normal>|"
            + "set <sleep-ignored|affects-spawning|pickup|collision> <on|off|keep>|unmanage>");
    }

    private String actualStateLine(String botId, dev.nulli0n.vbot.backend.protocol.ActualState actual,
                                   boolean includeBot) {
        String spawn = actual.respawnPoint().mode().name();
        if (!actual.respawnPoint().world().isBlank()) {
            spawn += "@" + actual.respawnPoint().world();
        }
        String extended = actual.extendedPresent()
            ? text("actual-state-extended",
                ", sleepIgnored=%s, affectsSpawning=%s, pickup=%s, collidable=%s",
                actual.sleepingIgnored(), actual.affectsSpawning(), actual.pickupItems(), actual.collidable())
            : includeBot
                ? text("actual-state-extended-unavailable", ", extendedAfk=unavailable")
                : "";
        return (includeBot ? botId + ": " : text("actual-state-prefix", "Actual state: "))
            + text("actual-state-values", "invulnerable=%s, gamemode=%s, spawnpoint=%s%s.",
                actual.invulnerable(), actual.gameMode(), spawn, extended);
    }

    private String world(String raw) {
        String world = raw == null ? "" : raw.trim();
        if (!world.matches("[A-Za-z0-9_.:/-]{1,128}")) {
            throw input(text("input-world",
                "World must use 1-128 letters, digits, '.', '_', ':', '/' or '-'."));
        }
        return world;
    }

    private double finiteDouble(String raw, String name) {
        try {
            double value = Double.parseDouble(raw);
            if (Double.isFinite(value)) {
                return value;
            }
        }
        catch (NumberFormatException ignored) {
        }
        throw input(text("input-finite-number", "%s must be a finite number.", name));
    }

    private float finiteFloat(String raw, String name) {
        try {
            float value = Float.parseFloat(raw);
            if (Float.isFinite(value)) {
                return value;
            }
        }
        catch (NumberFormatException ignored) {
        }
        throw input(text("input-finite-number", "%s must be a finite number.", name));
    }

    private String text(String key, String englishFallback, Object... arguments) {
        PluginMessages active = messages.get();
        return active == null
            ? String.format(Locale.ROOT, englishFallback, arguments)
            : active.text(key, englishFallback, arguments);
    }

    private static BackendControlResult failed(String botId, Throwable failure) {
        Throwable detail = failure;
        while ((detail instanceof CompletionException || detail instanceof java.util.concurrent.ExecutionException)
            && detail.getCause() != null) {
            detail = detail.getCause();
        }
        String message = detail.getMessage();
        if (message == null || message.isBlank()) {
            message = detail.getClass().getSimpleName();
        }
        return BackendControlResult.failure(botId, BackendStatus.APPLY_FAILED, message);
    }

    private static String detailSuffix(String detail) {
        return detail == null || detail.isBlank() ? "" : " - " + detail;
    }

    private static CommandInputException usage(String message) {
        return new CommandInputException(Severity.USAGE, message);
    }

    private static CommandInputException input(String message) {
        return new CommandInputException(Severity.ERROR, message);
    }

    enum Severity {
        SUCCESS,
        WARNING,
        ERROR,
        USAGE
    }

    record Reply(Severity severity, List<String> lines) {
        Reply {
            Objects.requireNonNull(severity, "severity");
            lines = List.copyOf(lines);
        }

        static Reply error(String message) {
            return new Reply(Severity.ERROR, List.of(message));
        }
    }

    private record ParsedOperation(
        String selector,
        String label,
        BackendControlPatch patch,
        OperationKind kind
    ) {
        static ParsedOperation apply(String selector, String label, BackendControlPatch patch) {
            return new ParsedOperation(selector, label, patch, OperationKind.APPLY);
        }

        static ParsedOperation probe(String selector, String label) {
            return new ParsedOperation(selector, label, null, OperationKind.PROBE);
        }

        static ParsedOperation respawn(String selector, String label) {
            return new ParsedOperation(selector, label, null, OperationKind.RESPAWN);
        }

        static ParsedOperation recover(String selector, String label) {
            return new ParsedOperation(selector, label, null, OperationKind.RECOVER);
        }
    }

    private enum OperationKind {
        APPLY,
        PROBE,
        RESPAWN,
        RECOVER
    }

    private static final class CommandInputException extends IllegalArgumentException {
        private final Severity severity;

        private CommandInputException(Severity severity, String message) {
            super(message);
            this.severity = severity;
        }

        private Severity severity() {
            return severity;
        }
    }
}
