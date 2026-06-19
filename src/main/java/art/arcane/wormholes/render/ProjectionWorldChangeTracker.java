package art.arcane.wormholes.render;

import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

import org.bukkit.World;

public final class ProjectionWorldChangeTracker {
    private static final int MAX_TRACKED_CHUNKS_PER_WORLD = 8192;

    private final AtomicLong version = new AtomicLong();
    private final ConcurrentHashMap<UUID, ConcurrentHashMap<Long, Long>> worldChunks = new ConcurrentHashMap<UUID, ConcurrentHashMap<Long, Long>>();
    private final ConcurrentHashMap<UUID, Long> clearFloor = new ConcurrentHashMap<UUID, Long>();

    public long currentVersion() {
        return version.get();
    }

    public void markChanged(World world, int blockX, int blockZ) {
        if (world == null) {
            return;
        }
        UUID id = world.getUID();
        long stamp = version.incrementAndGet();
        ConcurrentHashMap<Long, Long> chunks = worldChunks.computeIfAbsent(id, ignored -> new ConcurrentHashMap<Long, Long>(256));
        chunks.put(Long.valueOf(chunkKey(blockX >> 4, blockZ >> 4)), Long.valueOf(stamp));
        if (chunks.size() > MAX_TRACKED_CHUNKS_PER_WORLD) {
            chunks.clear();
            clearFloor.put(id, Long.valueOf(stamp));
        }
    }

    public boolean dirtySince(World world, int minChunkX, int minChunkZ, int maxChunkX, int maxChunkZ, long sinceVersion) {
        if (world == null) {
            return true;
        }
        UUID id = world.getUID();
        Long floor = clearFloor.get(id);
        if (floor != null && floor.longValue() > sinceVersion) {
            return true;
        }
        ConcurrentHashMap<Long, Long> chunks = worldChunks.get(id);
        if (chunks == null || chunks.isEmpty()) {
            return false;
        }
        for (int cx = minChunkX; cx <= maxChunkX; cx++) {
            for (int cz = minChunkZ; cz <= maxChunkZ; cz++) {
                Long stamp = chunks.get(Long.valueOf(chunkKey(cx, cz)));
                if (stamp != null && stamp.longValue() > sinceVersion) {
                    return true;
                }
            }
        }
        return false;
    }

    public void clearWorld(World world) {
        if (world == null) {
            return;
        }
        worldChunks.remove(world.getUID());
        clearFloor.remove(world.getUID());
    }

    private static long chunkKey(int chunkX, int chunkZ) {
        return (((long) chunkX) << 32) | (chunkZ & 0xFFFFFFFFL);
    }
}
