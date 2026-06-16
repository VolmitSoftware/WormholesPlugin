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

    public synchronized void putBlockEntity(int packedXyz, BlockEntityDiff diff) {
        entities.put(packedXyz, diff);
    }

    public synchronized void putBlockLight(int sectionY, byte[] data) {
        blockLights.put(sectionY, new LightDiff(sectionY, LightDiff.TYPE_BLOCKLIGHT, data));
    }

    public synchronized void putSkyLight(int sectionY, byte[] data) {
        skyLights.put(sectionY, new LightDiff(sectionY, LightDiff.TYPE_SKYLIGHT, data));
    }

    public synchronized int blockCount() {
        return blocks.size();
    }

    public synchronized java.util.Set<Integer> snapshotBlockPacked() {
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
