package art.arcane.wormholes.chunk;

import art.arcane.volmlib.util.scheduling.FoliaScheduler;
import art.arcane.wormholes.platform.WormholesPlatform;
import org.bukkit.Chunk;
import org.bukkit.World;
import org.bukkit.plugin.Plugin;

import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.logging.Level;

public final class BukkitChunkLeasePlatform implements ChunkLeasePlatform<World> {
    private static final long MILLIS_PER_TICK = 50L;

    private final Plugin plugin;
    private final Operations operations;

    public BukkitChunkLeasePlatform(Plugin plugin) {
        this(plugin, new BukkitOperations());
    }

    BukkitChunkLeasePlatform(Plugin plugin, Operations operations) {
        this.plugin = Objects.requireNonNull(plugin);
        this.operations = Objects.requireNonNull(operations);
    }

    @Override
    public CompletionStage<Boolean> add(World world, int chunkX, int chunkZ) {
        World activeWorld = Objects.requireNonNull(world);
        CompletableFuture<Boolean> result = new CompletableFuture<>();
        CompletionStage<Chunk> load;
        try {
            load = Objects.requireNonNull(operations.loadChunk(plugin, activeWorld, chunkX, chunkZ));
        } catch (RuntimeException error) {
            result.completeExceptionally(error);
            return result;
        }
        load.whenComplete((chunk, error) -> {
            if (error != null) {
                result.completeExceptionally(error);
                return;
            }
            if (chunk == null) {
                result.completeExceptionally(new IllegalStateException("Chunk load completed without a chunk"));
                return;
            }
            boolean scheduled;
            try {
                scheduled = operations.runRegion(plugin, activeWorld, chunkX, chunkZ, () -> addTicket(chunk, result));
            } catch (RuntimeException schedulingError) {
                result.completeExceptionally(schedulingError);
                return;
            }
            if (!scheduled) {
                result.completeExceptionally(new IllegalStateException("Owning region rejected chunk ticket add"));
            }
        });
        return result;
    }

    @Override
    public CompletionStage<Boolean> remove(World world, int chunkX, int chunkZ) {
        World activeWorld = Objects.requireNonNull(world);
        CompletableFuture<Boolean> result = new CompletableFuture<>();
        boolean scheduled;
        try {
            scheduled = operations.runRegion(plugin, activeWorld, chunkX, chunkZ,
                () -> removeTicket(activeWorld, chunkX, chunkZ, result));
        } catch (RuntimeException error) {
            result.completeExceptionally(error);
            return result;
        }
        if (!scheduled) {
            result.completeExceptionally(new IllegalStateException("Owning region rejected chunk ticket removal"));
        }
        return result;
    }

    @Override
    public boolean schedule(Runnable command, long delayMillis) {
        try {
            return operations.runAsync(plugin, Objects.requireNonNull(command), delayTicks(delayMillis));
        } catch (RuntimeException error) {
            reportFailure(error);
            return false;
        }
    }

    @Override
    public void reportFailure(Throwable error) {
        plugin.getLogger().log(Level.SEVERE, "Chunk lease operation failed", Objects.requireNonNull(error));
    }

    private void addTicket(Chunk chunk, CompletableFuture<Boolean> result) {
        try {
            result.complete(chunk.addPluginChunkTicket(plugin));
        } catch (RuntimeException error) {
            result.completeExceptionally(error);
        }
    }

    private void removeTicket(World world, int chunkX, int chunkZ, CompletableFuture<Boolean> result) {
        try {
            if (!world.isChunkLoaded(chunkX, chunkZ)) {
                result.complete(true);
                return;
            }
            result.complete(world.getChunkAt(chunkX, chunkZ).removePluginChunkTicket(plugin));
        } catch (RuntimeException error) {
            result.completeExceptionally(error);
        }
    }

    private long delayTicks(long delayMillis) {
        long normalized = Math.max(0L, delayMillis);
        long ticks = normalized / MILLIS_PER_TICK;
        return normalized % MILLIS_PER_TICK == 0L ? ticks : ticks + 1L;
    }

    interface Operations {
        CompletionStage<Chunk> loadChunk(Plugin plugin, World world, int chunkX, int chunkZ);

        boolean runRegion(Plugin plugin, World world, int chunkX, int chunkZ, Runnable command);

        boolean runAsync(Plugin plugin, Runnable command, long delayTicks);
    }

    private static final class BukkitOperations implements Operations {
        @Override
        public CompletionStage<Chunk> loadChunk(Plugin plugin, World world, int chunkX, int chunkZ) {
            return WormholesPlatform.loadChunk(plugin, world, chunkX, chunkZ);
        }

        @Override
        public boolean runRegion(Plugin plugin, World world, int chunkX, int chunkZ, Runnable command) {
            return FoliaScheduler.runRegion(plugin, world, chunkX, chunkZ, command);
        }

        @Override
        public boolean runAsync(Plugin plugin, Runnable command, long delayTicks) {
            return FoliaScheduler.runAsync(plugin, command, delayTicks);
        }
    }
}
