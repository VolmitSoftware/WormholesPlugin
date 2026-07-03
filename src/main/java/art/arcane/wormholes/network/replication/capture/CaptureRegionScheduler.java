package art.arcane.wormholes.network.replication.capture;

import art.arcane.volmlib.util.scheduling.FoliaScheduler;

import org.bukkit.Bukkit;
import org.bukkit.World;
import org.bukkit.plugin.Plugin;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;

public final class CaptureRegionScheduler {
    private final Plugin plugin;
    private final RegionalDiffAccumulator accumulator;
    private final LightDiffCapture lightDiffCapture;
    private final boolean folia;
    private final AtomicBoolean running = new AtomicBoolean(false);
    private int globalTaskId = -1;

    public CaptureRegionScheduler(Plugin plugin, RegionalDiffAccumulator accumulator, LightDiffCapture lightDiffCapture) {
        this.plugin = plugin;
        this.accumulator = accumulator;
        this.lightDiffCapture = lightDiffCapture;
        this.folia = detectFolia();
    }

    public boolean isFolia() {
        return folia;
    }

    public void start() {
        if (!running.compareAndSet(false, true)) {
            return;
        }
        if (folia) {
            scheduleFoliaCycle();
            return;
        }
        globalTaskId = Bukkit.getScheduler().runTaskTimer(plugin, this::runPaperDrain, 1L, 1L).getTaskId();
    }

    public void stop() {
        if (!running.compareAndSet(true, false)) {
            return;
        }
        if (!folia && globalTaskId != -1) {
            try {
                Bukkit.getScheduler().cancelTask(globalTaskId);
            } catch (Throwable ignored) {
            }
            globalTaskId = -1;
        }
        try {
            accumulator.drainAll(makeHook());
        } catch (Throwable ignored) {
        }
    }

    public boolean isRunning() {
        return running.get();
    }

    private RegionalDiffAccumulator.PreDrainHook makeHook() {
        if (lightDiffCapture == null) {
            return null;
        }
        return lightDiffCapture::sampleAround;
    }

    private void runPaperDrain() {
        accumulator.drainAll(makeHook());
    }

    private void scheduleFoliaCycle() {
        FoliaScheduler.runGlobal(plugin, this::dispatchRegionalDrains, 1L);
    }

    private void dispatchRegionalDrains() {
        if (!running.get()) {
            return;
        }
        RegionalDiffAccumulator.PreDrainHook hook = makeHook();
        for (Map.Entry<UUID, Map<Long, ChunkDirtySet>> worldEntry : accumulator.dirtyWorlds().entrySet()) {
            World world = Bukkit.getWorld(worldEntry.getKey());
            if (world == null) {
                continue;
            }
            for (Map.Entry<Long, ChunkDirtySet> chunkEntry : worldEntry.getValue().entrySet()) {
                long chunkKey = chunkEntry.getKey();
                if (chunkEntry.getValue().isEmpty() && !accumulator.hasPendingLight(worldEntry.getKey(), chunkKey)) {
                    continue;
                }
                int chunkX = (int) (chunkKey >> 32);
                int chunkZ = (int) chunkKey;
                FoliaScheduler.runRegion(plugin, world, chunkX, chunkZ, () -> accumulator.drainChunk(world, chunkKey, hook));
            }
        }
        if (running.get()) {
            FoliaScheduler.runGlobal(plugin, this::dispatchRegionalDrains, 1L);
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
