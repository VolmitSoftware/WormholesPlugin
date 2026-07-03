package art.arcane.wormholes.network.replication.capture;

import art.arcane.volmlib.util.scheduling.FoliaScheduler;
import art.arcane.wormholes.network.replication.BlockChange;
import art.arcane.wormholes.network.replication.ChunkReplicationManager;

import org.bukkit.Bukkit;
import org.bukkit.Chunk;
import org.bukkit.ChunkSnapshot;
import org.bukkit.World;
import org.bukkit.block.data.BlockData;
import org.bukkit.plugin.Plugin;

import java.util.ArrayDeque;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.logging.Level;
import java.util.logging.Logger;

public final class ChunkSnapshotComparator {
    private static final int SURFACE_SCAN_MARGIN = 8;
    private static final int MAX_PROBES_PER_TICK = 4;

    private record PendingProbe(World world, long chunkKey, int chunkX, int chunkZ) {
    }

    private final Plugin plugin;
    private final ChunkReplicationManager replication;
    private final RegionalDiffAccumulator accumulator;
    private final Logger logger;
    private volatile CaptureSettings settings;
    private final boolean folia;
    private final AtomicBoolean running = new AtomicBoolean(false);
    private int paperTaskId = -1;
    private final ArrayDeque<PendingProbe> paperProbeQueue = new ArrayDeque<>();
    private final Map<UUID, Map<Long, ChunkSnapshot>> worldShadows = new ConcurrentHashMap<>();
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

    public void evict(UUID worldId, long chunkKey) {
        Map<Long, ChunkSnapshot> shadowMap = worldShadows.get(worldId);
        if (shadowMap != null) {
            shadowMap.remove(chunkKey);
        }
        Map<Long, Long> inhabitedMap = lastInhabitedTime.get(worldId);
        if (inhabitedMap != null) {
            inhabitedMap.remove(chunkKey);
        }
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
                List<Long> keys = replication.subscribedChunkKeys(world.getUID());
                for (Long keyBoxed : keys) {
                    long chunkKey = keyBoxed.longValue();
                    int chunkX = (int) (chunkKey >> 32);
                    int chunkZ = (int) chunkKey;
                    if (!world.isChunkLoaded(chunkX, chunkZ)) {
                        continue;
                    }
                    if (folia) {
                        FoliaScheduler.runRegion(plugin, world, chunkX, chunkZ, () -> probeChunk(world, chunkKey, chunkX, chunkZ));
                    } else {
                        paperProbeQueue.add(new PendingProbe(world, chunkKey, chunkX, chunkZ));
                    }
                }
            }
        } catch (Throwable ex) {
            if (logger != null) {
                logger.log(Level.WARNING, "Snapshot-diff sweep failure", ex);
            }
        } finally {
            if (folia) {
                scheduleNext();
            } else {
                drainPaperProbeQueue();
            }
        }
    }

    private void drainPaperProbeQueue() {
        if (!running.get()) {
            paperProbeQueue.clear();
            return;
        }
        int processed = 0;
        while (processed < MAX_PROBES_PER_TICK && !paperProbeQueue.isEmpty()) {
            PendingProbe probe = paperProbeQueue.poll();
            probeChunk(probe.world(), probe.chunkKey(), probe.chunkX(), probe.chunkZ());
            processed++;
        }
        if (paperProbeQueue.isEmpty()) {
            scheduleNext();
            return;
        }
        paperTaskId = Bukkit.getScheduler().runTaskLater(plugin, this::drainPaperProbeQueue, 1L).getTaskId();
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
        if (!world.isChunkLoaded(chunkX, chunkZ)) {
            return;
        }
        Chunk chunk;
        try {
            chunk = world.getChunkAt(chunkX, chunkZ);
        } catch (Throwable ignored) {
            return;
        }
        if (!hasInhabitedTimeChanged(world, chunkKey, chunk.getInhabitedTime())) {
            return;
        }
        chunksProbed.incrementAndGet();
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
        Map<Long, ChunkSnapshot> worldMap = worldShadows.computeIfAbsent(world.getUID(), ignored -> new ConcurrentHashMap<>());
        ChunkSnapshot previous = worldMap.put(chunkKey, snapshot);
        if (previous == null) {
            return;
        }
        boolean diverged = false;
        int ceiling = maxHeight - 1;
        for (int x = 0; x < 16; x++) {
            for (int z = 0; z < 16; z++) {
                int curTop = snapshot.getHighestBlockYAt(x, z);
                int prevTop = previous.getHighestBlockYAt(x, z);
                int top = Math.min(ceiling, Math.max(curTop, prevTop) + SURFACE_SCAN_MARGIN);
                for (int y = minHeight; y <= top; y++) {
                    BlockData current;
                    BlockData prior;
                    try {
                        current = snapshot.getBlockData(x, y, z);
                        prior = previous.getBlockData(x, y, z);
                    } catch (Throwable ignored) {
                        continue;
                    }
                    if (current == null || current.equals(prior)) {
                        continue;
                    }
                    diverged = true;
                    accumulator.recordBlockChange(world, (chunkX << 4) | x, y, (chunkZ << 4) | z, current, BlockChange.FLAG_NONE);
                }
            }
        }
        if (diverged) {
            divergencesEmitted.incrementAndGet();
        }
    }

    private static boolean detectFolia() {
        try {
            Class.forName("io.papermc.paper.threadedregions.RegionizedServer");
            return true;
        } catch (ClassNotFoundException ex) {
            return false;
        }
    }
}
