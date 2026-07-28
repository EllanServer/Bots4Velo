package dev.nulli0n.vbot.command;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class VBotCommandTest {
    @Test
    void helpIsSplitIntoShortReadableLines() {
        assertThat(VBotCommand.helpLines(1))
            .allMatch(line -> line.length() <= 64)
            .allMatch(VBotCommandTest::isAscii)
            .anyMatch(line -> line.contains("server <id|selector> <server>"))
            .anyMatch(line -> line.contains("movehere"));
        assertThat(VBotCommand.helpLines(2))
            .allMatch(line -> line.length() <= 64)
            .allMatch(VBotCommandTest::isAscii)
            .anyMatch(line -> line.contains("create"))
            .anyMatch(line -> line.contains("selector"));
        assertThat(VBotCommand.helpLines(3)).isEmpty();
    }

    private static boolean isAscii(String value) {
        return value.chars().allMatch(character -> character <= 0x7F);
    }
}
