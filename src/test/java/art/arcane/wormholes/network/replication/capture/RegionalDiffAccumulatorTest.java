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
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

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
        accumulator.resetChunk(world.getUID(), chunkKey);
        drainAllSafely(accumulator, world);
        assertTrue(feed.blocks.isEmpty());
    }

    @Test
    void preDrainHookFiresWithEmptyBlockSetForPreviouslyDirtyChunk(@TempDir Path dir) {
        TestNetworkSink sink = new TestNetworkSink(dir);
        ChunkReplicationManager replication = sink.getReplicationManager();
        World world = StubWorld.create(UUID.randomUUID());
        long chunkKey = ViewSlice.columnKey(0, 0);
        replication.subscribe(PEER, world, chunkKey);

        CapturingFeed feed = new CapturingFeed();
        RegionalDiffAccumulator accumulator = new RegionalDiffAccumulator(replication, feed, CaptureSettings.defaults());
        accumulator.recordBlockChange(world, 3, 64, 5, fakeBlockData("minecraft:stone"), BlockChange.FLAG_NONE);

        List<Set<Integer>> hookCalls = new ArrayList<>();
        RegionalDiffAccumulator.PreDrainHook hook = (w, key, packed) -> hookCalls.add(new HashSet<>(packed));
        accumulator.drainChunk(world, chunkKey, hook);
        assertEquals(1, hookCalls.size());
        assertEquals(1, hookCalls.get(0).size());

        accumulator.drainChunk(world, chunkKey, hook);
        assertEquals(2, hookCalls.size());
        assertTrue(hookCalls.get(1).isEmpty());
    }

    @Test
    void stateStringCacheComputesOncePerCanonicalState(@TempDir Path dir) {
        TestNetworkSink sink = new TestNetworkSink(dir);
        ChunkReplicationManager replication = sink.getReplicationManager();
        World world = StubWorld.create(UUID.randomUUID());
        long chunkKey = ViewSlice.columnKey(0, 0);
        replication.subscribe(PEER, world, chunkKey);

        CapturingFeed feed = new CapturingFeed();
        RegionalDiffAccumulator accumulator = new RegionalDiffAccumulator(replication, feed, CaptureSettings.defaults());
        AtomicInteger asStringCalls = new AtomicInteger();
        BlockData stone = countingBlockData("minecraft:stone", asStringCalls);
        accumulator.recordBlockChange(world, 1, 64, 1, stone, BlockChange.FLAG_NONE);
        accumulator.recordBlockChange(world, 2, 64, 1, stone, BlockChange.FLAG_NONE);
        drainAllSafely(accumulator, world);

        assertEquals(2, feed.blocks.size());
        for (BlockChange change : feed.blocks) {
            assertEquals("minecraft:stone", change.state());
        }
        assertEquals(1, asStringCalls.get());
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
        public void onChunkDrain(World world, long chunkKey, List<BlockChange> drainedBlocks, List<LightDiff> drainedLights, List<BlockEntityDiff> drainedEntities) {
            blocks.addAll(drainedBlocks);
            lights.addAll(drainedLights);
            entities.addAll(drainedEntities);
        }

        @Override
        public void onTickEnd() {
        }
    }

    private static BlockData countingBlockData(String stateString, AtomicInteger asStringCalls) {
        return (BlockData) Proxy.newProxyInstance(
            BlockData.class.getClassLoader(),
            new Class<?>[]{BlockData.class},
            (proxy, method, args) -> {
                if ("getAsString".equals(method.getName())) {
                    asStringCalls.incrementAndGet();
                    return stateString;
                }
                if ("clone".equals(method.getName())) {
                    return proxy;
                }
                if ("equals".equals(method.getName())) {
                    return proxy == args[0];
                }
                if ("hashCode".equals(method.getName())) {
                    return stateString.hashCode();
                }
                if ("toString".equals(method.getName())) {
                    return "CountingBlockData[" + stateString + "]";
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

    private static BlockData fakeBlockData(String stateString) {
        return (BlockData) Proxy.newProxyInstance(
            BlockData.class.getClassLoader(),
            new Class<?>[]{BlockData.class},
            (proxy, method, args) -> {
                if ("getAsString".equals(method.getName())) {
                    return stateString;
                }
                if ("clone".equals(method.getName())) {
                    return proxy;
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
