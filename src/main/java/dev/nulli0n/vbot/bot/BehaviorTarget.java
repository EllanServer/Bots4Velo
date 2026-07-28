package dev.nulli0n.vbot.bot;

import dev.nulli0n.vbot.config.BotPluginConfig.BotDefinition;
import dev.nulli0n.vbot.transport.BotPosition;

/** Protocol-independent surface used by the behavior scheduler. */
interface BehaviorTarget {
    BotDefinition definition();

    boolean isPlayable();

    boolean isAuthenticationComplete();

    BotPosition position();

    boolean moveTo(double x, double y, double z);

    boolean look(float yaw, float pitch);

    boolean swingMainHand();

    boolean jump();

    boolean setSneaking(boolean sneaking);

    boolean sendCommand(String command);

    boolean requestBehaviorServerSwitch(String server);

    String followTarget();
}
