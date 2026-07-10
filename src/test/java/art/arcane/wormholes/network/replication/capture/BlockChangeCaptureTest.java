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

class BlockChangeCaptureTest {
    private static final String PEER = "peer-c";

    @Test
    void capturedBlockChangesPropagateThroughAccumulator(@TempDir Path dir) {
        TestNetworkSink sink = new TestNetworkSink(dir);
        ChunkReplicationManager replication = sink.getReplicationManager();
        World world = StubWorld.create(UUID.randomUUID());
        long chunkKey = ViewSlice.columnKey(0, 0);
        replication.subscribe(PEER, world.getUID(), world, chunkKey);

        CapturingFeed feed = new CapturingFeed();
        RegionalDiffAccumulator accumulator = new RegionalDiffAccumulator(replication, feed, CaptureSettings.defaults());

        accumulator.recordBlockChange(world, 5, 70, 5, fakeBlockData("minecraft:stone"), BlockChange.FLAG_NONE);
        accumulator.recordBlockChange(world, 6, -20, 5, fakeBlockData("minecraft:deepslate"), BlockChange.FLAG_NONE);
        accumulator.recordBlockChange(world, 7, 250, 5, fakeBlockData("minecraft:air"), BlockChange.FLAG_NONE);
        drainAllSafely(accumulator, world);

        assertEquals(3, feed.blocks.size());
        boolean sawNegative = false;
        boolean sawHigh = false;
        for (BlockChange change : feed.blocks) {
            int y = BlockChange.unpackY(change.packedXyz());
            if (y == -20) {
                sawNegative = true;
            }
            if (y == 250) {
                sawHigh = true;
            }
        }
        assertTrue(sawNegative);
        assertTrue(sawHigh);
    }

    @Test
    void overflowCappedByMaxQueuedDiffsPerChunk(@TempDir Path dir) {
        TestNetworkSink sink = new TestNetworkSink(dir);
        ChunkReplicationManager replication = sink.getReplicationManager();
        World world = StubWorld.create(UUID.randomUUID());
        long chunkKey = ViewSlice.columnKey(0, 0);
        replication.subscribe(PEER, world.getUID(), world, chunkKey);
        CapturingFeed feed = new CapturingFeed();
        CaptureSettings tight = new CaptureSettings(100, 4, true, true);
        RegionalDiffAccumulator accumulator = new RegionalDiffAccumulator(replication, feed, tight);
        for (int i = 0; i < 10; i++) {
            accumulator.recordBlockChange(world, i, 60, 0, fakeBlockData("minecraft:stone"), BlockChange.FLAG_NONE);
        }
        drainAllSafely(accumulator, world);
        assertTrue(feed.blocks.size() <= 4);
        assertTrue(accumulator.stats().overflowDrops() > 0L);
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
