package dev.nulli0n.vbot.paper;

import dev.nulli0n.vbot.backend.protocol.ActualState;
import dev.nulli0n.vbot.backend.protocol.BackendGameMode;
import dev.nulli0n.vbot.backend.protocol.BackendInvulnerability;
import dev.nulli0n.vbot.backend.protocol.BackendPolicy;
import dev.nulli0n.vbot.backend.protocol.BackendStatus;
import dev.nulli0n.vbot.backend.protocol.ManagedBoolean;
import dev.nulli0n.vbot.backend.protocol.RespawnPoint;
import org.bukkit.GameMode;
import org.bukkit.entity.Player;
import org.junit.jupiter.api.Test;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PaperPolicyServiceTest {
    @Test
    void extendedPolicyIsAppliedVerifiedAndReported() throws Exception {
        FakePlayer fake = new FakePlayer();
        fake.affectsSpawning = true;
        fake.pickupItems = true;
        BackendPolicy requested = new BackendPolicy(BackendInvulnerability.ENABLED,
            BackendGameMode.ADVENTURE, RespawnPoint.unchanged(), ManagedBoolean.ENABLED,
            ManagedBoolean.DISABLED, ManagedBoolean.DISABLED, ManagedBoolean.ENABLED);
        PaperPolicyService service = new PaperPolicyService();

        BackendPolicy cached = service.apply(fake.player(), requested);
        ActualState actual = service.actualState(fake.player());

        assertEquals(requested, cached);
        assertTrue(actual.extendedPresent());
        assertTrue(actual.invulnerable());
        assertEquals(BackendGameMode.ADVENTURE, actual.gameMode());
        assertTrue(actual.sleepingIgnored());
        assertFalse(actual.affectsSpawning());
        assertFalse(actual.pickupItems());
        assertTrue(actual.collidable());
    }

    @Test
    void recoveryRestoresBoundedVitalsAndIsIdempotent() throws Exception {
        FakePlayer fake = new FakePlayer();
        fake.maximumHealth = 40.0D;
        fake.health = 3.0D;
        fake.food = 4;
        fake.saturation = 1.0F;
        fake.fireTicks = 120;
        fake.fallDistance = 8.5F;
        PaperPolicyService service = new PaperPolicyService();

        service.recover(fake.player());
        service.recover(fake.player());

        assertEquals(40.0D, fake.health);
        assertEquals(20, fake.food);
        assertEquals(20.0F, fake.saturation);
        assertEquals(0, fake.fireTicks);
        assertEquals(0.0F, fake.fallDistance);
    }

    @Test
    void recoveryRejectsNonFiniteNonPositiveAndExcessiveMaximumHealth() {
        PaperPolicyService service = new PaperPolicyService();
        for (double maximum : new double[] {
            Double.NaN, Double.POSITIVE_INFINITY, 0.0D, -1.0D, 1024.0001D
        }) {
            FakePlayer fake = new FakePlayer();
            fake.maximumHealth = maximum;

            PolicyApplyException exception = assertThrows(PolicyApplyException.class,
                () -> service.recover(fake.player()));

            assertEquals(BackendStatus.APPLY_FAILED, exception.status());
        }
    }

    private static final class FakePlayer implements InvocationHandler {
        private boolean invulnerable;
        private GameMode gameMode = GameMode.SURVIVAL;
        private boolean sleepingIgnored;
        private boolean affectsSpawning;
        private boolean pickupItems;
        private boolean collidable;
        private double maximumHealth = 20.0D;
        private double health = 20.0D;
        private int food = 20;
        private float saturation = 5.0F;
        private int fireTicks;
        private float fallDistance;

        private Player player() {
            return (Player) Proxy.newProxyInstance(Player.class.getClassLoader(),
                new Class<?>[] {Player.class}, this);
        }

        @Override
        public Object invoke(Object proxy, Method method, Object[] arguments) {
            String name = method.getName();
            if (name.equals("setInvulnerable")) {
                invulnerable = (Boolean) arguments[0];
                return null;
            }
            if (name.equals("isInvulnerable")) {
                return invulnerable;
            }
            if (name.equals("setGameMode")) {
                gameMode = (GameMode) arguments[0];
                return null;
            }
            if (name.equals("getGameMode")) {
                return gameMode;
            }
            if (name.equals("setSleepingIgnored")) {
                sleepingIgnored = (Boolean) arguments[0];
                return null;
            }
            if (name.equals("isSleepingIgnored")) {
                return sleepingIgnored;
            }
            if (name.equals("setAffectsSpawning")) {
                affectsSpawning = (Boolean) arguments[0];
                return null;
            }
            if (name.equals("getAffectsSpawning")) {
                return affectsSpawning;
            }
            if (name.equals("setCanPickupItems")) {
                pickupItems = (Boolean) arguments[0];
                return null;
            }
            if (name.equals("getCanPickupItems")) {
                return pickupItems;
            }
            if (name.equals("setCollidable")) {
                collidable = (Boolean) arguments[0];
                return null;
            }
            if (name.equals("isCollidable")) {
                return collidable;
            }
            if (name.equals("getMaxHealth")) {
                return maximumHealth;
            }
            if (name.equals("setHealth")) {
                health = (Double) arguments[0];
                return null;
            }
            if (name.equals("getHealth")) {
                return health;
            }
            if (name.equals("setFoodLevel")) {
                food = (Integer) arguments[0];
                return null;
            }
            if (name.equals("getFoodLevel")) {
                return food;
            }
            if (name.equals("setSaturation")) {
                saturation = (Float) arguments[0];
                return null;
            }
            if (name.equals("getSaturation")) {
                return saturation;
            }
            if (name.equals("setFireTicks")) {
                fireTicks = (Integer) arguments[0];
                return null;
            }
            if (name.equals("getFireTicks")) {
                return fireTicks;
            }
            if (name.equals("setFallDistance")) {
                fallDistance = (Float) arguments[0];
                return null;
            }
            if (name.equals("getFallDistance")) {
                return fallDistance;
            }
            if (name.equals("toString")) {
                return "FakePlayer";
            }
            if (name.equals("hashCode")) {
                return System.identityHashCode(proxy);
            }
            if (name.equals("equals")) {
                return proxy == arguments[0];
            }
            return defaultValue(method.getReturnType());
        }

        private Object defaultValue(Class<?> type) {
            if (!type.isPrimitive()) {
                return null;
            }
            if (type == boolean.class) {
                return false;
            }
            if (type == char.class) {
                return '\0';
            }
            if (type == byte.class) {
                return (byte) 0;
            }
            if (type == short.class) {
                return (short) 0;
            }
            if (type == int.class) {
                return 0;
            }
            if (type == long.class) {
                return 0L;
            }
            if (type == float.class) {
                return 0.0F;
            }
            return 0.0D;
        }
    }
}
