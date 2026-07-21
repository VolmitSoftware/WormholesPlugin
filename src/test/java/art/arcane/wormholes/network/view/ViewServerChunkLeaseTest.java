package art.arcane.wormholes.network.view;

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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ViewServerChunkLeaseTest {
    private static final UUID WORLD_ID = UUID.fromString("40000000-0000-0000-0000-000000000001");
    private static final World WORLD = world();

    @AfterEach
    void tearDown() {
        BukkitChunkLeaseProvider.shutdown();
    }

    @Test
    void viewCloseDoesNotRemoveTicketRetainedByArrival() {
        ManualPlatform platform = new ManualPlatform();
        ChunkLeaseRegistry<World> registry = new ChunkLeaseRegistry<>(
            platform,
            new ChunkLeaseRegistry.Options(0L, 0L, 3)
        );
        BukkitChunkLeaseProvider.install(registry);
        ChunkLease arrival = registry.retain(WORLD, WORLD_ID, 4, -7);
        ViewBox box = new ViewBox(64, 0, -112, 64, 15, -112);
        ViewServer.TicketLease view = new ViewServer.TicketLease(UUID.randomUUID(), WORLD, box);

        view.close();

        assertEquals(1, platform.addCalls);
        assertEquals(0, platform.removeCalls);
        assertTrue(arrival.ready().join());

        arrival.close();
        platform.runAllScheduled();
        assertEquals(1, platform.removeCalls);
    }

    private static World world() {
        return (World) Proxy.newProxyInstance(
            World.class.getClassLoader(),
            new Class<?>[]{World.class},
            (proxy, method, arguments) -> switch (method.getName()) {
                case "getUID" -> WORLD_ID;
                case "hashCode" -> System.identityHashCode(proxy);
                case "equals" -> proxy == arguments[0];
                case "toString" -> "ViewServerTestWorld";
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
