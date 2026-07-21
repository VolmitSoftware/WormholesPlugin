package art.arcane.wormholes.chunk;

import org.bukkit.Chunk;
import org.bukkit.World;
import org.bukkit.plugin.Plugin;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Proxy;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.logging.Handler;
import java.util.logging.LogRecord;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class BukkitChunkLeasePlatformTest {
    private static final Plugin PLUGIN = proxy(Plugin.class);
    private static final Chunk CHUNK = chunk();
    private static final World WORLD = world(CHUNK);

    @Test
    void addCompletesExceptionallyWhenRegionSchedulerRejects() {
        ManualOperations operations = new ManualOperations();
        operations.regionAccepted = false;
        BukkitChunkLeasePlatform platform = new BukkitChunkLeasePlatform(PLUGIN, operations);

        CompletionException failure = assertThrows(
            CompletionException.class,
            () -> platform.add(WORLD, 4, -7).toCompletableFuture().join()
        );

        assertEquals("Owning region rejected chunk ticket add", failure.getCause().getMessage());
    }

    @Test
    void addAppliesPluginTicketInsideAcceptedRegionTask() {
        ManualOperations operations = new ManualOperations();
        BukkitChunkLeasePlatform platform = new BukkitChunkLeasePlatform(PLUGIN, operations);

        assertTrue(platform.add(WORLD, 4, -7).toCompletableFuture().join());
        assertEquals(1, operations.regionRuns);
    }

    @Test
    void removeSkipsChunkLookupWhenWorldAlreadyUnloadedChunk() {
        ManualOperations operations = new ManualOperations();
        World unloadedWorld = world(null);
        BukkitChunkLeasePlatform platform = new BukkitChunkLeasePlatform(PLUGIN, operations);

        assertTrue(platform.remove(unloadedWorld, 4, -7).toCompletableFuture().join());
        assertEquals(1, operations.regionRuns);
    }

    @Test
    void removeCompletesExceptionallyWhenRegionSchedulerRejects() {
        ManualOperations operations = new ManualOperations();
        operations.regionAccepted = false;
        BukkitChunkLeasePlatform platform = new BukkitChunkLeasePlatform(PLUGIN, operations);

        CompletionException failure = assertThrows(
            CompletionException.class,
            () -> platform.remove(WORLD, 4, -7).toCompletableFuture().join()
        );

        assertEquals("Owning region rejected chunk ticket removal", failure.getCause().getMessage());
    }

    @Test
    void delayedScheduleRoundsMillisecondsUpToTicksAndPreservesRejection() {
        ManualOperations operations = new ManualOperations();
        operations.asyncAccepted = false;
        AtomicInteger runs = new AtomicInteger();
        BukkitChunkLeasePlatform platform = new BukkitChunkLeasePlatform(PLUGIN, operations);

        boolean accepted = platform.schedule(runs::incrementAndGet, 51L);

        assertFalse(accepted);
        assertEquals(0, runs.get());
        assertEquals(2L, operations.lastDelayTicks);
    }

    @Test
    void schedulerFailureIsReportedAndReturnedAsRejection() {
        RecordingHandler handler = new RecordingHandler();
        Logger logger = Logger.getLogger("BukkitChunkLeasePlatformScheduleTest");
        logger.setUseParentHandlers(false);
        logger.addHandler(handler);
        ManualOperations operations = new ManualOperations();
        IllegalStateException failure = new IllegalStateException("scheduler unavailable");
        operations.asyncFailure = failure;
        BukkitChunkLeasePlatform platform = new BukkitChunkLeasePlatform(plugin(logger), operations);

        boolean accepted = platform.schedule(() -> {
        }, 1L);

        assertFalse(accepted);
        assertSame(failure, handler.lastThrown);
        logger.removeHandler(handler);
    }

    @Test
    void reportFailureLogsOriginalThrowableWithStacktrace() {
        RecordingHandler handler = new RecordingHandler();
        Logger logger = Logger.getLogger("BukkitChunkLeasePlatformTest");
        logger.setUseParentHandlers(false);
        logger.addHandler(handler);
        Plugin plugin = plugin(logger);
        BukkitChunkLeasePlatform platform = new BukkitChunkLeasePlatform(plugin, new ManualOperations());
        IllegalStateException failure = new IllegalStateException("physical ticket failure");

        platform.reportFailure(failure);

        assertSame(failure, handler.lastThrown);
        logger.removeHandler(handler);
    }

    private static Plugin plugin(Logger logger) {
        return (Plugin) Proxy.newProxyInstance(
            Plugin.class.getClassLoader(),
            new Class<?>[]{Plugin.class},
            (instance, method, arguments) -> switch (method.getName()) {
                case "getLogger" -> logger;
                case "hashCode" -> System.identityHashCode(instance);
                case "equals" -> instance == arguments[0];
                case "toString" -> "PluginProxy";
                default -> throw new UnsupportedOperationException(method.getName());
            }
        );
    }

    private static Chunk chunk() {
        return (Chunk) Proxy.newProxyInstance(
            Chunk.class.getClassLoader(),
            new Class<?>[]{Chunk.class},
            (instance, method, arguments) -> switch (method.getName()) {
                case "addPluginChunkTicket", "removePluginChunkTicket" -> true;
                case "hashCode" -> System.identityHashCode(instance);
                case "equals" -> instance == arguments[0];
                case "toString" -> "ChunkProxy";
                default -> throw new UnsupportedOperationException(method.getName());
            }
        );
    }

    private static World world(Chunk chunk) {
        return (World) Proxy.newProxyInstance(
            World.class.getClassLoader(),
            new Class<?>[]{World.class},
            (instance, method, arguments) -> switch (method.getName()) {
                case "isChunkLoaded" -> chunk != null;
                case "getChunkAt" -> {
                    if (chunk == null) {
                        throw new AssertionError("Unloaded chunk must not be looked up");
                    }
                    yield chunk;
                }
                case "hashCode" -> System.identityHashCode(instance);
                case "equals" -> instance == arguments[0];
                case "toString" -> "WorldProxy";
                default -> throw new UnsupportedOperationException(method.getName());
            }
        );
    }

    @SuppressWarnings("unchecked")
    private static <T> T proxy(Class<T> type) {
        return (T) Proxy.newProxyInstance(
            type.getClassLoader(),
            new Class<?>[]{type},
            (instance, method, arguments) -> switch (method.getName()) {
                case "hashCode" -> System.identityHashCode(instance);
                case "equals" -> instance == arguments[0];
                case "toString" -> type.getSimpleName() + "Proxy";
                default -> throw new UnsupportedOperationException(method.getName());
            }
        );
    }

    private static final class RecordingHandler extends Handler {
        private Throwable lastThrown;

        @Override
        public void publish(LogRecord record) {
            lastThrown = record.getThrown();
        }

        @Override
        public void flush() {
        }

        @Override
        public void close() {
        }
    }

    private static final class ManualOperations implements BukkitChunkLeasePlatform.Operations {
        private boolean regionAccepted = true;
        private boolean asyncAccepted = true;
        private RuntimeException asyncFailure;
        private int regionRuns;
        private long lastDelayTicks;

        @Override
        public CompletionStage<Chunk> loadChunk(Plugin plugin, World world, int chunkX, int chunkZ) {
            return CompletableFuture.completedFuture(CHUNK);
        }

        @Override
        public boolean runRegion(Plugin plugin, World world, int chunkX, int chunkZ, Runnable command) {
            if (regionAccepted) {
                regionRuns++;
                command.run();
            }
            return regionAccepted;
        }

        @Override
        public boolean runAsync(Plugin plugin, Runnable command, long delayTicks) {
            lastDelayTicks = delayTicks;
            if (asyncFailure != null) {
                throw asyncFailure;
            }
            if (asyncAccepted) {
                command.run();
            }
            return asyncAccepted;
        }
    }
}
