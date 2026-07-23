package art.arcane.wormholes.survival.doors.dimension;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.reflect.Proxy;

import org.bukkit.NamespacedKey;
import org.bukkit.World;
import org.junit.jupiter.api.Test;

public final class PocketWorldServiceTest {
    @Test
    void pocketWorldKeyIsRecognized() {
        assertTrue(PocketWorldService.isPocketWorld(world(new NamespacedKey("wormholes", "pockets"))));
    }

    @Test
    void otherWorldsAreNotPocketWorlds() {
        assertFalse(PocketWorldService.isPocketWorld(world(NamespacedKey.minecraft("overworld"))));
        assertFalse(PocketWorldService.isPocketWorld(world(new NamespacedKey("wormholes", "elsewhere"))));
        assertFalse(PocketWorldService.isPocketWorld(null));
    }

    private static World world(NamespacedKey key) {
        return (World) Proxy.newProxyInstance(
                PocketWorldServiceTest.class.getClassLoader(),
                new Class<?>[] {World.class},
                (proxy, method, args) -> {
                    if (method.getName().equals("getKey")) {
                        return key;
                    }
                    throw new UnsupportedOperationException(method.getName());
                });
    }
}
