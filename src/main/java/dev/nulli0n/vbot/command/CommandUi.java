package dev.nulli0n.vbot.command;

import dev.nulli0n.vbot.message.PluginMessages;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;

/** Shared visual treatment for concise command feedback. */
final class CommandUi {
    private CommandUi() {
    }

    static Component feedback(PluginMessages messages, NamedTextColor color, String key,
                              String englishFallback, Object... arguments) {
        return feedback(color, messages.text(key, englishFallback, arguments));
    }

    static Component feedback(NamedTextColor color, String message) {
        return Component.text("[", NamedTextColor.DARK_GRAY)
            .append(Component.text("Bots4Velo", NamedTextColor.GOLD, TextDecoration.BOLD))
            .append(Component.text("] ", NamedTextColor.DARK_GRAY))
            .append(Component.text(message, color));
    }
}
