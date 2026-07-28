package dev.nulli0n.vbot.bot;

import dev.nulli0n.vbot.config.BotPluginConfig.AuthConfig;
import dev.nulli0n.vbot.config.BotPluginConfig.AuthMode;
import dev.nulli0n.vbot.config.BotPluginConfig.BehaviorConfig;
import dev.nulli0n.vbot.config.BotPluginConfig.BehaviorMode;
import dev.nulli0n.vbot.config.BotPluginConfig.BotDefinition;
import dev.nulli0n.vbot.transport.BotPosition;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

class BotBehaviorRunnerTest {
    @Test
    void farmBehaviorUsesCrossProtocolActionsAndTracksStatus() throws Exception {
        FakeTarget target = new FakeTarget();
        BehaviorConfig config = new BehaviorConfig(BehaviorMode.FARM, true, 250, 1.0, 20.0F,
            false, true, true, true, List.of(), List.of(), List.of(), 0, "");
        ScheduledExecutorService executor = Executors.newSingleThreadScheduledExecutor();
        BotBehaviorRunner runner = new BotBehaviorRunner(target, config, executor,
            LoggerFactory.getLogger(BotBehaviorRunnerTest.class));
        try {
            runner.onReady();
            assertThat(target.action.await(1, TimeUnit.SECONDS)).isTrue();
            BehaviorSnapshot snapshot = runner.snapshot();
            assertThat(snapshot.cycles()).isGreaterThanOrEqualTo(1);
            assertThat(target.looks).isGreaterThanOrEqualTo(1);
            assertThat(target.moves).isGreaterThanOrEqualTo(1);
            assertThat(target.jumps).isGreaterThanOrEqualTo(1);
            assertThat(target.swings).isGreaterThanOrEqualTo(1);
            assertThat(target.sneaking).isTrue();
        }
        finally {
            runner.close();
            executor.shutdownNow();
        }
    }

    @Test
    void commandBehaviorRendersAndCyclesCommands() throws Exception {
        FakeTarget target = new FakeTarget();
        BehaviorConfig config = new BehaviorConfig(BehaviorMode.COMMAND, true, 250, 0, 0,
            false, false, false, false, List.of("say {id}"), List.of(), List.of(), 0, "");
        ScheduledExecutorService executor = Executors.newSingleThreadScheduledExecutor();
        BotBehaviorRunner runner = new BotBehaviorRunner(target, config, executor,
            LoggerFactory.getLogger(BotBehaviorRunnerTest.class));
        try {
            runner.onReady();
            assertThat(target.action.await(1, TimeUnit.SECONDS)).isTrue();
            assertThat(target.lastCommand).isEqualTo("say farm");
        }
        finally {
            runner.close();
            executor.shutdownNow();
        }
    }

    private static final class FakeTarget implements BehaviorTarget {
        private final CountDownLatch action = new CountDownLatch(1);
        private final BotDefinition definition = new BotDefinition("farm", true, "AFK_Farm", "secret", "", "", 2,
            new AuthConfig(AuthMode.NONE, "", "", 0, 0, 0, List.of(), List.of(), List.of()), "", 0, 0, List.of());
        private int looks;
        private int moves;
        private int jumps;
        private int swings;
        private boolean sneaking;
        private String lastCommand = "";

        @Override public BotDefinition definition() { return definition; }
        @Override public boolean isPlayable() { return true; }
        @Override public boolean isAuthenticationComplete() { return true; }
        @Override public BotPosition position() { return BotPosition.known(0, 64, 0, 0, 0); }
        @Override public boolean moveTo(double x, double y, double z) { moves++; action.countDown(); return true; }
        @Override public boolean look(float yaw, float pitch) { looks++; return true; }
        @Override public boolean swingMainHand() { swings++; return true; }
        @Override public boolean jump() { jumps++; return true; }
        @Override public boolean setSneaking(boolean value) { sneaking = value; return true; }
        @Override public boolean sendCommand(String command) { lastCommand = command; action.countDown(); return true; }
        @Override public boolean requestBehaviorServerSwitch(String server) { return true; }
        @Override public String followTarget() { return ""; }
    }
}
