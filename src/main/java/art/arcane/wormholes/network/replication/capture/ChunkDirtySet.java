package art.arcane.wormholes.network.replication.capture;

import art.arcane.wormholes.network.replication.BlockChange;
import art.arcane.wormholes.network.replication.BlockEntityDiff;
import art.arcane.wormholes.network.replication.LightDiff;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public final class ChunkDirtySet {
    public record BlockSlot(String state, byte flags) {
    }

    private final long chunkKey;
    private final Map<Integer, BlockSlot> blocks = new HashMap<>(16);
    private final Map<Integer, BlockEntityDiff> entities = new HashMap<>(4);
    private final Map<Integer, LightDiff> blockLights = new HashMap<>(4);
    private final Map<Integer, LightDiff> skyLights = new HashMap<>(4);

    public ChunkDirtySet(long chunkKey) {
        this.chunkKey = chunkKey;
    }

    public long chunkKey() {
        return chunkKey;
    }

    public synchronized void putBlock(int packedXyz, String state, byte flags) {
        blocks.put(packedXyz, new BlockSlot(state, flags));
    }

    public synchronized boolean putBlockIfBelowCapacity(int packedXyz, String state, byte flags, int capacity) {
        if (!blocks.containsKey(packedXyz) && blocks.size() >= capacity) {
            return false;
        }
        blocks.put(packedXyz, new BlockSlot(state, flags));
        return true;
    }

    public synchronized void putBlockEntity(int packedXyz, BlockEntityDiff diff) {
        entities.put(packedXyz, diff);
    }

    public synchronized void putBlockLight(LightDiff diff) {
        blockLights.put(diff.sectionY(), mergedPending(blockLights.get(diff.sectionY()), diff));
    }

    public synchronized void putSkyLight(LightDiff diff) {
        skyLights.put(diff.sectionY(), mergedPending(skyLights.get(diff.sectionY()), diff));
    }

    private static LightDiff mergedPending(LightDiff existing, LightDiff incoming) {
        if (existing == null || incoming.sparseCells() == null) {
            return incoming;
        }
        if (existing.sparseCells() == null) {
            return LightDiff.pending(incoming.sectionY(), incoming.lightType(), incoming.data(), null);
        }
        int[] union = sortedUnion(existing.sparseCells(), incoming.sparseCells());
        if (union.length > LightDiff.SPARSE_MAX_CELLS) {
            return LightDiff.pending(incoming.sectionY(), incoming.lightType(), incoming.data(), null);
        }
        return LightDiff.pending(incoming.sectionY(), incoming.lightType(), incoming.data(), union);
    }

    private static int[] sortedUnion(int[] a, int[] b) {
        int[] merged = new int[a.length + b.length];
        int i = 0;
        int j = 0;
        int k = 0;
        while (i < a.length && j < b.length) {
            if (a[i] == b[j]) {
                merged[k] = a[i];
                i++;
                j++;
            } else if (a[i] < b[j]) {
                merged[k] = a[i];
                i++;
            } else {
                merged[k] = b[j];
                j++;
            }
            k++;
        }
        while (i < a.length) {
            merged[k] = a[i];
            i++;
            k++;
        }
        while (j < b.length) {
            merged[k] = b[j];
            j++;
            k++;
        }
        return k == merged.length ? merged : java.util.Arrays.copyOf(merged, k);
    }

    public synchronized int blockCount() {
        return blocks.size();
    }

    public synchronized java.util.Set<Integer> snapshotBlockPacked() {
        if (blocks.isEmpty()) {
            return java.util.Set.of();
        }
        return new java.util.HashSet<>(blocks.keySet());
    }

    public synchronized boolean isEmpty() {
        return blocks.isEmpty() && entities.isEmpty() && blockLights.isEmpty() && skyLights.isEmpty();
    }

    public synchronized Drain drainAll() {
        List<BlockChange> blockList = new ArrayList<>(blocks.size());
        for (Map.Entry<Integer, BlockSlot> entry : blocks.entrySet()) {
            BlockSlot slot = entry.getValue();
            blockList.add(new BlockChange(entry.getKey().intValue(), slot.state(), slot.flags()));
        }
        List<BlockEntityDiff> entityList = new ArrayList<>(entities.values());
        List<LightDiff> lightList = new ArrayList<>(blockLights.size() + skyLights.size());
        lightList.addAll(blockLights.values());
        lightList.addAll(skyLights.values());
        blocks.clear();
        entities.clear();
        blockLights.clear();
        skyLights.clear();
        return new Drain(blockList, lightList, entityList);
    }

    public record Drain(List<BlockChange> blocks, List<LightDiff> lights, List<BlockEntityDiff> entities) {
    }
}
