package dev.nulli0n.vbot.observe;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import dev.nulli0n.vbot.bot.BotSnapshot;
import dev.nulli0n.vbot.bot.BotState;
import dev.nulli0n.vbot.bot.FailureCategory;
import org.slf4j.Logger;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.Supplier;

/** Minimal dependency-free Prometheus endpoint for operational monitoring. */
public final class PrometheusExporter implements AutoCloseable {
    private final HttpServer server;
    private final ExecutorService executor;

    public PrometheusExporter(String address, int port, Supplier<List<BotSnapshot>> snapshots, Logger logger)
        throws IOException {
        server = HttpServer.create(new InetSocketAddress(address, port), 0);
        executor = Executors.newSingleThreadExecutor(runnable -> {
            Thread thread = new Thread(runnable, "bots4velo-prometheus");
            thread.setDaemon(true);
            return thread;
        });
        server.setExecutor(executor);
        server.createContext("/metrics", exchange -> writeMetrics(exchange, snapshots.get()));
        server.start();
        logger.info("Bots4Velo Prometheus metrics listening on {}:{}/metrics", address, port);
    }

    private static void writeMetrics(HttpExchange exchange, List<BotSnapshot> snapshots) throws IOException {
        if (!"GET".equalsIgnoreCase(exchange.getRequestMethod())) {
            exchange.sendResponseHeaders(405, -1);
            return;
        }
        byte[] body = render(snapshots).getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "text/plain; version=0.0.4; charset=utf-8");
        exchange.sendResponseHeaders(200, body.length);
        try (var output = exchange.getResponseBody()) {
            output.write(body);
        }
    }

    static String render(List<BotSnapshot> snapshots) {
        StringBuilder output = new StringBuilder()
            .append("# HELP bots4velo_bots_total Number of configured bots.\n")
            .append("# TYPE bots4velo_bots_total gauge\n")
            .append("bots4velo_bots_total ").append(snapshots.size()).append('\n')
            .append("# HELP bots4velo_bot_online Whether the bot is in PLAY.\n")
            .append("# TYPE bots4velo_bot_online gauge\n");
        for (BotSnapshot snapshot : snapshots) {
            String labels = "id=\"" + label(snapshot.id()) + "\",username=\"" + label(snapshot.username()) + "\"";
            output.append("bots4velo_bot_online{").append(labels).append("} ")
                .append(snapshot.state() == BotState.PLAY ? 1 : 0).append('\n');
            output.append("bots4velo_bot_reconnect_attempts{").append(labels).append("} ")
                .append(snapshot.reconnectAttempts()).append('\n');
            output.append("bots4velo_bot_disconnects_total{").append(labels).append("} ")
                .append(snapshot.disconnects()).append('\n');
            output.append("bots4velo_bot_resource_packs_loaded_total{").append(labels).append("} ")
                .append(snapshot.resourcePacksLoaded()).append('\n');
            output.append("bots4velo_bot_online_seconds{").append(labels).append("} ")
                .append(snapshot.onlineSeconds()).append('\n');
            for (FailureCategory category : FailureCategory.values()) {
                output.append("bots4velo_bot_failure{").append(labels).append(",category=\"")
                    .append(category.name().toLowerCase(java.util.Locale.ROOT)).append("\"} ")
                    .append(snapshot.failureCategory() == category ? 1 : 0).append('\n');
            }
        }
        return output.toString();
    }

    private static String label(String value) {
        return value.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n");
    }

    @Override
    public void close() {
        server.stop(0);
        executor.shutdownNow();
    }
}
