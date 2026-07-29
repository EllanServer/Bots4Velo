package dev.nulli0n.vbot;

import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.BooleanSupplier;

/**
 * One-shot reload barrier. Replacement bots may start only after the manager
 * is still current and every old bot player has disappeared from Velocity.
 */
final class ReloadHandoffGate {
    private final BooleanSupplier replacementStillCurrent;
    private final BooleanSupplier previousPlayersGone;
    private final Runnable startReplacement;
    private final AtomicBoolean finished = new AtomicBoolean();

    ReloadHandoffGate(BooleanSupplier replacementStillCurrent,
                      BooleanSupplier previousPlayersGone,
                      Runnable startReplacement) {
        this.replacementStillCurrent = Objects.requireNonNull(
            replacementStillCurrent, "replacementStillCurrent");
        this.previousPlayersGone = Objects.requireNonNull(previousPlayersGone, "previousPlayersGone");
        this.startReplacement = Objects.requireNonNull(startReplacement, "startReplacement");
    }

    PollResult poll() {
        if (finished.get()) {
            return PollResult.FINISHED;
        }
        if (!replacementStillCurrent.getAsBoolean()) {
            finished.set(true);
            return PollResult.CANCELLED;
        }
        if (!previousPlayersGone.getAsBoolean()) {
            return PollResult.WAITING;
        }
        if (!finished.compareAndSet(false, true)) {
            return PollResult.FINISHED;
        }
        startReplacement.run();
        return PollResult.STARTED;
    }

    static Set<String> mergePendingUsernames(Set<String> pending, Collection<String> current) {
        LinkedHashSet<String> merged = new LinkedHashSet<>(Objects.requireNonNull(pending, "pending"));
        merged.addAll(Objects.requireNonNull(current, "current"));
        return Set.copyOf(merged);
    }

    enum PollResult {
        WAITING,
        STARTED,
        CANCELLED,
        FINISHED
    }
}
