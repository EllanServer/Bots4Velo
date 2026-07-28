package dev.nulli0n.vbot.tab;

import dev.nulli0n.vbot.bot.BotManager;
import dev.nulli0n.vbot.bot.BotSession;
import dev.nulli0n.vbot.config.BotPluginConfig.BotDefinition;
import me.neznamy.tab.api.TabAPI;
import me.neznamy.tab.api.TabPlayer;
import me.neznamy.tab.api.placeholder.Placeholder;
import me.neznamy.tab.api.tablist.TabListFormatManager;
import org.slf4j.Logger;

import java.util.Locale;

/** TAB API bridge; all values are temporary and reapplied after bot reconnects. */
public final class TabIntegration implements AutoCloseable {
    public static final String GROUP_PLACEHOLDER = "%bots4velo_tab_group%";

    private final BotManager manager;
    private final TabAPI tab;
    private final Placeholder groupPlaceholder;
    private final Logger logger;

    public TabIntegration(BotManager manager, Logger logger) {
        this.manager = manager;
        this.logger = logger;
        this.tab = TabAPI.getInstance();
        if (tab == null) {
            throw new IllegalStateException("TAB API is not available");
        }
        this.groupPlaceholder = tab.getPlaceholderManager().registerPlayerPlaceholder(GROUP_PLACEHOLDER, -1,
            player -> definitionFor(player).map(BotDefinition::tabGroup).orElse(""));
    }

    public void apply() {
        TabListFormatManager names = tab.getTabListFormatManager();
        for (BotSession session : manager.sessions()) {
            BotDefinition definition = session.definition();
            TabPlayer player = tab.getPlayer(definition.username());
            if (player == null || !player.isLoaded()) {
                continue;
            }
            if (names != null) {
                names.setName(player, definition.displayName().isBlank() ? null : definition.displayName());
            }
            player.setTemporaryGroup(definition.tabGroup().isBlank() ? null : definition.tabGroup());
        }
    }

    private java.util.Optional<BotDefinition> definitionFor(TabPlayer player) {
        String name = player.getName().toLowerCase(Locale.ROOT);
        return manager.sessions().stream().map(BotSession::definition)
            .filter(definition -> definition.username().toLowerCase(Locale.ROOT).equals(name)).findFirst();
    }

    @Override
    public void close() {
        for (BotSession session : manager.sessions()) {
            TabPlayer player = tab.getPlayer(session.definition().username());
            if (player != null && player.isLoaded()) {
                TabListFormatManager names = tab.getTabListFormatManager();
                if (names != null) {
                    names.setName(player, null);
                }
                player.setTemporaryGroup(null);
            }
        }
        tab.getPlaceholderManager().unregisterPlaceholder(groupPlaceholder);
        logger.debug("Cleared Bots4Velo TAB integration");
    }
}
