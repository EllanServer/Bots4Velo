package dev.nulli0n.vbot.command;

import dev.nulli0n.vbot.bot.BotState;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import java.util.Optional;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class BotViewQueryTest {
    @Test
    void emptyArgumentsSelectAllBotsOnTheFirstPage() {
        BotViewQuery query = BotViewQuery.parse();

        assertThat(query.selector()).isEmpty();
        assertThat(query.state()).isEmpty();
        assertThat(query.server()).isEmpty();
        assertThat(query.failedOnly()).isFalse();
        assertThat(query.page()).isEqualTo(1);
    }

    @Test
    void parsesTheSelectorAndEveryFilterInAnyOrder() {
        BotViewQuery query = BotViewQuery.parse("--page", "3", "--FAILED", "@farm",
            "--server", "Survival", "--state", "reconnect-wait");

        assertThat(query.selector()).contains("@farm");
        assertThat(query.state()).contains(BotState.RECONNECT_WAIT);
        assertThat(query.server()).contains("Survival");
        assertThat(query.failedOnly()).isTrue();
        assertThat(query.page()).isEqualTo(3);
    }

    @ParameterizedTest
    @MethodSource("invalidArguments")
    void rejectsInvalidArguments(BotViewQuery.ParseFailure expected, String[] arguments) {
        assertThatThrownBy(() -> BotViewQuery.parse(arguments))
            .isInstanceOfSatisfying(BotViewQuery.ParseException.class,
                exception -> assertThat(exception.failure()).isEqualTo(expected));
    }

    private static Stream<Arguments> invalidArguments() {
        return Stream.of(
            Arguments.of(BotViewQuery.ParseFailure.UNKNOWN_OPTION, new String[]{"--wat"}),
            Arguments.of(BotViewQuery.ParseFailure.DUPLICATE_OPTION,
                new String[]{"--failed", "--FAILED"}),
            Arguments.of(BotViewQuery.ParseFailure.DUPLICATE_OPTION,
                new String[]{"--state", "play", "--state", "failed"}),
            Arguments.of(BotViewQuery.ParseFailure.DUPLICATE_OPTION,
                new String[]{"--server", "one", "--server", "two"}),
            Arguments.of(BotViewQuery.ParseFailure.DUPLICATE_OPTION,
                new String[]{"--page", "1", "--page", "2"}),
            Arguments.of(BotViewQuery.ParseFailure.DUPLICATE_SELECTOR,
                new String[]{"farm01", "farm02"}),
            Arguments.of(BotViewQuery.ParseFailure.INVALID_SELECTOR, new String[]{"@"}),
            Arguments.of(BotViewQuery.ParseFailure.INVALID_SELECTOR, new String[]{"@group:"}),
            Arguments.of(BotViewQuery.ParseFailure.INVALID_SELECTOR, new String[]{"@tag:"}),
            Arguments.of(BotViewQuery.ParseFailure.INVALID_SELECTOR, new String[]{"@server:"}),
            Arguments.of(BotViewQuery.ParseFailure.MISSING_VALUE, new String[]{"--state"}),
            Arguments.of(BotViewQuery.ParseFailure.MISSING_VALUE,
                new String[]{"--server", "--failed"}),
            Arguments.of(BotViewQuery.ParseFailure.MISSING_VALUE, new String[]{"--page", ""}),
            Arguments.of(BotViewQuery.ParseFailure.INVALID_STATE,
                new String[]{"--state", "playing"}),
            Arguments.of(BotViewQuery.ParseFailure.INVALID_PAGE, new String[]{"--page", "0"}),
            Arguments.of(BotViewQuery.ParseFailure.INVALID_PAGE, new String[]{"--page", "-2"}),
            Arguments.of(BotViewQuery.ParseFailure.INVALID_PAGE, new String[]{"--page", "many"}),
            Arguments.of(BotViewQuery.ParseFailure.INVALID_PAGE,
                new String[]{"--page", "999999999999999999999"}),
            Arguments.of(BotViewQuery.ParseFailure.EMPTY_ARGUMENT, new String[]{" "}),
            Arguments.of(BotViewQuery.ParseFailure.EMPTY_ARGUMENT, new String[]{null})
        );
    }

    @Test
    void validatesDirectConstructionToo() {
        assertThatThrownBy(() -> new BotViewQuery(Optional.empty(), Optional.empty(), Optional.empty(), false, 0))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("positive");
        assertThatThrownBy(() -> new BotViewQuery(Optional.of(" "), Optional.empty(), Optional.empty(), false, 1))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("selector");
    }
}
