package art.arcane.wormholes.network;

import org.bukkit.NamespacedKey;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.persistence.PersistentDataContainer;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Proxy;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

public final class TraversalServiceTransitStampTest {
    @Test
    public void encodeDecodeRoundtripCoversAllFlagCombinations() {
        for (int mask = 0; mask < 8; mask++) {
            boolean invulnerable = (mask & 1) != 0;
            boolean silent = (mask & 2) != 0;
            boolean gravity = (mask & 4) != 0;
            byte stamp = TraversalService.encodeTransitStamp(invulnerable, silent, gravity);
            assertEquals(invulnerable, TraversalService.stampInvulnerable(stamp));
            assertEquals(silent, TraversalService.stampSilent(stamp));
            assertEquals(gravity, TraversalService.stampGravity(stamp));
        }
    }

    @Test
    public void pendingSourceRemovalRemovesEntityOnceOnLoad() {
        TraversalService service = new TraversalService(null);
        FakeEntityState state = new FakeEntityState(UUID.randomUUID());
        Entity entity = fakeEntity(state);
        service.queueSourceRemoval(state.id);

        service.reconcileLoadedEntity(entity);
        assertTrue(state.removed);

        state.removed = false;
        service.reconcileLoadedEntity(entity);
        assertFalse(state.removed);
    }

    @Test
    public void strandedStampRestoresFlagsAndClearsStamp() {
        TraversalService service = new TraversalService(null);
        FakeEntityState state = new FakeEntityState(UUID.randomUUID());
        state.pdc.put(TraversalService.TRANSIT_STAMP_KEY, Byte.valueOf(TraversalService.encodeTransitStamp(false, true, true)));
        Entity entity = fakeEntity(state);

        service.reconcileLoadedEntity(entity);

        assertFalse(state.removed);
        assertEquals(Boolean.FALSE, state.invulnerable);
        assertEquals(Boolean.TRUE, state.silent);
        assertEquals(Boolean.TRUE, state.gravity);
        assertNull(state.pdc.get(TraversalService.TRANSIT_STAMP_KEY));
    }

    @Test
    public void unstampedEntityIsLeftUntouched() {
        TraversalService service = new TraversalService(null);
        FakeEntityState state = new FakeEntityState(UUID.randomUUID());
        Entity entity = fakeEntity(state);

        service.reconcileLoadedEntity(entity);

        assertFalse(state.removed);
        assertNull(state.invulnerable);
        assertNull(state.silent);
        assertNull(state.gravity);
    }

    @Test
    public void playerEntitiesAreIgnored() {
        TraversalService service = new TraversalService(null);
        FakeEntityState state = new FakeEntityState(UUID.randomUUID());
        Player player = fakePlayer(state);
        service.queueSourceRemoval(state.id);

        service.reconcileLoadedEntity(player);

        assertFalse(state.removed);
    }

    private static final class FakeEntityState {
        private final UUID id;
        private final Map<NamespacedKey, Byte> pdc = new HashMap<>();
        private boolean removed;
        private Boolean invulnerable;
        private Boolean silent;
        private Boolean gravity;

        private FakeEntityState(UUID id) {
            this.id = id;
        }
    }

    private static Entity fakeEntity(FakeEntityState state) {
        return (Entity) Proxy.newProxyInstance(
            TraversalServiceTransitStampTest.class.getClassLoader(),
            new Class<?>[]{Entity.class},
            (proxy, method, arguments) -> dispatch(proxy, method.getName(), method.getReturnType(), arguments, state)
        );
    }

    private static Player fakePlayer(FakeEntityState state) {
        return (Player) Proxy.newProxyInstance(
            TraversalServiceTransitStampTest.class.getClassLoader(),
            new Class<?>[]{Player.class},
            (proxy, method, arguments) -> dispatch(proxy, method.getName(), method.getReturnType(), arguments, state)
        );
    }

    private static Object dispatch(Object proxy, String name, Class<?> returnType, Object[] arguments, FakeEntityState state) {
        return switch (name) {
            case "getUniqueId" -> state.id;
            case "remove" -> {
                state.removed = true;
                yield null;
            }
            case "isValid" -> !state.removed;
            case "getPersistentDataContainer" -> container(state);
            case "setInvulnerable" -> {
                state.invulnerable = (Boolean) arguments[0];
                yield null;
            }
            case "setSilent" -> {
                state.silent = (Boolean) arguments[0];
                yield null;
            }
            case "setGravity" -> {
                state.gravity = (Boolean) arguments[0];
                yield null;
            }
            case "hashCode" -> System.identityHashCode(proxy);
            case "equals" -> proxy == arguments[0];
            case "toString" -> "FakeEntity[" + state.id + "]";
            default -> defaultValue(returnType);
        };
    }

    private static PersistentDataContainer container(FakeEntityState state) {
        return (PersistentDataContainer) Proxy.newProxyInstance(
            TraversalServiceTransitStampTest.class.getClassLoader(),
            new Class<?>[]{PersistentDataContainer.class},
            (proxy, method, arguments) -> switch (method.getName()) {
                case "get" -> state.pdc.get((NamespacedKey) arguments[0]);
                case "remove" -> {
                    state.pdc.remove((NamespacedKey) arguments[0]);
                    yield null;
                }
                case "has" -> state.pdc.containsKey((NamespacedKey) arguments[0]);
                case "hashCode" -> System.identityHashCode(proxy);
                case "equals" -> proxy == arguments[0];
                case "toString" -> "FakeContainer[" + state.id + "]";
                default -> defaultValue(method.getReturnType());
            }
        );
    }

    private static Object defaultValue(Class<?> type) {
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
