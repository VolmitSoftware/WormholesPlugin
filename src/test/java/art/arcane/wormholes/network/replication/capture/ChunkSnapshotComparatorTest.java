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

import java.lang.reflect.Proxy;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
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
        replication.subscribe(PEER, world, chunkKey);
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
        replication.subscribe(PEER, world, chunkKey);
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
        public void onBlockChange(World world, long chunkKey, BlockChange change) {
            blocks.add(change);
        }

        @Override
        public void onLightChange(World world, long chunkKey, LightDiff diff) {
        }

        @Override
        public void onBlockEntityChange(World world, long chunkKey, BlockEntityDiff diff) {
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
