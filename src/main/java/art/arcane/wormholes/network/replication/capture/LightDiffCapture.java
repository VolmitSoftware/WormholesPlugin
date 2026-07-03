package art.arcane.wormholes.network.replication.capture;

import art.arcane.wormholes.network.replication.BlockChange;
import art.arcane.wormholes.network.replication.LightDiff;

import org.bukkit.Chunk;
import org.bukkit.ChunkSnapshot;
import org.bukkit.World;

import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.atomic.AtomicLong;

public final class LightDiffCapture {
    private static final int SECTION_SIZE = 16;
    private static final int CELLS_PER_SECTION = SECTION_SIZE * SECTION_SIZE * SECTION_SIZE;
    private static final int NIBBLE_BYTES = CELLS_PER_SECTION / 2;
    private static final long SAMPLE_COOLDOWN_MILLIS = 250L;
    private static final int FULL_EMISSION_INTERVAL = 16;
    private static final int[] NO_CHANGED_CELLS = new int[0];
    private static final ThreadLocal<byte[]> BLOCK_SCRATCH = ThreadLocal.withInitial(() -> new byte[LightDiff.DATA_LENGTH]);
    private static final ThreadLocal<byte[]> SKY_SCRATCH = ThreadLocal.withInitial(() -> new byte[LightDiff.DATA_LENGTH]);

    private final RegionalDiffAccumulator accumulator;
    private final AtomicLong sectionsSampled = new AtomicLong();
    private final AtomicLong diffsEmitted = new AtomicLong();

    public LightDiffCapture(RegionalDiffAccumulator accumulator) {
        this.accumulator = accumulator;
    }

    public void sampleAround(World world, long chunkKey, Set<Integer> blockChangePackedXyzs) {
        if (!accumulator.settings().lightCaptureEnabled() || world == null || blockChangePackedXyzs == null) {
            return;
        }
        Set<Integer> sections = blockChangePackedXyzs.isEmpty() ? Set.of() : sectionRangeFromPackedXyzs(blockChangePackedXyzs);
        ChunkLightShadow shadow;
        if (sections.isEmpty()) {
            shadow = accumulator.lightShadowIfPresent(world, chunkKey);
            if (shadow == null || !shadow.hasPending()) {
                return;
            }
        } else {
            shadow = accumulator.lightShadowFor(world, chunkKey);
        }
        long now = System.currentTimeMillis();
        if (!shadow.tryBeginSample(now, SAMPLE_COOLDOWN_MILLIS)) {
            shadow.deferSections(sections);
            return;
        }
        Set<Integer> pending = shadow.drainPendingSections();
        Set<Integer> toSample;
        if (pending.isEmpty()) {
            toSample = sections;
        } else if (sections.isEmpty()) {
            toSample = pending;
        } else {
            toSample = new HashSet<>(sections.size() + pending.size());
            toSample.addAll(sections);
            toSample.addAll(pending);
        }
        if (toSample.isEmpty()) {
            return;
        }
        int chunkX = (int) (chunkKey >> 32);
        int chunkZ = (int) chunkKey;
        Chunk chunk;
        try {
            if (!world.isChunkLoaded(chunkX, chunkZ)) {
                return;
            }
            chunk = world.getChunkAt(chunkX, chunkZ);
        } catch (Throwable ignored) {
            return;
        }
        ChunkSnapshot snapshot;
        try {
            snapshot = chunk.getChunkSnapshot(false, false, false, true);
        } catch (Throwable ignored) {
            return;
        }
        int minHeight = world.getMinHeight();
        int maxHeight = world.getMaxHeight();
        byte[] blockScratch = BLOCK_SCRATCH.get();
        byte[] skyScratch = SKY_SCRATCH.get();
        for (Integer sectionYBoxed : toSample) {
            int sectionY = sectionYBoxed.intValue();
            int sectionMinY = sectionY * SECTION_SIZE;
            if (sectionMinY + SECTION_SIZE - 1 < minHeight || sectionMinY >= maxHeight) {
                continue;
            }
            boolean blockSampled = sampleSection(snapshot, sectionY, false, blockScratch);
            boolean skySampled = sampleSection(snapshot, sectionY, true, skyScratch);
            sectionsSampled.incrementAndGet();
            if (blockSampled) {
                emitSection(world, chunkKey, shadow, sectionY, LightDiff.TYPE_BLOCKLIGHT, blockScratch, shadow.getBlockLight(sectionY));
            }
            if (skySampled) {
                emitSection(world, chunkKey, shadow, sectionY, LightDiff.TYPE_SKYLIGHT, skyScratch, shadow.getSkyLight(sectionY));
            }
        }
    }

    private void emitSection(World world, long chunkKey, ChunkLightShadow shadow, int sectionY, byte lightType, byte[] sampled, byte[] shadowData) {
        int[] cells = emissionCells(shadowData, sampled);
        if (cells != null && cells.length == 0) {
            return;
        }
        if (shadow.nextEmitCounter(sectionY, lightType) == FULL_EMISSION_INTERVAL - 1) {
            cells = null;
        }
        byte[] copy = sampled.clone();
        accumulator.recordLightSection(world, chunkKey, LightDiff.pending(sectionY, lightType, copy, cells));
        if (lightType == LightDiff.TYPE_BLOCKLIGHT) {
            shadow.putBlockLight(sectionY, copy);
        } else {
            shadow.putSkyLight(sectionY, copy);
        }
        diffsEmitted.incrementAndGet();
    }

    static int[] emissionCells(byte[] shadowData, byte[] sampled) {
        if (shadowData == null || shadowData.length != sampled.length) {
            return null;
        }
        int[] changed = changedCells(shadowData, sampled);
        if (changed.length > LightDiff.SPARSE_MAX_CELLS) {
            return null;
        }
        return changed;
    }

    static int[] changedCells(byte[] previous, byte[] next) {
        int count = 0;
        for (int i = 0; i < previous.length; i++) {
            if (previous[i] == next[i]) {
                continue;
            }
            if ((previous[i] & 0x0F) != (next[i] & 0x0F)) {
                count++;
            }
            if ((previous[i] & 0xF0) != (next[i] & 0xF0)) {
                count++;
            }
        }
        if (count == 0) {
            return NO_CHANGED_CELLS;
        }
        int[] cells = new int[count];
        int index = 0;
        for (int i = 0; i < previous.length; i++) {
            if (previous[i] == next[i]) {
                continue;
            }
            if ((previous[i] & 0x0F) != (next[i] & 0x0F)) {
                cells[index] = i * 2;
                index++;
            }
            if ((previous[i] & 0xF0) != (next[i] & 0xF0)) {
                cells[index] = i * 2 + 1;
                index++;
            }
        }
        return cells;
    }

    public long sectionsSampled() {
        return sectionsSampled.get();
    }

    public long diffsEmitted() {
        return diffsEmitted.get();
    }

    private Set<Integer> sectionRangeFromPackedXyzs(Set<Integer> packed) {
        Set<Integer> result = new HashSet<>(8);
        for (Integer p : packed) {
            int y = BlockChange.unpackY(p.intValue());
            int sectionY = y >> 4;
            result.add(sectionY);
            result.add(sectionY - 1);
            result.add(sectionY + 1);
        }
        return result;
    }

    private boolean sampleSection(ChunkSnapshot snapshot, int sectionY, boolean skyLight, byte[] out) {
        if (NIBBLE_BYTES != LightDiff.DATA_LENGTH) {
            throw new IllegalStateException("Light section byte length mismatch");
        }
        int sectionMinY = sectionY * SECTION_SIZE;
        for (int ly = 0; ly < SECTION_SIZE; ly++) {
            int worldY = sectionMinY + ly;
            for (int lz = 0; lz < SECTION_SIZE; lz++) {
                for (int lx = 0; lx < SECTION_SIZE; lx++) {
                    int level;
                    try {
                        level = skyLight ? snapshot.getBlockSkyLight(lx, worldY, lz) : snapshot.getBlockEmittedLight(lx, worldY, lz);
                    } catch (Throwable ignored) {
                        return false;
                    }
                    int cellIndex = (ly << 8) | (lz << 4) | lx;
                    int byteIndex = cellIndex >> 1;
                    int nibble = level & 0x0F;
                    byte existing = out[byteIndex];
                    if ((cellIndex & 1) == 0) {
                        out[byteIndex] = (byte) ((existing & 0xF0) | nibble);
                    } else {
                        out[byteIndex] = (byte) ((existing & 0x0F) | (nibble << 4));
                    }
                }
            }
        }
        return true;
    }
}
