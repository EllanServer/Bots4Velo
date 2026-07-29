package dev.nulli0n.vbot.command;

import dev.nulli0n.vbot.bot.BotState;

import java.util.HashSet;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/**
 * Strict, side-effect-free parser and predicate shared by bot list views.
 * Arguments passed to {@link #parse(String...)} do not include the command name.
 */
record BotViewQuery(
    Optional<String> selector,
    Optional<BotState> state,
    Optional<String> server,
    boolean failedOnly,
    int page
) {
    BotViewQuery {
        selector = normalizedOptional(selector, "selector");
        state = Objects.requireNonNull(state, "state");
        server = normalizedOptional(server, "server");
        if (page < 1) {
            throw new IllegalArgumentException("page must be positive");
        }
    }

    static BotViewQuery parse(String... arguments) {
        Objects.requireNonNull(arguments, "arguments");

        String selector = null;
        BotState state = null;
        String server = null;
        boolean failedOnly = false;
        int page = 1;
        Set<String> seenOptions = new HashSet<>();

        for (int index = 0; index < arguments.length; index++) {
            String argument = normalizeArgument(arguments[index], index);
            if (!argument.startsWith("--")) {
                if (selector != null) {
                    throw error(ParseFailure.DUPLICATE_SELECTOR, argument);
                }
                validateSelector(argument);
                selector = argument;
                continue;
            }

            String option = argument.toLowerCase(Locale.ROOT);
            switch (option) {
                case "--failed" -> {
                    requireFirstOccurrence(seenOptions, option);
                    failedOnly = true;
                }
                case "--state" -> {
                    requireFirstOccurrence(seenOptions, option);
                    String value = requireValue(arguments, ++index, option);
                    state = parseState(value);
                }
                case "--server" -> {
                    requireFirstOccurrence(seenOptions, option);
                    server = requireValue(arguments, ++index, option);
                }
                case "--page" -> {
                    requireFirstOccurrence(seenOptions, option);
                    String value = requireValue(arguments, ++index, option);
                    page = parsePage(value);
                }
                default -> throw error(ParseFailure.UNKNOWN_OPTION, argument);
            }
        }

        return new BotViewQuery(Optional.ofNullable(selector), Optional.ofNullable(state),
            Optional.ofNullable(server), failedOnly, page);
    }

    boolean matches(BotViewRow row) {
        Objects.requireNonNull(row, "row");
        return selector.map(value -> matchesSelector(value, row)).orElse(true)
            && state.map(value -> value == row.state()).orElse(true)
            && server.map(value -> value.equalsIgnoreCase(row.server())).orElse(true)
            && (!failedOnly || row.failed());
    }

    private static boolean matchesSelector(String selector, BotViewRow row) {
        String normalized = selector.toLowerCase(Locale.ROOT);
        if (normalized.equals("all") || normalized.equals("@all") || normalized.equals("*")) {
            return true;
        }
        if (!normalized.startsWith("@")) {
            return row.id().equalsIgnoreCase(selector);
        }

        String expression = normalized.substring(1);
        if (expression.startsWith("group:")) {
            return row.groups().contains(expression.substring("group:".length()));
        }
        if (expression.startsWith("tag:")) {
            return row.tags().contains(expression.substring("tag:".length()));
        }
        if (expression.startsWith("server:")) {
            return row.server().equalsIgnoreCase(expression.substring("server:".length()));
        }
        return row.groups().contains(expression) || row.tags().contains(expression);
    }

    private static BotState parseState(String value) {
        String normalized = value.replace('-', '_').toUpperCase(Locale.ROOT);
        try {
            return BotState.valueOf(normalized);
        }
        catch (IllegalArgumentException exception) {
            throw error(ParseFailure.INVALID_STATE, value);
        }
    }

    private static void validateSelector(String selector) {
        String normalized = selector.toLowerCase(Locale.ROOT);
        if (!normalized.startsWith("@")) {
            return;
        }
        String expression = normalized.substring(1);
        if (expression.isBlank()
            || expression.equals("group:")
            || expression.equals("tag:")
            || expression.equals("server:")) {
            throw error(ParseFailure.INVALID_SELECTOR, selector);
        }
    }

    private static int parsePage(String value) {
        try {
            int parsed = Integer.parseInt(value);
            if (parsed < 1) {
                throw error(ParseFailure.INVALID_PAGE, value);
            }
            return parsed;
        }
        catch (NumberFormatException exception) {
            throw error(ParseFailure.INVALID_PAGE, value);
        }
    }

    private static String requireValue(String[] arguments, int index, String option) {
        if (index >= arguments.length || arguments[index] == null
            || arguments[index].isBlank() || arguments[index].trim().startsWith("--")) {
            throw error(ParseFailure.MISSING_VALUE, option);
        }
        return arguments[index].trim();
    }

    private static void requireFirstOccurrence(Set<String> seenOptions, String option) {
        if (!seenOptions.add(option)) {
            throw error(ParseFailure.DUPLICATE_OPTION, option);
        }
    }

    private static String normalizeArgument(String argument, int index) {
        if (argument == null || argument.isBlank()) {
            throw error(ParseFailure.EMPTY_ARGUMENT, Integer.toString(index));
        }
        return argument.trim();
    }

    private static Optional<String> normalizedOptional(Optional<String> value, String name) {
        Objects.requireNonNull(value, name);
        return value.map(item -> {
            String normalized = Objects.requireNonNull(item, name).trim();
            if (normalized.isEmpty()) {
                throw new IllegalArgumentException(name + " must not be blank");
            }
            return normalized;
        });
    }

    private static ParseException error(ParseFailure failure, String token) {
        return new ParseException(failure, token);
    }

    enum ParseFailure {
        UNKNOWN_OPTION,
        DUPLICATE_OPTION,
        DUPLICATE_SELECTOR,
        INVALID_SELECTOR,
        MISSING_VALUE,
        INVALID_STATE,
        INVALID_PAGE,
        EMPTY_ARGUMENT
    }

    static final class ParseException extends IllegalArgumentException {
        private final ParseFailure failure;
        private final String token;

        private ParseException(ParseFailure failure, String token) {
            super(failure + ": " + token);
            this.failure = Objects.requireNonNull(failure, "failure");
            this.token = Objects.requireNonNull(token, "token");
        }

        ParseFailure failure() {
            return failure;
        }

        String token() {
            return token;
        }
    }
}
