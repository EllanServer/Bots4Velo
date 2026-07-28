package dev.nulli0n.vbot.bot;

import java.time.Instant;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;

final class BotEventLog {
    private static final int LIMIT = 50;
    private final Deque<BotEvent> events = new ArrayDeque<>();
    private final String botId;

    BotEventLog(String botId) {
        this.botId = botId;
    }

    synchronized void add(String type, String detail) {
        while (events.size() >= LIMIT) {
            events.removeFirst();
        }
        events.addLast(new BotEvent(Instant.now(), botId, type, detail == null ? "" : detail));
    }

    synchronized List<BotEvent> snapshot() {
        return List.copyOf(new ArrayList<>(events));
    }
}
