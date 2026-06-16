package art.arcane.wormholes.network.replication.capture;

import art.arcane.wormholes.network.replication.BlockChange;
import art.arcane.wormholes.network.replication.LightDiff;

import org.bukkit.Chunk;
import org.bukkit.ChunkSnapshot;
import org.bukkit.World;

import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.atomic.AtomicLong;

public final class LightDiffCapture {
    private static final int SECTION_SIZE = 16;
    private static final int CELLS_PER_SECTION = SECTION_SIZE * SECTION_SIZE * SECTION_SIZE;
    private static final int NIBBLE_BYTES = CELLS_PER_SECTION / 2;

    private final RegionalDiffAccumulator accumulator;
    private final AtomicLong sectionsSampled = new AtomicLong();
    private final AtomicLong diffsEmitted = new AtomicLong();

    public LightDiffCapture(RegionalDiffAccumulator accumulator) {
        this.accumulator = accumulator;
    }

    public void sampleAround(World world, long chunkKey, Set<Integer> blockChangePackedXyzs) {
        if (!accumulator.settings().lightCaptureEnabled() || world == null || blockChangePackedXyzs == null || blockChangePackedXyzs.isEmpty()) {
            return;
        }
        Set<Integer> sections = sectionRangeFromPackedXyzs(blockChangePackedXyzs);
        if (sections.isEmpty()) {
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
        ChunkLightShadow shadow = accumulator.lightShadowFor(world, chunkKey);
        for (Integer sectionYBoxed : sections) {
            int sectionY = sectionYBoxed.intValue();
            int sectionMinY = sectionY * SECTION_SIZE;
            if (sectionMinY + SECTION_SIZE - 1 < minHeight || sectionMinY >= maxHeight) {
                continue;
            }
            byte[] blockLightData = sampleSection(snapshot, sectionY, false);
            byte[] skyLightData = sampleSection(snapshot, sectionY, true);
            sectionsSampled.incrementAndGet();
            byte[] shadowBlock = shadow.getBlockLight(sectionY);
            if (blockLightData != null && !Arrays.equals(blockLightData, shadowBlock)) {
                accumulator.recordLightSection(world, chunkKey, sectionY, LightDiff.TYPE_BLOCKLIGHT, blockLightData);
                shadow.putBlockLight(sectionY, blockLightData);
                diffsEmitted.incrementAndGet();
            }
            byte[] shadowSky = shadow.getSkyLight(sectionY);
            if (skyLightData != null && !Arrays.equals(skyLightData, shadowSky)) {
                accumulator.recordLightSection(world, chunkKey, sectionY, LightDiff.TYPE_SKYLIGHT, skyLightData);
                shadow.putSkyLight(sectionY, skyLightData);
                diffsEmitted.incrementAndGet();
            }
        }
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

    private byte[] sampleSection(ChunkSnapshot snapshot, int sectionY, boolean skyLight) {
        if (NIBBLE_BYTES != LightDiff.DATA_LENGTH) {
            throw new IllegalStateException("Light section byte length mismatch");
        }
        byte[] out = new byte[LightDiff.DATA_LENGTH];
        int sectionMinY = sectionY * SECTION_SIZE;
        for (int ly = 0; ly < SECTION_SIZE; ly++) {
            int worldY = sectionMinY + ly;
            for (int lz = 0; lz < SECTION_SIZE; lz++) {
                for (int lx = 0; lx < SECTION_SIZE; lx++) {
                    int level;
                    try {
                        level = skyLight ? snapshot.getBlockSkyLight(lx, worldY, lz) : snapshot.getBlockEmittedLight(lx, worldY, lz);
                    } catch (Throwable ignored) {
                        return null;
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
        return out;
    }
}
