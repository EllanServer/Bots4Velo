package dev.nulli0n.vbot.bot;

import dev.nulli0n.vbot.config.BotPluginConfig.BotDefinition;

public final class CommandTemplate {
    private CommandTemplate() {
    }

    public static String render(String template, BotDefinition bot) {
        String rendered = template
            .replace("{username}", bot.username())
            .replace("{password}", bot.password())
            .replace("{server}", bot.targetServer());
        while (rendered.startsWith("/")) {
            rendered = rendered.substring(1);
        }
        return rendered.trim();
    }
}
