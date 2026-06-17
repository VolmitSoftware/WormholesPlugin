package art.arcane.wormholes.network.replication.capture;

import art.arcane.volmlib.util.scheduling.FoliaScheduler;
import art.arcane.wormholes.network.replication.BlockChange;
import art.arcane.wormholes.network.replication.ChunkReplicationManager;
import art.arcane.wormholes.network.view.ViewSlice;

import org.bukkit.Bukkit;
import org.bukkit.Chunk;
import org.bukkit.ChunkSnapshot;
import org.bukkit.World;
import org.bukkit.block.data.BlockData;
import org.bukkit.plugin.Plugin;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.logging.Level;
import java.util.logging.Logger;

public final class ChunkSnapshotComparator {
    private static final int SURFACE_SCAN_MARGIN = 8;

    private final Plugin plugin;
    private final ChunkReplicationManager replication;
    private final RegionalDiffAccumulator accumulator;
    private final Logger logger;
    private volatile CaptureSettings settings;
    private final boolean folia;
    private final AtomicBoolean running = new AtomicBoolean(false);
    private int paperTaskId = -1;
    private final Map<UUID, Map<Long, ChunkSurfaceShadow>> worldShadows = new ConcurrentHashMap<>();
    private final Map<UUID, Map<Long, Long>> lastInhabitedTime = new ConcurrentHashMap<>();
    private final AtomicLong sweepsRun = new AtomicLong();
    private final AtomicLong chunksProbed = new AtomicLong();
    private final AtomicLong divergencesEmitted = new AtomicLong();

    public ChunkSnapshotComparator(Plugin plugin, ChunkReplicationManager replication, RegionalDiffAccumulator accumulator, CaptureSettings settings, Logger logger) {
        this.plugin = plugin;
        this.replication = replication;
        this.accumulator = accumulator;
        this.settings = settings == null ? CaptureSettings.defaults() : settings;
        this.logger = logger;
        this.folia = detectFolia();
    }

    public void applySettings(CaptureSettings next) {
        this.settings = next == null ? CaptureSettings.defaults() : next;
    }

    public void start() {
        if (!running.compareAndSet(false, true)) {
            return;
        }
        scheduleNext();
    }

    public void stop() {
        if (!running.compareAndSet(true, false)) {
            return;
        }
        if (!folia && paperTaskId != -1) {
            try {
                Bukkit.getScheduler().cancelTask(paperTaskId);
            } catch (Throwable ignored) {
            }
            paperTaskId = -1;
        }
    }

    public boolean isRunning() {
        return running.get();
    }

    public Stats stats() {
        return new Stats(sweepsRun.get(), chunksProbed.get(), divergencesEmitted.get());
    }

    public record Stats(long sweepsRun, long chunksProbed, long divergencesEmitted) {
    }

    private void scheduleNext() {
        if (!running.get()) {
            return;
        }
        long delay = Math.max(20L, settings.snapshotIntervalTicks());
        if (folia) {
            FoliaScheduler.runGlobal(plugin, this::runSweep, delay);
            return;
        }
        paperTaskId = Bukkit.getScheduler().runTaskLater(plugin, this::runSweep, delay).getTaskId();
    }

    private void runSweep() {
        if (!running.get()) {
            return;
        }
        try {
            sweepsRun.incrementAndGet();
            for (World world : Bukkit.getWorlds()) {
                for (Chunk chunk : world.getLoadedChunks()) {
                    long chunkKey = ViewSlice.columnKey(chunk.getX(), chunk.getZ());
                    if (!replication.hasSubscribers(world, chunkKey)) {
                        continue;
                    }
                    if (!hasInhabitedTimeChanged(world, chunkKey, chunk.getInhabitedTime())) {
                        continue;
                    }
                    if (folia) {
                        FoliaScheduler.runRegion(plugin, world, chunk.getX(), chunk.getZ(), () -> probeChunk(world, chunkKey, chunk.getX(), chunk.getZ()));
                    } else {
                        probeChunk(world, chunkKey, chunk.getX(), chunk.getZ());
                    }
                }
            }
        } catch (Throwable ex) {
            if (logger != null) {
                logger.log(Level.WARNING, "Snapshot-diff sweep failure", ex);
            }
        } finally {
            scheduleNext();
        }
    }

    private boolean hasInhabitedTimeChanged(World world, long chunkKey, long current) {
        Map<Long, Long> worldMap = lastInhabitedTime.computeIfAbsent(world.getUID(), ignored -> new ConcurrentHashMap<>());
        Long previous = worldMap.put(chunkKey, current);
        if (previous == null) {
            return true;
        }
        return previous.longValue() != current;
    }

    private void probeChunk(World world, long chunkKey, int chunkX, int chunkZ) {
        chunksProbed.incrementAndGet();
        Chunk chunk;
        try {
            chunk = world.getChunkAt(chunkX, chunkZ);
        } catch (Throwable ignored) {
            return;
        }
        ChunkSnapshot snapshot;
        try {
            snapshot = chunk.getChunkSnapshot(true, false, false, false);
        } catch (Throwable ignored) {
            return;
        }
        int minHeight = world.getMinHeight();
        int maxHeight = world.getMaxHeight();
        FoliaScheduler.runAsync(plugin, () -> compareSnapshot(world, chunkKey, chunkX, chunkZ, snapshot, minHeight, maxHeight));
    }

    private void compareSnapshot(World world, long chunkKey, int chunkX, int chunkZ, ChunkSnapshot snapshot, int minHeight, int maxHeight) {
        ChunkSurfaceShadow shadow = shadowFor(world, chunkKey);
        boolean diverged = false;
        int ceiling = maxHeight - 1;
        for (int x = 0; x < 16; x++) {
            for (int z = 0; z < 16; z++) {
                int top = Math.min(ceiling, snapshot.getHighestBlockYAt(x, z) + SURFACE_SCAN_MARGIN);
                for (int y = minHeight; y <= top; y++) {
                    BlockData data;
                    try {
                        data = snapshot.getBlockData(x, y, z);
                    } catch (Throwable ignored) {
                        continue;
                    }
                    String stateString = data.getAsString();
                    int packed = BlockChange.pack(x, y, z);
                    String previous = shadow.getAndPut(packed, stateString);
                    if (previous == null || previous.equals(stateString)) {
                        continue;
                    }
                    diverged = true;
                    accumulator.recordBlockChange(world, (chunkX << 4) | x, y, (chunkZ << 4) | z, data, BlockChange.FLAG_NONE);
                }
            }
        }
        if (diverged) {
            divergencesEmitted.incrementAndGet();
        }
    }

    private ChunkSurfaceShadow shadowFor(World world, long chunkKey) {
        Map<Long, ChunkSurfaceShadow> worldMap = worldShadows.computeIfAbsent(world.getUID(), ignored -> new ConcurrentHashMap<>());
        return worldMap.computeIfAbsent(chunkKey, ignored -> new ChunkSurfaceShadow());
    }

    private static boolean detectFolia() {
        try {
            Class.forName("io.papermc.paper.threadedregions.RegionizedServer");
            return true;
        } catch (ClassNotFoundException ex) {
            return false;
        }
    }

    private static final class ChunkSurfaceShadow {
        private final Map<Integer, String> stateByPackedXyz = new HashMap<>(64);

        synchronized String getAndPut(int packedXyz, String stateString) {
            String previous = stateByPackedXyz.put(packedXyz, stateString);
            return previous;
        }
    }
}
