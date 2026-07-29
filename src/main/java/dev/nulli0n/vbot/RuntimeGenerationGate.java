package dev.nulli0n.vbot;

import java.util.concurrent.atomic.AtomicLong;

/** Invalidates asynchronous runtime callbacks across reload and shutdown. */
final class RuntimeGenerationGate {
    private final AtomicLong generation = new AtomicLong();

    long advance() {
        return generation.incrementAndGet();
    }

    long current() {
        return generation.get();
    }

    boolean matches(long expected) {
        return generation.get() == expected;
    }
}
