package dev.nulli0n.vbot.observe;

import dev.nulli0n.vbot.bot.BotEvent;
import org.slf4j.Logger;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpRequest.BodyPublishers;
import java.time.Duration;
import java.util.function.Consumer;

/** Best-effort webhook output; failures never affect bot lifecycle work. */
public final class WebhookNotifier implements Consumer<BotEvent> {
    private final URI endpoint;
    private final HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
    private final Logger logger;

    public WebhookNotifier(String endpoint, Logger logger) {
        this.endpoint = URI.create(endpoint);
        this.logger = logger;
    }

    @Override
    public void accept(BotEvent event) {
        String json = "{\"botId\":\"" + escape(event.botId()) + "\",\"type\":\""
            + escape(event.type()) + "\",\"detail\":\"" + escape(event.detail()) + "\",\"at\":\""
            + event.at() + "\"}";
        HttpRequest request = HttpRequest.newBuilder(endpoint).timeout(Duration.ofSeconds(10))
            .header("Content-Type", "application/json").POST(BodyPublishers.ofString(json)).build();
        client.sendAsync(request, java.net.http.HttpResponse.BodyHandlers.discarding())
            .exceptionally(error -> {
                logger.debug("Bots4Velo webhook delivery failed: {}", error.toString());
                return null;
            });
    }

    private static String escape(String value) {
        return (value == null ? "" : value).replace("\\", "\\\\").replace("\"", "\\\"")
            .replace("\n", "\\n").replace("\r", "\\r");
    }
}
