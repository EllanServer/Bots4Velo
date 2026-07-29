package dev.nulli0n.vbot.command;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class VBotCommandTest {
    @Test
    void helpCatalogMatchesTheFivePublishedPages() {
        assertThat(VBotCommand.helpLines(1))
            .containsExactly(
                "/vbot list [selector] [filters] - Filter and page bots, states, servers and labels",
                "/vbot status <id> - Show detailed bot status",
                "/vbot history <id> - Show recent bot events",
                "/vbot doctor [selector] - Diagnose configuration and connectivity",
                "/vbot monitor [id] - Output monitoring JSON",
                "/vbot queue [selector] [--page n] - Show pending starts and reconnects"
            );
        assertThat(VBotCommand.helpLines(2))
            .containsExactly(
                "/vbot servers - List Velocity backends",
                "/vbot server <selector> <server> - Switch bots to a backend",
                "/vbot movehere <selector> - Bring bots to your server",
                "/vbot start|stop|reconnect <selector> - Control connections",
                "/vbot command <selector> <command> - Run a command as bots",
                "/vbot hold <selector> [--ttl 30m] [reason] - Stop and maintenance-lock bots",
                "/vbot resume <selector> - Remove a maintenance lock"
            );
        assertThat(VBotCommand.helpLines(3))
            .containsExactly(
                "/vbot behavior <selector> <action> - Start, pause or inspect behavior",
                "/vbot behavior <selector> follow <player> - Follow or unfollow a player",
                "/vbot position <id> - Show the protocol position",
                "/vbot move <id> <x> <y> <z> - Move a bot",
                "/vbot look <id> <yaw> <pitch> - Rotate a bot"
            );
        assertThat(VBotCommand.helpLines(4))
            .containsExactly(
                "/vbot create <id> <name> ... - Create a persistent bot",
                "/vbot remove <id> - Remove a managed bot",
                "/vbot reload [--check] - Preview or safely reload configuration",
                "/vbot language - Show the current UI language",
                "/vbot language <locale> - Switch the global UI language"
            );
        assertThat(VBotCommand.helpLines(5))
            .containsExactly(
                "/vbot afk <selector> status - Inspect the actual AFK policy",
                "/vbot afk <selector> <preset|set|unmanage> - Apply or stop managing an AFK policy",
                "/vbot recover <selector> - Heal, feed, extinguish and respawn",
                "/vbot invulnerable <selector> <on|off|keep> - Manage backend invulnerability",
                "/vbot gamemode <selector> <mode|unchanged> - Manage backend game mode",
                "/vbot spawnpoint <selector> <mode> - Manage the respawn point",
                "/vbot respawn <selector> - Request a backend respawn"
            );
        assertThat(VBotCommand.helpLines(6)).isEmpty();
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
    void operationsViewsAndMaintenanceCommandsUseSplitPermissions() {
        assertThat(VBotCommand.permissionFor("list")).isEqualTo("bots4velo.view");
        assertThat(VBotCommand.permissionFor("queue")).isEqualTo("bots4velo.view");
        assertThat(VBotCommand.permissionFor("hold")).isEqualTo("bots4velo.control");
        assertThat(VBotCommand.permissionFor("resume")).isEqualTo("bots4velo.control");
        assertThat(VBotCommand.permissionFor("reload", new String[]{"reload", "--check"}))
            .isEqualTo("bots4velo.reload");
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
}
