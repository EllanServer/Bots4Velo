package dev.nulli0n.vbot.command;

import dev.nulli0n.vbot.config.ManagedCredentialReference;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

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
                "/vbot behavior <selector> status - Inspect behavior status",
                "/vbot behavior <selector> <start|pause> - Start or pause behavior",
                "/vbot behavior <selector> <follow|unfollow> [player] - Follow or unfollow a player",
                "/vbot position <id> - Show the protocol position",
                "/vbot move <id> <x> <y> <z> - Move a bot",
                "/vbot look <id> <yaw> <pitch> - Rotate a bot"
            );
        assertThat(VBotCommand.helpLines(4))
            .containsExactly(
                "/vbot create <id> <name> <credential> [server] - Create a persistent bot from a secret or environment reference",
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
    void behaviorStatusUsesViewWhileMutationsUseControl() {
        assertThat(VBotCommand.permissionFor("behavior", new String[]{"behavior", "all", "status"}))
            .isEqualTo("bots4velo.view");
        for (String action : List.of("start", "pause", "follow", "unfollow")) {
            assertThat(VBotCommand.permissionFor("behavior", new String[]{"behavior", "all", action}))
                .as(action)
                .isEqualTo("bots4velo.control");
        }
    }

    @Test
    void commandRootIsAvailableToEveryDefinedRoleButNotToUnprivilegedSources() {
        for (String permission : List.of("bots4velo.view", "bots4velo.control", "bots4velo.create",
            "bots4velo.reload", "bots4velo.admin")) {
            assertThat(VBotCommand.canAccessRoot(Set.of(permission)::contains)).as(permission).isTrue();
        }
        assertThat(VBotCommand.canAccessRoot(ignored -> false)).isFalse();
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

    @Test
    void behaviorSuggestionsSeparateViewAndControlActions() {
        assertThat(VBotCommand.behaviorActionSuggestions("", true, false))
            .containsExactly("status");
        assertThat(VBotCommand.behaviorActionSuggestions("", false, true))
            .containsExactly("start", "pause", "follow", "unfollow");
        assertThat(VBotCommand.behaviorActionSuggestions("", true, true))
            .containsExactly("status", "start", "pause", "follow", "unfollow");
        assertThat(VBotCommand.behaviorActionSuggestions("f", true, true))
            .containsExactly("follow");
        assertThat(VBotCommand.behaviorActionSuggestions("", false, false)).isEmpty();
    }

    @Test
    void targetSuggestionsDistinguishExactIdsSelectorsAndManagedIds() {
        List<String> botIds = List.of("IronFarm", "LobbyBot", "StaticBot");
        List<String> managedIds = List.of("IronFarm", "LobbyBot");
        List<String> selectors = List.of("all", "IronFarm", "LobbyBot", "StaticBot",
            "@group:farm", "@tag:backup", "@server:lobby");

        for (String action : List.of("status", "monitor", "history", "position", "move", "look")) {
            assertThat(VBotCommand.targetSuggestions(action, "", botIds, managedIds, selectors))
                .as(action)
                .containsExactlyElementsOf(botIds)
                .noneMatch(value -> value.equals("all") || value.startsWith("@"));
        }
        for (String action : List.of("doctor", "server", "movehere", "start", "stop", "reconnect",
            "hold", "resume", "command", "behavior", "invulnerable", "gamemode", "spawnpoint",
            "respawn", "afk", "recover")) {
            assertThat(VBotCommand.targetSuggestions(action, "@", botIds, managedIds, selectors))
                .as(action)
                .containsExactly("@group:farm", "@tag:backup", "@server:lobby");
        }
        assertThat(VBotCommand.targetSuggestions("remove", "", botIds, managedIds, selectors))
            .containsExactlyElementsOf(managedIds)
            .doesNotContain("StaticBot", "all", "@group:farm");
        assertThat(VBotCommand.targetSuggestions("create", "", botIds, managedIds, selectors)).isEmpty();
    }

    @Test
    void querySuggestionsDoNotOfferOptionsAlreadyCommitted() {
        List<String> servers = List.of("lobby", "survival");

        assertThat(VBotCommand.querySuggestions(new String[]{"list", ""}, servers))
            .containsExactly("--state", "--server", "--failed", "--page");
        assertThat(VBotCommand.querySuggestions(
            new String[]{"list", "all", "--state", "play", "--failed", ""}, servers))
            .containsExactly("--server", "--page");
        assertThat(VBotCommand.querySuggestions(
            new String[]{"list", "--state", "play", "--p"}, servers))
            .containsExactly("--page");
        assertThat(VBotCommand.querySuggestions(
            new String[]{"queue", "@server:lobby", "--server", "l"}, servers))
            .containsExactly("lobby");
        assertThat(VBotCommand.querySuggestions(
            new String[]{"queue", "--state", "re"}, servers))
            .containsExactly("reconnect_wait");
        assertThat(VBotCommand.querySuggestions(
            new String[]{"list", "--state", "play", "--state", ""}, servers)).isEmpty();
    }

    @Test
    void followSuggestionsUseSortedCaseInsensitiveOnlinePlayerNames() {
        assertThat(VBotCommand.playerNameSuggestions("a", List.of("Zed", "alice", "ALBERT", "Builder")))
            .containsExactly("ALBERT", "alice");
        assertThat(VBotCommand.playerNameSuggestions("missing", List.of("alice", "Builder"))).isEmpty();
    }

    @Test
    void managedCreateAcceptsOnlyReferencesAndNeverReflectsAnInlineToken() {
        assertThat(VBotCommand.parseManagedCredentialToken("-"))
            .isEqualTo(ManagedCredentialReference.none());
        assertThat(VBotCommand.parseManagedCredentialToken("secret:Farm01"))
            .isEqualTo(ManagedCredentialReference.secret("Farm01"));
        assertThat(VBotCommand.parseManagedCredentialToken("ENV:BOTS4VELO_FARM01"))
            .isEqualTo(ManagedCredentialReference.environment("BOTS4VELO_FARM01"));

        String inline = "raw-password-that-must-not-be-echoed";
        assertThatThrownBy(() -> VBotCommand.parseManagedCredentialToken(inline))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessage("Invalid managed credential reference")
            .hasMessageNotContaining(inline);
        assertThatThrownBy(() -> VBotCommand.parseManagedCredentialToken("secret:"))
            .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> VBotCommand.parseManagedCredentialToken("env:bad-name"))
            .isInstanceOf(IllegalArgumentException.class);
    }
}
