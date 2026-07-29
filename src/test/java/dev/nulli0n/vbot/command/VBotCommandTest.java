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
            .anyMatch(line -> line.contains("respawn"))
            .anyMatch(line -> line.contains("afk"))
            .anyMatch(line -> line.contains("recover"));
        assertThat(VBotCommand.helpLines(4)).isEmpty();
    }

    @Test
    void playerStateActionsUseControlPermission() {
        assertThat(VBotCommand.permissionFor("invulnerable")).isEqualTo("bots4velo.control");
        assertThat(VBotCommand.permissionFor("gamemode")).isEqualTo("bots4velo.control");
        assertThat(VBotCommand.permissionFor("spawnpoint")).isEqualTo("bots4velo.control");
        assertThat(VBotCommand.permissionFor("respawn")).isEqualTo("bots4velo.control");
        assertThat(VBotCommand.permissionFor("afk")).isEqualTo("bots4velo.control");
        assertThat(VBotCommand.permissionFor("recover")).isEqualTo("bots4velo.control");
        assertThat(VBotCommand.permissionFor("afk", new String[]{"afk", "all", "status"}))
            .isEqualTo("bots4velo.view");
        assertThat(VBotCommand.permissionFor("afk", new String[]{"afk", "all", "preset", "safe"}))
            .isEqualTo("bots4velo.control");
    }

    @Test
    void afkSuggestionsSeparateViewAndControlActions() {
        assertThat(VBotCommand.afkActionSuggestions("", true, false))
            .containsExactly("status");
        assertThat(VBotCommand.afkActionSuggestions("", false, true))
            .containsExactly("preset", "set", "unmanage");
        assertThat(VBotCommand.afkActionSuggestions("", true, true))
            .containsExactly("status", "preset", "set", "unmanage");
        assertThat(VBotCommand.afkActionSuggestions("p", true, true))
            .containsExactly("preset");
        assertThat(VBotCommand.afkActionSuggestions("", false, false)).isEmpty();
    }

    private static boolean isAscii(String value) {
        return value.chars().allMatch(character -> character <= 0x7F);
    }
}
