package dev.nulli0n.vbot.bot;

import dev.nulli0n.vbot.config.BotPluginConfig.BehaviorConfig;
import dev.nulli0n.vbot.config.BotPluginConfig.BehaviorMode;
import dev.nulli0n.vbot.transport.BotPosition;
import org.slf4j.Logger;

import java.time.Instant;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Version-neutral behavior scheduler. It deliberately uses only the existing
 * move/look/chat transport capabilities; protocol-specific actions can be
 * added as transport capabilities without changing session lifecycle logic.
 */
final class BotBehaviorRunner implements AutoCloseable {
    private final BotSession session;
    private final BehaviorConfig config;
    private final ScheduledExecutorService executor;
    private final Logger logger;
    private final AtomicBoolean requested;
    private final AtomicBoolean paused = new AtomicBoolean();
    private final AtomicLong cycles = new AtomicLong();
    private volatile ScheduledFuture<?> task;
    private volatile Instant lastActionAt;
    private volatile String lastAction = "never run";
    private volatile boolean forward = true;
    private volatile int commandIndex;
    private volatile int pathIndex;
    private volatile int serverIndex;

    BotBehaviorRunner(BotSession session, BehaviorConfig config, ScheduledExecutorService executor, Logger logger) {
        this.session = session;
        this.config = config;
        this.executor = executor;
        this.logger = logger;
        this.requested = new AtomicBoolean(config.enabled());
    }

    synchronized void onReady() {
        if (requested.get() && !paused.get()) {
            schedule(0);
        }
    }

    synchronized void onUnavailable() {
        cancelTask();
    }

    synchronized void start() {
        requested.set(true);
        paused.set(false);
        if (session.isPlayable() && session.isAuthenticationComplete()) {
            schedule(0);
        }
    }

    synchronized void pause() {
        paused.set(true);
        cancelTask();
        lastAction = "paused by operator";
    }

    BehaviorSnapshot snapshot() {
        boolean running = task != null && !task.isDone() && !paused.get();
        return new BehaviorSnapshot(config.mode(), requested.get(), running, paused.get(), cycles.get(),
            lastActionAt, lastAction);
    }

    @Override
    public synchronized void close() {
        requested.set(false);
        cancelTask();
    }

    private synchronized void schedule(long delayMillis) {
        if (task != null && !task.isDone()) {
            return;
        }
        task = executor.schedule(this::runOnce, Math.max(0L, delayMillis), TimeUnit.MILLISECONDS);
    }

    private void cancelTask() {
        ScheduledFuture<?> current = task;
        task = null;
        if (current != null) {
            current.cancel(false);
        }
    }

    private void runOnce() {
        synchronized (this) {
            task = null;
        }
        if (!requested.get() || paused.get() || !session.isPlayable() || !session.isAuthenticationComplete()) {
            return;
        }
        try {
            runAction();
            cycles.incrementAndGet();
            lastActionAt = Instant.now();
            rotateServerIfDue();
        }
        catch (RuntimeException exception) {
            lastAction = "failed: " + exception.getClass().getSimpleName();
            logger.warn("Bot {} behavior {} failed", session.definition().id(), config.mode(), exception);
        }
        synchronized (this) {
            if (requested.get() && !paused.get() && session.isPlayable() && session.isAuthenticationComplete()) {
                schedule(config.intervalMillis());
            }
        }
    }

    private void runAction() {
        switch (config.mode()) {
            case STATIC -> lastAction = "static keep-online";
            case FARM -> farmAction();
            case PATROL -> patrolAction();
            case COMMAND -> commandAction();
            case FOLLOW -> lastAction = "follow is waiting for a player target";
        }
    }

    private void farmAction() {
        BotPosition position = session.position();
        if (!position.known()) {
            lastAction = "farm waiting for initial position";
            return;
        }
        float yaw = normalizeYaw(position.yaw() + (forward ? config.yawStep() : -config.yawStep()));
        session.look(yaw, position.pitch());
        if (config.jump()) {
            session.jump();
        }
        if (config.swing()) {
            session.swingMainHand();
        }
        if (config.movementRadius() > 0.0D) {
            double offset = forward ? config.movementRadius() : -config.movementRadius();
            session.moveTo(position.x() + offset, position.y(), position.z());
            forward = !forward;
            lastAction = "farm look and move";
        }
        else {
            lastAction = "farm look";
        }
    }

    private void patrolAction() {
        if (!config.path().isEmpty()) {
            var point = config.path().get(pathIndex++ % config.path().size());
            session.moveTo(point.x(), point.y(), point.z());
            lastAction = "patrol path point " + pathIndex;
            return;
        }
        BotPosition position = session.position();
        if (!position.known() || config.movementRadius() <= 0.0D) {
            lastAction = "patrol waiting for a position and movement-radius";
            return;
        }
        double offset = forward ? config.movementRadius() : -config.movementRadius();
        session.moveTo(position.x() + offset, position.y(), position.z());
        forward = !forward;
        lastAction = "patrol move";
    }

    private void commandAction() {
        if (config.commands().isEmpty()) {
            lastAction = "command behavior has no commands";
            return;
        }
        String command = config.commands().get(commandIndex++ % config.commands().size());
        session.sendCommand(CommandTemplate.render(command, session.definition()));
        lastAction = "command sent";
    }

    private void rotateServerIfDue() {
        if (config.serverCycle().isEmpty() || config.serverCycleEvery() <= 0
            || cycles.get() % config.serverCycleEvery() != 0) {
            return;
        }
        String server = config.serverCycle().get(serverIndex++ % config.serverCycle().size());
        if (session.requestBehaviorServerSwitch(server)) {
            lastAction = "requested server switch to " + server;
        }
    }

    private static float normalizeYaw(float yaw) {
        float normalized = yaw % 360.0F;
        return normalized < 0.0F ? normalized + 360.0F : normalized;
    }

}
