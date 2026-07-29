package dev.nulli0n.vbot.command;

import com.velocitypowered.api.command.CommandSource;
import dev.nulli0n.vbot.Bots4VeloPlugin;
import dev.nulli0n.vbot.config.ConfigChangePreview;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;

/** Renders secret-safe reload validation and applies validated replacements. */
final class ReloadCommandHandler {
    private final Bots4VeloPlugin plugin;

    ReloadCommandHandler(Bots4VeloPlugin plugin) {
        this.plugin = plugin;
    }

    void execute(CommandSource source, String[] args) {
        try {
            executeValidated(source, args);
        }
        catch (Exception exception) {
            // SnakeYAML exceptions can embed the complete offending line,
            // including passwords. Never return or log their raw message.
            plugin.logger().warn("/vbot reload validation failed ({})",
                ConfigValidationFailure.diagnosticType(exception));
            feedback(source, NamedTextColor.RED, "reload-validation-failed",
                ConfigValidationFailure.userMessage());
        }
    }

    private void executeValidated(CommandSource source, String[] args) throws java.io.IOException {
        if (args.length == 1) {
            Bots4VeloPlugin.ReloadResult result = plugin.reload();
            source.sendMessage(CommandUi.feedback(plugin.messages(), NamedTextColor.GREEN,
                "reload-success-v29",
                "Configuration reloaded (%s bots, %s managed); safe player handoff controls bot startup.",
                result.configuredBots(), result.managedBots()));
            return;
        }
        if (args.length != 2 || !args[1].equalsIgnoreCase("--check")) {
            feedback(source, NamedTextColor.YELLOW, "usage-reload", "Usage: /vbot reload [--check]");
            return;
        }
        Bots4VeloPlugin.ReloadCheckResult result = plugin.previewReload();
        ConfigChangePreview.Preview preview = result.preview();
        source.sendMessage(CommandUi.feedback(plugin.messages(), NamedTextColor.GOLD,
            "reload-check-title", "Reload preview: %s change(s), %s configured bot(s), %s managed bot(s).",
            preview.totalChanges(), result.configuredBots(), result.managedBots()));
        if (!result.currentLanguage().equalsIgnoreCase(result.candidateLanguage())) {
            source.sendMessage(Component.text(plugin.messages().text("reload-check-language-changed",
                "Language changed: %s -> %s", result.currentLanguage(), result.candidateLanguage()),
                NamedTextColor.YELLOW));
        }
        if (!preview.hasChanges()) {
            source.sendMessage(Component.text(plugin.messages().text("reload-check-none",
                "No configuration changes."), NamedTextColor.GRAY));
        }
        for (ConfigChangePreview.Change change : preview.changes()) {
            String fallback = switch (change.type()) {
                case PROXY_CHANGED -> "Proxy settings changed: %s";
                case RUNTIME_CHANGED -> "Runtime settings changed: %s";
                case BOT_ADDED -> "Bot added: %s";
                case BOT_REMOVED -> "Bot removed: %s";
                case BOT_CHANGED -> "Bot changed: %s (fields: %s)";
            };
            source.sendMessage(Component.text(" - " + plugin.messages().text(change.localizationKey(), fallback,
                change.localizationArguments().toArray()), NamedTextColor.GRAY));
        }
        if (preview.truncated()) {
            source.sendMessage(Component.text(plugin.messages().text("reload-check-omitted",
                "... %s additional change(s) omitted.", preview.omittedChanges()), NamedTextColor.DARK_GRAY));
        }
        source.sendMessage(Component.text(plugin.messages().text("reload-check-safe",
            "No live bots were changed. Run /vbot reload to apply this validated configuration."),
            NamedTextColor.GREEN));
    }

    private void feedback(CommandSource source, NamedTextColor color, String key,
                          String englishFallback, Object... arguments) {
        source.sendMessage(CommandUi.feedback(plugin.messages(), color, key, englishFallback, arguments));
    }
}
