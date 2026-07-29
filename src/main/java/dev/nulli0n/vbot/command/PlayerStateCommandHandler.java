package dev.nulli0n.vbot.command;

import dev.nulli0n.vbot.backend.BackendControlPatch;
import dev.nulli0n.vbot.backend.BackendControlResult;
import dev.nulli0n.vbot.backend.BackendControlService;
import dev.nulli0n.vbot.backend.InvulnerabilityChange;
import dev.nulli0n.vbot.backend.protocol.BackendGameMode;
import dev.nulli0n.vbot.backend.protocol.BackendStatus;
import dev.nulli0n.vbot.backend.protocol.RespawnPoint;

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

/** Parses player-state commands and summarizes asynchronous backend ACKs. */
final class PlayerStateCommandHandler {
    private static final int MAXIMUM_FAILURE_LINES = 10;

    private final BackendControlService backend;
    private final Function<String, List<String>> selector;

    PlayerStateCommandHandler(BackendControlService backend, Function<String, List<String>> selector) {
        this.backend = Objects.requireNonNull(backend, "backend");
        this.selector = Objects.requireNonNull(selector, "selector");
    }

    CompletableFuture<Reply> execute(String action, String[] args) {
        try {
            ParsedOperation operation = parse(action, args);
            List<String> targets = select(operation.selector());
            if (targets.isEmpty()) {
                return CompletableFuture.completedFuture(Reply.error(
                    "No bots matched selector: " + operation.selector()));
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
        return completed.thenApply(ignored -> summarize(operation.label(), acknowledgements));
    }

    private CompletableFuture<BackendControlResult> invoke(String botId, ParsedOperation operation) {
        CompletionStage<BackendControlResult> stage;
        try {
            stage = operation.respawn()
                ? backend.respawn(botId)
                : backend.apply(botId, operation.patch());
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

    private static Reply summarize(String label,
                                   List<CompletableFuture<BackendControlResult>> acknowledgements) {
        List<BackendControlResult> results = acknowledgements.stream().map(CompletableFuture::join).toList();
        long succeeded = results.stream().filter(BackendControlResult::successful).count();
        Severity severity = succeeded == results.size()
            ? Severity.SUCCESS : (succeeded == 0 ? Severity.ERROR : Severity.WARNING);
        List<String> lines = new ArrayList<>();
        lines.add(label + " acknowledged by " + succeeded + "/" + results.size() + " bot(s).");
        if (results.size() == 1 && results.getFirst().successful()
            && results.getFirst().actualState().present()) {
            var actual = results.getFirst().actualState();
            String spawn = actual.respawnPoint().mode().name();
            if (!actual.respawnPoint().world().isBlank()) {
                spawn += "@" + actual.respawnPoint().world();
            }
            lines.add("Actual state: invulnerable=" + actual.invulnerable()
                + ", gamemode=" + actual.gameMode() + ", spawnpoint=" + spawn + ".");
        }
        List<BackendControlResult> failures = results.stream()
            .filter(result -> !result.successful()).toList();
        failures.stream().limit(MAXIMUM_FAILURE_LINES).forEach(result -> lines.add(
            result.botId() + ": " + result.status() + detailSuffix(result.detail())));
        if (failures.size() > MAXIMUM_FAILURE_LINES) {
            lines.add("... and " + (failures.size() - MAXIMUM_FAILURE_LINES) + " more failure(s).");
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

    private static ParsedOperation parse(String action, String[] args) {
        return switch (action.toLowerCase(Locale.ROOT)) {
            case "invulnerable" -> parseInvulnerable(args);
            case "gamemode" -> parseGameMode(args);
            case "spawnpoint" -> parseSpawnPoint(args);
            case "respawn" -> parseRespawn(args);
            default -> throw new IllegalArgumentException("Unsupported player-state action: " + action);
        };
    }

    private static ParsedOperation parseInvulnerable(String[] args) {
        if (args.length != 3) {
            throw usage("Usage: /vbot invulnerable <id|selector> <on|off|keep>");
        }
        InvulnerabilityChange change = switch (args[2].toLowerCase(Locale.ROOT)) {
            case "on" -> InvulnerabilityChange.ENABLED;
            case "off" -> InvulnerabilityChange.DISABLED;
            case "keep" -> InvulnerabilityChange.KEEP;
            default -> throw input("Invulnerability must be on, off or keep.");
        };
        return ParsedOperation.apply(args[1], "Invulnerability update",
            BackendControlPatch.invulnerability(change));
    }

    private static ParsedOperation parseGameMode(String[] args) {
        if (args.length != 3) {
            throw usage("Usage: /vbot gamemode <id|selector> "
                + "<survival|creative|adventure|spectator|unchanged>");
        }
        BackendGameMode gameMode = switch (args[2].toLowerCase(Locale.ROOT)) {
            case "survival" -> BackendGameMode.SURVIVAL;
            case "creative" -> BackendGameMode.CREATIVE;
            case "adventure" -> BackendGameMode.ADVENTURE;
            case "spectator" -> BackendGameMode.SPECTATOR;
            case "unchanged" -> BackendGameMode.UNCHANGED;
            default -> throw input("Game mode must be survival, creative, adventure, spectator or unchanged.");
        };
        return ParsedOperation.apply(args[1], "Game mode update", BackendControlPatch.gameMode(gameMode));
    }

    private static ParsedOperation parseSpawnPoint(String[] args) {
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
            default -> throw input("Spawn point mode must be current, worldspawn, clear or set.");
        }
        return ParsedOperation.apply(args[1], "Respawn point update",
            BackendControlPatch.respawnPoint(point));
    }

    private static ParsedOperation parseRespawn(String[] args) {
        if (args.length != 2) {
            throw usage("Usage: /vbot respawn <id|selector>");
        }
        return ParsedOperation.respawn(args[1]);
    }

    private static void requireLength(String[] args, int expected, String usage) {
        if (args.length != expected) {
            throw usage(usage);
        }
    }

    private static String spawnPointUsage() {
        return "Usage: /vbot spawnpoint <id|selector> "
            + "<current|worldspawn|clear|set <world> <x> <y> <z> [yaw]>";
    }

    private static String world(String raw) {
        String world = raw == null ? "" : raw.trim();
        if (!world.matches("[A-Za-z0-9_.:/-]{1,128}")) {
            throw input("World must use 1-128 letters, digits, '.', '_', ':', '/' or '-'.");
        }
        return world;
    }

    private static double finiteDouble(String raw, String name) {
        try {
            double value = Double.parseDouble(raw);
            if (Double.isFinite(value)) {
                return value;
            }
        }
        catch (NumberFormatException ignored) {
        }
        throw input(name + " must be a finite number.");
    }

    private static float finiteFloat(String raw, String name) {
        try {
            float value = Float.parseFloat(raw);
            if (Float.isFinite(value)) {
                return value;
            }
        }
        catch (NumberFormatException ignored) {
        }
        throw input(name + " must be a finite number.");
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
        boolean respawn
    ) {
        static ParsedOperation apply(String selector, String label, BackendControlPatch patch) {
            return new ParsedOperation(selector, label, patch, false);
        }

        static ParsedOperation respawn(String selector) {
            return new ParsedOperation(selector, "Respawn request", null, true);
        }
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
