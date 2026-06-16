package art.arcane.wormholes.network.replication.capture;

import art.arcane.wormholes.network.replication.BlockChange;
import art.arcane.wormholes.network.replication.BlockChangeFeed;
import art.arcane.wormholes.network.replication.BlockEntityDiff;
import art.arcane.wormholes.network.replication.ChunkReplicationManager;
import art.arcane.wormholes.network.replication.LightDiff;
import art.arcane.wormholes.network.replication.StubWorld;
import art.arcane.wormholes.network.replication.TestNetworkSink;
import art.arcane.wormholes.network.view.ViewSlice;

import org.bukkit.World;
import org.bukkit.block.data.BlockData;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RegionalDiffAccumulatorTest {
    private static final String PEER = "peer-r";

    @Test
    void recordedBlockChangeIsRoutedThroughFeed(@TempDir Path dir) {
        TestNetworkSink sink = new TestNetworkSink(dir);
        ChunkReplicationManager replication = sink.getReplicationManager();
        World world = StubWorld.create(UUID.randomUUID());
        long chunkKey = ViewSlice.columnKey(0, 0);
        replication.subscribe(PEER, world, chunkKey);

        CapturingFeed feed = new CapturingFeed();
        RegionalDiffAccumulator accumulator = new RegionalDiffAccumulator(replication, feed, CaptureSettings.defaults());
        accumulator.recordBlockChange(world, 3, -32, 5, fakeBlockData("minecraft:stone"), BlockChange.FLAG_NONE);
        accumulator.drainChunk(world, chunkKey);
        assertEquals(1, feed.blocks.size());
        BlockChange recorded = feed.blocks.get(0);
        assertEquals(-32, BlockChange.unpackY(recorded.packedXyz()));
    }

    @Test
    void duplicateBlockChangesOnSameCellDedupWithinTick(@TempDir Path dir) {
        TestNetworkSink sink = new TestNetworkSink(dir);
        ChunkReplicationManager replication = sink.getReplicationManager();
        World world = StubWorld.create(UUID.randomUUID());
        long chunkKey = ViewSlice.columnKey(0, 0);
        replication.subscribe(PEER, world, chunkKey);

        CapturingFeed feed = new CapturingFeed();
        RegionalDiffAccumulator accumulator = new RegionalDiffAccumulator(replication, feed, CaptureSettings.defaults());
        accumulator.recordBlockChange(world, 1, 64, 1, fakeBlockData("minecraft:stone"), BlockChange.FLAG_NONE);
        accumulator.recordBlockChange(world, 1, 64, 1, fakeBlockData("minecraft:dirt"), BlockChange.FLAG_NONE);
        accumulator.drainChunk(world, chunkKey);
        assertEquals(1, feed.blocks.size());
    }

    @Test
    void differentChunksAreIsolated(@TempDir Path dir) {
        TestNetworkSink sink = new TestNetworkSink(dir);
        ChunkReplicationManager replication = sink.getReplicationManager();
        World world = StubWorld.create(UUID.randomUUID());
        long chunkKeyA = ViewSlice.columnKey(0, 0);
        long chunkKeyB = ViewSlice.columnKey(1, 0);
        replication.subscribe(PEER, world, chunkKeyA);
        replication.subscribe(PEER, world, chunkKeyB);

        CapturingFeed feed = new CapturingFeed();
        RegionalDiffAccumulator accumulator = new RegionalDiffAccumulator(replication, feed, CaptureSettings.defaults());
        accumulator.recordBlockChange(world, 2, 64, 3, fakeBlockData("minecraft:stone"), BlockChange.FLAG_NONE);
        accumulator.recordBlockChange(world, 18, 64, 4, fakeBlockData("minecraft:dirt"), BlockChange.FLAG_NONE);
        drainAllSafely(accumulator, world);
        assertEquals(2, feed.blocks.size());
    }

    @Test
    void blockChangeBelowZeroPropagatesNegativeY(@TempDir Path dir) {
        TestNetworkSink sink = new TestNetworkSink(dir);
        ChunkReplicationManager replication = sink.getReplicationManager();
        World world = StubWorld.create(UUID.randomUUID());
        long chunkKey = ViewSlice.columnKey(0, 0);
        replication.subscribe(PEER, world, chunkKey);

        CapturingFeed feed = new CapturingFeed();
        RegionalDiffAccumulator accumulator = new RegionalDiffAccumulator(replication, feed, CaptureSettings.defaults());
        accumulator.recordBlockChange(world, 0, -50, 0, fakeBlockData("minecraft:deepslate"), BlockChange.FLAG_NONE);
        drainAllSafely(accumulator, world);
        assertEquals(1, feed.blocks.size());
        assertEquals(-50, BlockChange.unpackY(feed.blocks.get(0).packedXyz()));
    }

    @Test
    void resetChunkClearsState(@TempDir Path dir) {
        TestNetworkSink sink = new TestNetworkSink(dir);
        ChunkReplicationManager replication = sink.getReplicationManager();
        World world = StubWorld.create(UUID.randomUUID());
        long chunkKey = ViewSlice.columnKey(0, 0);
        replication.subscribe(PEER, world, chunkKey);

        CapturingFeed feed = new CapturingFeed();
        RegionalDiffAccumulator accumulator = new RegionalDiffAccumulator(replication, feed, CaptureSettings.defaults());
        accumulator.recordBlockChange(world, 3, 64, 4, fakeBlockData("minecraft:stone"), BlockChange.FLAG_NONE);
        accumulator.resetChunk(world, chunkKey);
        drainAllSafely(accumulator, world);
        assertTrue(feed.blocks.isEmpty());
    }

    private static void drainAllSafely(RegionalDiffAccumulator accumulator, World world) {
        java.util.Map<Long, ChunkDirtySet> chunkMap = accumulator.dirtyWorlds().get(world.getUID());
        if (chunkMap == null) {
            return;
        }
        for (long key : new java.util.ArrayList<>(chunkMap.keySet())) {
            accumulator.drainChunk(world, key);
        }
    }

    private static final class CapturingFeed implements BlockChangeFeed {
        private final List<BlockChange> blocks = new ArrayList<>();
        private final List<LightDiff> lights = new ArrayList<>();
        private final List<BlockEntityDiff> entities = new ArrayList<>();

        @Override
        public void onBlockChange(World world, long chunkKey, BlockChange change) {
            blocks.add(change);
        }

        @Override
        public void onLightChange(World world, long chunkKey, LightDiff diff) {
            lights.add(diff);
        }

        @Override
        public void onBlockEntityChange(World world, long chunkKey, BlockEntityDiff diff) {
            entities.add(diff);
        }

        @Override
        public void onTickEnd() {
        }
    }

    private static BlockData fakeBlockData(String stateString) {
        return (BlockData) Proxy.newProxyInstance(
            BlockData.class.getClassLoader(),
            new Class<?>[]{BlockData.class},
            (proxy, method, args) -> {
                if ("getAsString".equals(method.getName())) {
                    return stateString;
                }
                if ("equals".equals(method.getName())) {
                    return proxy == args[0];
                }
                if ("hashCode".equals(method.getName())) {
                    return stateString.hashCode();
                }
                if ("toString".equals(method.getName())) {
                    return "FakeBlockData[" + stateString + "]";
                }
                Class<?> rt = method.getReturnType();
                if (rt == boolean.class) {
                    return Boolean.FALSE;
                }
                if (rt == int.class || rt == long.class || rt == byte.class || rt == short.class) {
                    return 0;
                }
                if (rt == float.class || rt == double.class) {
                    return 0.0D;
                }
                return null;
            });
    }
}
