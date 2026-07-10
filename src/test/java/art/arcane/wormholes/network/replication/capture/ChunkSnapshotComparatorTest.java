package art.arcane.wormholes.network.replication.capture;

import art.arcane.wormholes.network.replication.BlockChange;
import art.arcane.wormholes.network.replication.BlockChangeFeed;
import art.arcane.wormholes.network.replication.BlockEntityDiff;
import art.arcane.wormholes.network.replication.ChunkReplicationManager;
import art.arcane.wormholes.network.replication.LightDiff;
import art.arcane.wormholes.network.replication.StubWorld;
import art.arcane.wormholes.network.replication.TestNetworkSink;
import art.arcane.wormholes.network.view.ViewSlice;

import org.bukkit.ChunkSnapshot;
import org.bukkit.World;
import org.bukkit.block.data.BlockData;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ChunkSnapshotComparatorTest {
    private static final String PEER = "peer-snapshot";

    @Test
    void recordingDistinctStatesAtSameCoordinateFlagsDivergence(@TempDir Path dir) {
        TestNetworkSink sink = new TestNetworkSink(dir);
        ChunkReplicationManager replication = sink.getReplicationManager();
        World world = StubWorld.create(UUID.randomUUID());
        long chunkKey = ViewSlice.columnKey(0, 0);
        replication.subscribe(PEER, world.getUID(), world, chunkKey);
        CapturingFeed feed = new CapturingFeed();
        RegionalDiffAccumulator accumulator = new RegionalDiffAccumulator(replication, feed, CaptureSettings.defaults());
        accumulator.recordBlockChange(world, 3, 70, 3, fakeBlockData("minecraft:stone"), BlockChange.FLAG_NONE);
        accumulator.recordBlockChange(world, 3, 70, 3, fakeBlockData("minecraft:dirt"), BlockChange.FLAG_NONE);
        drainAllSafely(accumulator, world);
        assertEquals(1, feed.blocks.size(), "snapshot diff backstop dedupes intra-tick to a single later state");
    }

    @Test
    void recordingChangesAcrossYBandsKeepsDistinct(@TempDir Path dir) {
        TestNetworkSink sink = new TestNetworkSink(dir);
        ChunkReplicationManager replication = sink.getReplicationManager();
        World world = StubWorld.create(UUID.randomUUID());
        long chunkKey = ViewSlice.columnKey(0, 0);
        replication.subscribe(PEER, world.getUID(), world, chunkKey);
        CapturingFeed feed = new CapturingFeed();
        RegionalDiffAccumulator accumulator = new RegionalDiffAccumulator(replication, feed, CaptureSettings.defaults());
        accumulator.recordBlockChange(world, 1, -64, 1, fakeBlockData("minecraft:deepslate"), BlockChange.FLAG_NONE);
        accumulator.recordBlockChange(world, 1, 0, 1, fakeBlockData("minecraft:stone"), BlockChange.FLAG_NONE);
        accumulator.recordBlockChange(world, 1, 319, 1, fakeBlockData("minecraft:air"), BlockChange.FLAG_NONE);
        drainAllSafely(accumulator, world);
        assertEquals(3, feed.blocks.size());
        boolean haveMin = false;
        boolean haveMax = false;
        for (BlockChange change : feed.blocks) {
            int y = BlockChange.unpackY(change.packedXyz());
            if (y == -64) haveMin = true;
            if (y == 319) haveMax = true;
        }
        assertTrue(haveMin);
        assertTrue(haveMax);
    }

    @Test
    void repeatSweepsOverUnchangedSnapshotEmitNothing(@TempDir Path dir) throws Exception {
        TestNetworkSink sink = new TestNetworkSink(dir);
        ChunkReplicationManager replication = sink.getReplicationManager();
        World world = StubWorld.create(UUID.randomUUID());
        long chunkKey = ViewSlice.columnKey(0, 0);
        replication.subscribe(PEER, world.getUID(), world, chunkKey);
        CapturingFeed feed = new CapturingFeed();
        RegionalDiffAccumulator accumulator = new RegionalDiffAccumulator(replication, feed, CaptureSettings.defaults());
        ChunkSnapshotComparator comparator = new ChunkSnapshotComparator(null, replication, accumulator, CaptureSettings.defaults(), null);

        BlockData stone = fakeBlockData("minecraft:stone");
        ChunkSnapshot first = fakeSnapshot(stone, Map.of(), 5);
        ChunkSnapshot second = fakeSnapshot(stone, Map.of(), 5);
        invokeCompare(comparator, world, chunkKey, 0, 0, first, 0, 16);
        invokeCompare(comparator, world, chunkKey, 0, 0, second, 0, 16);
        drainAllSafely(accumulator, world);

        assertTrue(feed.blocks.isEmpty());
        assertEquals(0L, comparator.stats().divergencesEmitted());
    }

    @Test
    void mutatedCellEmitsExactlyOneDiff(@TempDir Path dir) throws Exception {
        TestNetworkSink sink = new TestNetworkSink(dir);
        ChunkReplicationManager replication = sink.getReplicationManager();
        World world = StubWorld.create(UUID.randomUUID());
        long chunkKey = ViewSlice.columnKey(0, 0);
        replication.subscribe(PEER, world.getUID(), world, chunkKey);
        CapturingFeed feed = new CapturingFeed();
        RegionalDiffAccumulator accumulator = new RegionalDiffAccumulator(replication, feed, CaptureSettings.defaults());
        ChunkSnapshotComparator comparator = new ChunkSnapshotComparator(null, replication, accumulator, CaptureSettings.defaults(), null);

        BlockData stone = fakeBlockData("minecraft:stone");
        BlockData dirt = fakeBlockData("minecraft:dirt");
        ChunkSnapshot baseline = fakeSnapshot(stone, Map.of(), 5);
        ChunkSnapshot mutated = fakeSnapshot(stone, Map.of(cellKey(3, 7, 4), dirt), 5);
        invokeCompare(comparator, world, chunkKey, 0, 0, baseline, 0, 16);
        invokeCompare(comparator, world, chunkKey, 0, 0, mutated, 0, 16);
        drainAllSafely(accumulator, world);

        assertEquals(1, feed.blocks.size());
        BlockChange change = feed.blocks.get(0);
        assertEquals("minecraft:dirt", change.state());
        assertEquals(7, BlockChange.unpackY(change.packedXyz()));
        assertEquals(1L, comparator.stats().divergencesEmitted());
    }

    private static void invokeCompare(ChunkSnapshotComparator comparator, World world, long chunkKey, int chunkX, int chunkZ, ChunkSnapshot snapshot, int minHeight, int maxHeight) throws Exception {
        Method method = ChunkSnapshotComparator.class.getDeclaredMethod("compareSnapshot", World.class, long.class, int.class, int.class, ChunkSnapshot.class, int.class, int.class);
        method.setAccessible(true);
        method.invoke(comparator, world, chunkKey, chunkX, chunkZ, snapshot, minHeight, maxHeight);
    }

    private static int cellKey(int x, int y, int z) {
        return (x << 16) | (z << 8) | y;
    }

    private static ChunkSnapshot fakeSnapshot(BlockData fill, Map<Integer, BlockData> overrides, int highestY) {
        return (ChunkSnapshot) Proxy.newProxyInstance(
            ChunkSnapshot.class.getClassLoader(),
            new Class<?>[]{ChunkSnapshot.class},
            (proxy, method, args) -> {
                String name = method.getName();
                if ("getBlockData".equals(name)) {
                    int x = (Integer) args[0];
                    int y = (Integer) args[1];
                    int z = (Integer) args[2];
                    BlockData override = overrides.get(cellKey(x, y, z));
                    return override != null ? override : fill;
                }
                if ("getHighestBlockYAt".equals(name)) {
                    return highestY;
                }
                if ("equals".equals(name)) {
                    return proxy == args[0];
                }
                if ("hashCode".equals(name)) {
                    return System.identityHashCode(proxy);
                }
                if ("toString".equals(name)) {
                    return "FakeChunkSnapshot";
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

        @Override
        public void onChunkDrain(World world, long chunkKey, List<BlockChange> drainedBlocks, List<LightDiff> drainedLights, List<BlockEntityDiff> drainedEntities) {
            blocks.addAll(drainedBlocks);
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
