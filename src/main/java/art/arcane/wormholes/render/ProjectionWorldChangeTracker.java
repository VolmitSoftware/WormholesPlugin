package art.arcane.wormholes.render;

import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

public final class ProjectionWorldChangeTracker {
    private static final int MAX_TRACKED_CHUNKS_PER_WORLD = 8192;

    private final AtomicLong version = new AtomicLong();
    private final ConcurrentHashMap<UUID, ConcurrentHashMap<Long, Long>> worldChunks = new ConcurrentHashMap<UUID, ConcurrentHashMap<Long, Long>>();
    private final ConcurrentHashMap<UUID, Long> clearFloor = new ConcurrentHashMap<UUID, Long>();
    private final ConcurrentHashMap<UUID, AtomicLong> worldMaxStamp = new ConcurrentHashMap<UUID, AtomicLong>();

    public long currentVersion() {
        return version.get();
    }

    public void markChanged(UUID worldId, int blockX, int blockZ) {
        if (worldId == null) {
            return;
        }
        ConcurrentHashMap<Long, Long> chunks = worldChunks.computeIfAbsent(worldId, ignored -> new ConcurrentHashMap<Long, Long>(256));
        Long key = Long.valueOf(chunkKey(blockX >> 4, blockZ >> 4));
        Long existing = chunks.get(key);
        if (existing != null && existing.longValue() == version.get()) {
            return;
        }
        long stamp = version.incrementAndGet();
        Long boxed = Long.valueOf(stamp);
        chunks.put(key, boxed);
        worldMaxStamp.computeIfAbsent(worldId, ignored -> new AtomicLong()).accumulateAndGet(stamp, Math::max);
        if (chunks.size() > MAX_TRACKED_CHUNKS_PER_WORLD) {
            chunks.clear();
            clearFloor.put(worldId, boxed);
        }
    }

    public boolean dirtySince(UUID worldId, int minChunkX, int minChunkZ, int maxChunkX, int maxChunkZ, long sinceVersion) {
        if (worldId == null) {
            return true;
        }
        Long floor = clearFloor.get(worldId);
        if (floor != null && floor.longValue() > sinceVersion) {
            return true;
        }
        AtomicLong maxStamp = worldMaxStamp.get(worldId);
        if (maxStamp == null || maxStamp.get() <= sinceVersion) {
            return false;
        }
        ConcurrentHashMap<Long, Long> chunks = worldChunks.get(worldId);
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

    public void clearWorld(UUID worldId) {
        if (worldId == null) {
            return;
        }
        worldChunks.remove(worldId);
        clearFloor.remove(worldId);
        worldMaxStamp.remove(worldId);
    }

    private static long chunkKey(int chunkX, int chunkZ) {
        return (((long) chunkX) << 32) | (chunkZ & 0xFFFFFFFFL);
    }
}
