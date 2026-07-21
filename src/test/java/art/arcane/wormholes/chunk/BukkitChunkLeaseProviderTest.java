package art.arcane.wormholes.chunk;

import org.bukkit.World;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Proxy;
import java.util.ArrayDeque;
import java.util.Queue;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class BukkitChunkLeaseProviderTest {
    private static final UUID WORLD_ID = UUID.fromString("20000000-0000-0000-0000-000000000001");
    private static final World WORLD = world();

    @AfterEach
    void tearDown() {
        BukkitChunkLeaseProvider.shutdown();
    }

    @Test
    void arrivalExpiryDoesNotRemoveTicketRetainedByViewAndRtp() {
        ManualPlatform platform = new ManualPlatform();
        ChunkLeaseRegistry<World> registry = registry(platform);
        BukkitChunkLeaseProvider.install(registry);
        ChunkLease arrival = BukkitChunkLeaseProvider.registry().retain(WORLD, WORLD_ID, 4, -7);
        ChunkLease view = BukkitChunkLeaseProvider.registry().retain(WORLD, WORLD_ID, 4, -7);
        ChunkLease rtp = BukkitChunkLeaseProvider.registry().retain(WORLD, WORLD_ID, 4, -7);

        arrival.close();

        assertSame(registry, BukkitChunkLeaseProvider.registry());
        assertEquals(1, platform.addCalls);
        assertEquals(0, platform.removeCalls);
        assertTrue(view.ready().join());
        assertTrue(rtp.ready().join());
    }

    @Test
    void installingSameRegistryTwiceIsIdempotent() {
        ManualPlatform platform = new ManualPlatform();
        ChunkLeaseRegistry<World> registry = registry(platform);

        BukkitChunkLeaseProvider.install(registry);
        BukkitChunkLeaseProvider.install(registry);

        assertSame(registry, BukkitChunkLeaseProvider.registry());
    }

    @Test
    void installingDifferentRegistryWhileActiveIsRejected() {
        ChunkLeaseRegistry<World> first = registry(new ManualPlatform());
        ChunkLeaseRegistry<World> second = registry(new ManualPlatform());
        BukkitChunkLeaseProvider.install(first);

        assertThrows(IllegalStateException.class, () -> BukkitChunkLeaseProvider.install(second));
        assertSame(first, BukkitChunkLeaseProvider.registry());
    }

    @Test
    void registryCanBeReinstalledAfterShutdown() {
        ChunkLeaseRegistry<World> first = registry(new ManualPlatform());
        ChunkLeaseRegistry<World> second = registry(new ManualPlatform());
        BukkitChunkLeaseProvider.install(first);
        BukkitChunkLeaseProvider.shutdown();

        BukkitChunkLeaseProvider.install(second);

        assertSame(second, BukkitChunkLeaseProvider.registry());
    }

    @Test
    void worldUnloadRemovesOneSharedTicketAndMakesLaterCloseIdempotent() {
        ManualPlatform platform = new ManualPlatform();
        ChunkLeaseRegistry<World> registry = registry(platform);
        BukkitChunkLeaseProvider.install(registry);
        ChunkLease arrival = registry.retain(WORLD, WORLD_ID, 4, -7);
        ChunkLease view = registry.retain(WORLD, WORLD_ID, 4, -7);
        ChunkLease rtp = registry.retain(WORLD, WORLD_ID, 4, -7);

        BukkitChunkLeaseProvider.worldUnloaded(WORLD_ID);
        arrival.close();
        view.close();
        rtp.close();

        assertEquals(1, platform.addCalls);
        assertEquals(1, platform.removeCalls);
    }

    @Test
    void shutdownIsIdempotentAndClearsInstalledRegistry() {
        ManualPlatform platform = new ManualPlatform();
        ChunkLeaseRegistry<World> registry = registry(platform);
        BukkitChunkLeaseProvider.install(registry);
        registry.retain(WORLD, WORLD_ID, 4, -7);

        BukkitChunkLeaseProvider.shutdown();
        BukkitChunkLeaseProvider.shutdown();

        assertEquals(1, platform.removeCalls);
        assertThrows(IllegalStateException.class, BukkitChunkLeaseProvider::registry);
    }

    private static ChunkLeaseRegistry<World> registry(ManualPlatform platform) {
        return new ChunkLeaseRegistry<>(platform, new ChunkLeaseRegistry.Options(0L, 0L, 3));
    }

    private static World world() {
        return (World) Proxy.newProxyInstance(
            World.class.getClassLoader(),
            new Class<?>[]{World.class},
            (proxy, method, arguments) -> switch (method.getName()) {
                case "getUID" -> WORLD_ID;
                case "hashCode" -> System.identityHashCode(proxy);
                case "equals" -> proxy == arguments[0];
                case "toString" -> "ChunkLeaseTestWorld";
                default -> throw new UnsupportedOperationException(method.getName());
            }
        );
    }

    private static final class ManualPlatform implements ChunkLeasePlatform<World> {
        private final Queue<Runnable> scheduled = new ArrayDeque<>();
        private int addCalls;
        private int removeCalls;

        @Override
        public CompletionStage<Boolean> add(World world, int chunkX, int chunkZ) {
            addCalls++;
            return CompletableFuture.completedFuture(true);
        }

        @Override
        public CompletionStage<Boolean> remove(World world, int chunkX, int chunkZ) {
            removeCalls++;
            return CompletableFuture.completedFuture(true);
        }

        @Override
        public boolean schedule(Runnable command, long delayMillis) {
            return scheduled.offer(command);
        }

        @Override
        public void reportFailure(Throwable error) {
        }
    }
}
