package art.arcane.wormholes.network.replication.capture;

import art.arcane.wormholes.config.toml.NetworkConfig;
import art.arcane.wormholes.network.replication.BlockChangeFeed;
import art.arcane.wormholes.network.replication.ChunkReplicationManager;

import org.bukkit.event.HandlerList;
import org.bukkit.event.Listener;
import org.bukkit.plugin.Plugin;

import java.util.logging.Logger;

public final class CaptureRuntime {
    private final Plugin plugin;
    private final Logger logger;
    private final ChunkReplicationManager replication;
    private final RegionalDiffAccumulator accumulator;
    private final LightDiffCapture lightDiffCapture;
    private final BlockEntityCapture blockEntityCapture;
    private final BlockChangeCapture blockChangeCapture;
    private final CaptureRegionScheduler regionScheduler;
    private final ChunkSnapshotComparator snapshotComparator;
    private volatile CaptureSettings settings;
    private volatile boolean started = false;

    public CaptureRuntime(Plugin plugin, Logger logger, ChunkReplicationManager replication, BlockChangeFeed feed, CaptureSettings initialSettings) {
        this.plugin = plugin;
        this.logger = logger;
        this.replication = replication;
        this.settings = initialSettings == null ? CaptureSettings.defaults() : initialSettings;
        this.accumulator = new RegionalDiffAccumulator(replication, feed, this.settings);
        this.lightDiffCapture = new LightDiffCapture(accumulator);
        this.blockEntityCapture = new BlockEntityCapture(accumulator, logger);
        this.blockChangeCapture = new BlockChangeCapture(accumulator, blockEntityCapture);
        this.regionScheduler = new CaptureRegionScheduler(plugin, accumulator, lightDiffCapture);
        this.snapshotComparator = new ChunkSnapshotComparator(plugin, replication, accumulator, this.settings, logger);
        replication.setEvictionListener((worldId, chunkKey) -> {
            accumulator.resetChunk(worldId, chunkKey);
            snapshotComparator.evict(worldId, chunkKey);
        });
    }

    public void start() {
        if (started) {
            return;
        }
        plugin.getServer().getPluginManager().registerEvents(blockChangeCapture, plugin);
        plugin.getServer().getPluginManager().registerEvents(blockEntityCapture, plugin);
        regionScheduler.start();
        snapshotComparator.start();
        started = true;
        if (logger != null) {
            logger.info("Replication capture started (folia=" + regionScheduler.isFolia() + ", lights=" + settings.lightCaptureEnabled() + ", blockEntities=" + settings.blockEntityCaptureEnabled() + ", snapshotIntervalTicks=" + settings.snapshotIntervalTicks() + ", maxQueuedDiffsPerChunk=" + settings.maxQueuedDiffsPerChunk() + ")");
        }
    }

    public void stop() {
        if (!started) {
            return;
        }
        started = false;
        try {
            HandlerList.unregisterAll(blockChangeCapture);
        } catch (Throwable ignored) {
        }
        try {
            HandlerList.unregisterAll(blockEntityCapture);
        } catch (Throwable ignored) {
        }
        try {
            snapshotComparator.stop();
        } catch (Throwable ignored) {
        }
        try {
            regionScheduler.stop();
        } catch (Throwable ignored) {
        }
    }

    public void applySettings(NetworkConfig nextConfig) {
        CaptureSettings next = CaptureSettings.from(nextConfig);
        this.settings = next;
        accumulator.applySettings(next);
        snapshotComparator.applySettings(next);
    }

    public boolean isStarted() {
        return started;
    }

    public RegionalDiffAccumulator accumulator() {
        return accumulator;
    }

    public LightDiffCapture lightDiffCapture() {
        return lightDiffCapture;
    }

    public ChunkSnapshotComparator snapshotComparator() {
        return snapshotComparator;
    }

    public CaptureRegionScheduler regionScheduler() {
        return regionScheduler;
    }

    public Listener blockChangeListener() {
        return blockChangeCapture;
    }

    public Listener blockEntityListener() {
        return blockEntityCapture;
    }

    public CaptureSettings settings() {
        return settings;
    }

    public CaptureStats statsSnapshot() {
        return new CaptureStats(accumulator.stats(), snapshotComparator.stats(), lightDiffCapture.sectionsSampled(), lightDiffCapture.diffsEmitted());
    }

    public record CaptureStats(RegionalDiffAccumulator.Stats accumulator, ChunkSnapshotComparator.Stats snapshot, long lightSectionsSampled, long lightDiffsEmitted) {
    }
}
