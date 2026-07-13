package art.arcane.wormholes.platform;

import org.bukkit.NamespacedKey;
import org.bukkit.World;
import org.bukkit.entity.Entity;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Proxy;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

public final class WormholesPlatformTest {
    @Test
    public void directMinecraftVersionTakesPriority() {
        assertEquals("26.2", WormholesPlatform.selectMinecraftVersion(" 26.2 ", "26.1-R0.1-SNAPSHOT"));
    }

    @Test
    public void bukkitVersionSuppliesSpigotFallback() {
        assertEquals("26.2", WormholesPlatform.selectMinecraftVersion(null, "26.2-R0.1-SNAPSHOT"));
        assertEquals("26.2", WormholesPlatform.selectMinecraftVersion("", "26.2"));
    }

    @Test
    public void missingMinecraftVersionUsesStableUnknownValue() {
        assertEquals("unknown", WormholesPlatform.selectMinecraftVersion(null, null));
        assertEquals("unknown", WormholesPlatform.selectMinecraftVersion(" ", " "));
    }

    @Test
    public void pluginVersionNormalizesBlankAndWhitespace() {
        assertEquals("1.2.3", WormholesPlatform.selectPluginVersion(" 1.2.3 "));
        assertEquals("unknown", WormholesPlatform.selectPluginVersion(""));
        assertEquals("unknown", WormholesPlatform.selectPluginVersion(null));
    }

    @Test
    public void worldFallbackMatchesNamespacedKey() {
        NamespacedKey targetKey = new NamespacedKey("wormholes", "pockets");
        World other = world(new NamespacedKey("minecraft", "overworld"));
        World target = world(targetKey);

        assertSame(target, WormholesPlatform.findWorld(List.of(other, target), targetKey));
    }

    @Test
    public void worldFallbackReturnsNullForMissingInputsAndKeys() {
        NamespacedKey targetKey = new NamespacedKey("wormholes", "pockets");

        assertNull(WormholesPlatform.findWorld(null, targetKey));
        assertNull(WormholesPlatform.findWorld(List.of(world(NamespacedKey.minecraft("overworld"))), targetKey));
        assertNull(WormholesPlatform.findWorld(List.of(), null));
    }

    @Test
    public void entityPositionRejectsUndersizedOutput() {
        assertThrows(IllegalArgumentException.class, () -> WormholesPlatform.entityPosition(entity(), new double[4]));
    }

    private static World world(NamespacedKey key) {
        return (World) Proxy.newProxyInstance(
            WormholesPlatformTest.class.getClassLoader(),
            new Class<?>[]{World.class},
            (proxy, method, arguments) -> switch (method.getName()) {
                case "getKey" -> key;
                case "toString" -> "World[" + key + "]";
                case "hashCode" -> System.identityHashCode(proxy);
                case "equals" -> proxy == arguments[0];
                default -> defaultValue(method.getReturnType());
            }
        );
    }

    private static Entity entity() {
        return (Entity) Proxy.newProxyInstance(
            WormholesPlatformTest.class.getClassLoader(),
            new Class<?>[]{Entity.class},
            (proxy, method, arguments) -> defaultValue(method.getReturnType())
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
