package art.arcane.wormholes.portal;

import art.arcane.wormholes.chunk.BukkitChunkLeaseProvider;
import art.arcane.wormholes.chunk.ChunkLease;
import art.arcane.wormholes.chunk.ChunkLeasePlatform;
import art.arcane.wormholes.chunk.ChunkLeaseRegistry;
import org.bukkit.World;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Proxy;
import java.util.ArrayDeque;
import java.util.Queue;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.fail;

class ArrivalWarmerChunkLeaseTest {
    private static final UUID WORLD_ID = UUID.fromString("30000000-0000-0000-0000-000000000001");
    private static final World WORLD = world();

    @AfterEach
    void tearDown() {
        BukkitChunkLeaseProvider.shutdown();
    }

    @Test
    void shutdownReleasesArrivalLeaseExactlyOnce() {
        ManualPlatform platform = new ManualPlatform();
        BukkitChunkLeaseProvider.install(new ChunkLeaseRegistry<>(
            platform,
            new ChunkLeaseRegistry.Options(0L, 0L, 3)
        ));
        ArrivalWarmer warmer = new ArrivalWarmer();
        warmer.warmAround(WORLD, 64, -112, 0, 10_000L);

        warmer.shutdown();
        warmer.shutdown();
        platform.runAllScheduled();

        assertEquals(1, platform.addCalls);
        assertEquals(1, platform.removeCalls);
    }

    @Test
    void arrivalExpiryDoesNotRemoveTicketRetainedByViewAndRtp() throws InterruptedException {
        ManualPlatform platform = new ManualPlatform();
        ChunkLeaseRegistry<World> registry = new ChunkLeaseRegistry<>(
            platform,
            new ChunkLeaseRegistry.Options(0L, 0L, 3)
        );
        BukkitChunkLeaseProvider.install(registry);
        ChunkLease view = registry.retain(WORLD, WORLD_ID, 4, -7);
        ChunkLease rtp = registry.retain(WORLD, WORLD_ID, 4, -7);
        ArrivalWarmer warmer = new ArrivalWarmer();
        warmer.warmAround(WORLD, 64, -112, 0, 1L);
        awaitAfter(System.currentTimeMillis() + 2L);

        warmer.sweep();
        view.close();

        assertEquals(1, platform.addCalls);
        assertEquals(0, platform.removeCalls);

        rtp.close();
        platform.runAllScheduled();
        assertEquals(1, platform.removeCalls);
    }

    private static void awaitAfter(long targetMillis) throws InterruptedException {
        long timeoutNanos = System.nanoTime() + TimeUnit.SECONDS.toNanos(1L);
        while (System.currentTimeMillis() <= targetMillis) {
            if (System.nanoTime() >= timeoutNanos) {
                fail("Timed out waiting for arrival lease expiry");
            }
            TimeUnit.MILLISECONDS.sleep(10L);
        }
    }

    private static World world() {
        return (World) Proxy.newProxyInstance(
            World.class.getClassLoader(),
            new Class<?>[]{World.class},
            (proxy, method, arguments) -> switch (method.getName()) {
                case "getUID" -> WORLD_ID;
                case "hashCode" -> System.identityHashCode(proxy);
                case "equals" -> proxy == arguments[0];
                case "toString" -> "ArrivalWarmerTestWorld";
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

        private void runAllScheduled() {
            while (!scheduled.isEmpty()) {
                scheduled.remove().run();
            }
        }
    }
}
