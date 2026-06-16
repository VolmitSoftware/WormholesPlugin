package art.arcane.wormholes.network.replication.capture;

import art.arcane.wormholes.network.replication.BlockChange;
import art.arcane.wormholes.network.replication.BlockChangeFeed;
import art.arcane.wormholes.network.replication.BlockEntityDiff;
import art.arcane.wormholes.network.replication.ChunkReplicationManager;
import art.arcane.wormholes.network.replication.LightDiff;
import art.arcane.wormholes.network.view.ViewSlice;

import org.bukkit.Bukkit;
import org.bukkit.World;
import org.bukkit.block.data.BlockData;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

public final class RegionalDiffAccumulator {
    private final ChunkReplicationManager replication;
    private final BlockChangeFeed feed;
    private volatile CaptureSettings settings;
    private final Map<UUID, Map<Long, ChunkDirtySet>> worldDirty = new ConcurrentHashMap<>();
    private final Map<UUID, Map<Long, ChunkLightShadow>> worldLightShadows = new ConcurrentHashMap<>();
    private final AtomicLong blocksCaptured = new AtomicLong();
    private final AtomicLong blocksDropped = new AtomicLong();
    private final AtomicLong overflowDrops = new AtomicLong();
    private final AtomicLong lightDiffsCaptured = new AtomicLong();
    private final AtomicLong entityDiffsCaptured = new AtomicLong();
    private final AtomicLong tickDrains = new AtomicLong();

    public RegionalDiffAccumulator(ChunkReplicationManager replication, BlockChangeFeed feed, CaptureSettings settings) {
        this.replication = replication;
        this.feed = feed;
        this.settings = settings == null ? CaptureSettings.defaults() : settings;
    }

    public void applySettings(CaptureSettings next) {
        this.settings = next == null ? CaptureSettings.defaults() : next;
    }

    public CaptureSettings settings() {
        return settings;
    }

    public boolean isRelevant(World world, long chunkKey) {
        if (world == null) {
            return false;
        }
        return replication.hasSubscribers(world, chunkKey);
    }

    public void recordBlockChange(World world, int worldX, int worldY, int worldZ, BlockData newData, byte flags) {
        if (world == null || newData == null) {
            return;
        }
        int chunkX = worldX >> 4;
        int chunkZ = worldZ >> 4;
        long chunkKey = ViewSlice.columnKey(chunkX, chunkZ);
        if (!replication.hasSubscribers(world, chunkKey)) {
            return;
        }
        int lx = worldX & 0xF;
        int lz = worldZ & 0xF;
        int packed = BlockChange.pack(lx, worldY, lz);
        String stateString = newData.getAsString();
        ChunkDirtySet set = dirtySetFor(world, chunkKey);
        int currentCap = settings.maxQueuedDiffsPerChunk();
        if (set.blockCount() >= currentCap) {
            overflowDrops.incrementAndGet();
            blocksDropped.incrementAndGet();
            return;
        }
        set.putBlock(packed, stateString, flags);
        blocksCaptured.incrementAndGet();
    }

    public void recordBlockEntityChange(World world, int worldX, int worldY, int worldZ, byte[] nbt) {
        if (!settings.blockEntityCaptureEnabled() || world == null || nbt == null) {
            return;
        }
        int chunkX = worldX >> 4;
        int chunkZ = worldZ >> 4;
        long chunkKey = ViewSlice.columnKey(chunkX, chunkZ);
        if (!replication.hasSubscribers(world, chunkKey)) {
            return;
        }
        int packed = BlockChange.pack(worldX & 0xF, worldY, worldZ & 0xF);
        ChunkDirtySet set = dirtySetFor(world, chunkKey);
        set.putBlockEntity(packed, new BlockEntityDiff(packed, nbt));
        entityDiffsCaptured.incrementAndGet();
    }

    public void recordLightSection(World world, long chunkKey, int sectionY, byte lightType, byte[] data) {
        if (!settings.lightCaptureEnabled() || world == null || data == null) {
            return;
        }
        if (data.length != LightDiff.DATA_LENGTH) {
            return;
        }
        if (!replication.hasSubscribers(world, chunkKey)) {
            return;
        }
        ChunkDirtySet set = dirtySetFor(world, chunkKey);
        if (lightType == LightDiff.TYPE_BLOCKLIGHT) {
            set.putBlockLight(sectionY, data);
        } else {
            set.putSkyLight(sectionY, data);
        }
        lightDiffsCaptured.incrementAndGet();
    }

    public ChunkLightShadow lightShadowFor(World world, long chunkKey) {
        Map<Long, ChunkLightShadow> worldMap = worldLightShadows.computeIfAbsent(world.getUID(), ignored -> new ConcurrentHashMap<>());
        return worldMap.computeIfAbsent(chunkKey, ignored -> new ChunkLightShadow());
    }

    public ChunkDirtySet dirtySetFor(World world, long chunkKey) {
        Map<Long, ChunkDirtySet> worldMap = worldDirty.computeIfAbsent(world.getUID(), ignored -> new ConcurrentHashMap<>());
        return worldMap.computeIfAbsent(chunkKey, ChunkDirtySet::new);
    }

    public Map<UUID, Map<Long, ChunkDirtySet>> dirtyWorlds() {
        return worldDirty;
    }

    public void drainChunk(World world, long chunkKey) {
        drainChunk(world, chunkKey, null);
    }

    public void drainChunk(World world, long chunkKey, PreDrainHook hook) {
        Map<Long, ChunkDirtySet> worldMap = worldDirty.get(world.getUID());
        if (worldMap == null) {
            return;
        }
        ChunkDirtySet set = worldMap.get(chunkKey);
        if (set == null) {
            return;
        }
        if (hook != null) {
            java.util.Set<Integer> blockPacked = set.snapshotBlockPacked();
            if (!blockPacked.isEmpty()) {
                hook.beforeDrain(world, chunkKey, blockPacked);
            }
        }
        ChunkDirtySet.Drain drained;
        synchronized (set) {
            if (set.isEmpty()) {
                return;
            }
            drained = set.drainAll();
        }
        dispatchDrain(world, chunkKey, drained);
    }

    public interface PreDrainHook {
        void beforeDrain(World world, long chunkKey, java.util.Set<Integer> blockChangePackedXyzs);
    }

    public void drainAll() {
        drainAll(null);
    }

    public void drainAll(PreDrainHook hook) {
        for (Map.Entry<UUID, Map<Long, ChunkDirtySet>> worldEntry : worldDirty.entrySet()) {
            UUID worldId = worldEntry.getKey();
            World world = Bukkit.getWorld(worldId);
            if (world == null) {
                continue;
            }
            for (Map.Entry<Long, ChunkDirtySet> chunkEntry : worldEntry.getValue().entrySet()) {
                long chunkKey = chunkEntry.getKey();
                ChunkDirtySet set = chunkEntry.getValue();
                if (hook != null) {
                    java.util.Set<Integer> blockPacked = set.snapshotBlockPacked();
                    if (!blockPacked.isEmpty()) {
                        hook.beforeDrain(world, chunkKey, blockPacked);
                    }
                }
                ChunkDirtySet.Drain drained;
                synchronized (set) {
                    if (set.isEmpty()) {
                        continue;
                    }
                    drained = set.drainAll();
                }
                dispatchDrain(world, chunkKey, drained);
            }
        }
        tickDrains.incrementAndGet();
    }

    public void resetChunk(World world, long chunkKey) {
        if (world == null) {
            return;
        }
        Map<Long, ChunkDirtySet> dirtyMap = worldDirty.get(world.getUID());
        if (dirtyMap != null) {
            dirtyMap.remove(chunkKey);
        }
        Map<Long, ChunkLightShadow> lightMap = worldLightShadows.get(world.getUID());
        if (lightMap != null) {
            lightMap.remove(chunkKey);
        }
    }

    public Stats stats() {
        return new Stats(
            blocksCaptured.get(),
            blocksDropped.get(),
            lightDiffsCaptured.get(),
            entityDiffsCaptured.get(),
            overflowDrops.get(),
            tickDrains.get()
        );
    }

    public record Stats(long blocksCaptured, long blocksDropped, long lightDiffsCaptured, long entityDiffsCaptured, long overflowDrops, long tickDrains) {
    }

    private void dispatchDrain(World world, long chunkKey, ChunkDirtySet.Drain drained) {
        List<BlockChange> blocks = drained.blocks();
        for (int i = 0; i < blocks.size(); i++) {
            feed.onBlockChange(world, chunkKey, blocks.get(i));
        }
        List<LightDiff> lights = drained.lights();
        for (int i = 0; i < lights.size(); i++) {
            feed.onLightChange(world, chunkKey, lights.get(i));
        }
        List<BlockEntityDiff> entities = drained.entities();
        for (int i = 0; i < entities.size(); i++) {
            feed.onBlockEntityChange(world, chunkKey, entities.get(i));
        }
    }
}
