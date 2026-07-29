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
        assertThat(VBotCommand.helpLines(3))
            .allMatch(line -> line.length() <= 64)
            .allMatch(VBotCommandTest::isAscii)
            .anyMatch(line -> line.contains("invulnerable"))
            .anyMatch(line -> line.contains("gamemode"))
            .anyMatch(line -> line.contains("spawnpoint"))
            .anyMatch(line -> line.contains("respawn"));
        assertThat(VBotCommand.helpLines(4)).isEmpty();
    }

    @Test
    void playerStateActionsUseControlPermission() {
        assertThat(VBotCommand.permissionFor("invulnerable")).isEqualTo("bots4velo.control");
        assertThat(VBotCommand.permissionFor("gamemode")).isEqualTo("bots4velo.control");
        assertThat(VBotCommand.permissionFor("spawnpoint")).isEqualTo("bots4velo.control");
        assertThat(VBotCommand.permissionFor("respawn")).isEqualTo("bots4velo.control");
    }

    private static boolean isAscii(String value) {
        return value.chars().allMatch(character -> character <= 0x7F);
    }
}
