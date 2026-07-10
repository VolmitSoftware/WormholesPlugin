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
import org.bukkit.block.Block;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.lang.reflect.Proxy;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class BlockEntityCaptureTest {
    private static final String PEER = "peer-be";

    @Test
    void recordBlockEntityProducesDiff(@TempDir Path dir) {
        TestNetworkSink sink = new TestNetworkSink(dir);
        ChunkReplicationManager replication = sink.getReplicationManager();
        World world = StubWorld.create(UUID.randomUUID());
        long chunkKey = ViewSlice.columnKey(0, 0);
        replication.subscribe(PEER, world.getUID(), world, chunkKey);
        CapturingFeed feed = new CapturingFeed();
        RegionalDiffAccumulator accumulator = new RegionalDiffAccumulator(replication, feed, CaptureSettings.defaults());

        byte[] nbt = "sign-line-1\nsign-line-2".getBytes();
        accumulator.recordBlockEntityChange(world, 4, 70, 8, nbt);
        drainAllSafely(accumulator, world);
        assertEquals(1, feed.entities.size());
        BlockEntityDiff diff = feed.entities.get(0);
        assertArrayEquals(nbt, diff.nbt());
        int unpackedY = BlockChange.unpackY(diff.packedXyz());
        assertEquals(70, unpackedY);
    }

    @Test
    void blockEntityCaptureDisabledSwallowsDiff(@TempDir Path dir) {
        TestNetworkSink sink = new TestNetworkSink(dir);
        ChunkReplicationManager replication = sink.getReplicationManager();
        World world = StubWorld.create(UUID.randomUUID());
        long chunkKey = ViewSlice.columnKey(0, 0);
        replication.subscribe(PEER, world.getUID(), world, chunkKey);
        CapturingFeed feed = new CapturingFeed();
        CaptureSettings disabled = new CaptureSettings(100, 256, true, false);
        RegionalDiffAccumulator accumulator = new RegionalDiffAccumulator(replication, feed, disabled);

        accumulator.recordBlockEntityChange(world, 0, 64, 0, new byte[]{1, 2, 3});
        drainAllSafely(accumulator, world);
        assertTrue(feed.entities.isEmpty());
    }

    @Test
    void blockEntityCapturesNegativeY(@TempDir Path dir) {
        TestNetworkSink sink = new TestNetworkSink(dir);
        ChunkReplicationManager replication = sink.getReplicationManager();
        World world = StubWorld.create(UUID.randomUUID());
        long chunkKey = ViewSlice.columnKey(0, 0);
        replication.subscribe(PEER, world.getUID(), world, chunkKey);
        CapturingFeed feed = new CapturingFeed();
        RegionalDiffAccumulator accumulator = new RegionalDiffAccumulator(replication, feed, CaptureSettings.defaults());
        accumulator.recordBlockEntityChange(world, 1, -40, 1, new byte[]{7});
        drainAllSafely(accumulator, world);
        assertEquals(1, feed.entities.size());
        assertEquals(-40, BlockChange.unpackY(feed.entities.get(0).packedXyz()));
    }

    @Test
    void captureFromBlockSkipsStateWorkWithoutSubscriber(@TempDir Path dir) {
        TestNetworkSink sink = new TestNetworkSink(dir);
        ChunkReplicationManager replication = sink.getReplicationManager();
        World world = StubWorld.create(UUID.randomUUID());
        CapturingFeed feed = new CapturingFeed();
        RegionalDiffAccumulator accumulator = new RegionalDiffAccumulator(replication, feed, CaptureSettings.defaults());
        BlockEntityCapture capture = new BlockEntityCapture(accumulator, null);

        AtomicBoolean stateQueried = new AtomicBoolean(false);
        Block block = fakeBlock(world, 4, 70, 8, stateQueried);
        capture.captureFromBlock(block);

        assertFalse(stateQueried.get());
        assertTrue(feed.entities.isEmpty());
    }

    @Test
    void captureFromBlockQueriesStateForSubscribedChunk(@TempDir Path dir) {
        TestNetworkSink sink = new TestNetworkSink(dir);
        ChunkReplicationManager replication = sink.getReplicationManager();
        World world = StubWorld.create(UUID.randomUUID());
        long chunkKey = ViewSlice.columnKey(0, 0);
        replication.subscribe(PEER, world.getUID(), world, chunkKey);
        CapturingFeed feed = new CapturingFeed();
        RegionalDiffAccumulator accumulator = new RegionalDiffAccumulator(replication, feed, CaptureSettings.defaults());
        BlockEntityCapture capture = new BlockEntityCapture(accumulator, null);

        AtomicBoolean stateQueried = new AtomicBoolean(false);
        Block block = fakeBlock(world, 4, 70, 8, stateQueried);
        capture.captureFromBlock(block);

        assertTrue(stateQueried.get());
    }

    @Test
    void captureFromBlockSkipsStateWorkWhenDisabled(@TempDir Path dir) {
        TestNetworkSink sink = new TestNetworkSink(dir);
        ChunkReplicationManager replication = sink.getReplicationManager();
        World world = StubWorld.create(UUID.randomUUID());
        long chunkKey = ViewSlice.columnKey(0, 0);
        replication.subscribe(PEER, world.getUID(), world, chunkKey);
        CapturingFeed feed = new CapturingFeed();
        CaptureSettings disabled = new CaptureSettings(100, 256, true, false);
        RegionalDiffAccumulator accumulator = new RegionalDiffAccumulator(replication, feed, disabled);
        BlockEntityCapture capture = new BlockEntityCapture(accumulator, null);

        AtomicBoolean stateQueried = new AtomicBoolean(false);
        Block block = fakeBlock(world, 4, 70, 8, stateQueried);
        capture.captureFromBlock(block);

        assertFalse(stateQueried.get());
    }

    private static Block fakeBlock(World world, int x, int y, int z, AtomicBoolean stateQueried) {
        return (Block) Proxy.newProxyInstance(
            Block.class.getClassLoader(),
            new Class<?>[]{Block.class},
            (proxy, method, args) -> {
                String name = method.getName();
                if ("getX".equals(name)) {
                    return x;
                }
                if ("getY".equals(name)) {
                    return y;
                }
                if ("getZ".equals(name)) {
                    return z;
                }
                if ("getWorld".equals(name)) {
                    return world;
                }
                if ("getState".equals(name)) {
                    stateQueried.set(true);
                    return null;
                }
                if ("equals".equals(name)) {
                    return proxy == args[0];
                }
                if ("hashCode".equals(name)) {
                    return System.identityHashCode(proxy);
                }
                if ("toString".equals(name)) {
                    return "FakeBlock[" + x + "," + y + "," + z + "]";
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
        private final List<BlockEntityDiff> entities = new ArrayList<>();

        @Override
        public void onChunkDrain(World world, long chunkKey, List<BlockChange> drainedBlocks, List<LightDiff> drainedLights, List<BlockEntityDiff> drainedEntities) {
            entities.addAll(drainedEntities);
        }

        @Override
        public void onTickEnd() {
        }
    }
}
