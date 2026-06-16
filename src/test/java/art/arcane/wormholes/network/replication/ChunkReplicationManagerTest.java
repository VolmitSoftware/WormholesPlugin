package art.arcane.wormholes.network.replication;

import art.arcane.wormholes.network.WireMessage;
import art.arcane.wormholes.network.view.ViewSlice;

import org.bukkit.World;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertFalse;

class ChunkReplicationManagerTest {
    private static final String PEER = "peer-a";

    @Test
    void subscribeThenSendBulkRecordsBulkSent(@TempDir Path dir) {
        TestNetworkSink sink = new TestNetworkSink(dir);
        ChunkReplicationManager manager = sink.getReplicationManager();
        World world = StubWorld.create(UUID.randomUUID());
        long chunkKey = ViewSlice.columnKey(2, 3);
        manager.subscribe(PEER, world, chunkKey);
        assertFalse(manager.isBulked(PEER, chunkKey));
        byte[] payload = synthesizeBulkPayload(2, 3);
        manager.sendBulk(PEER, chunkKey, payload);
        assertTrue(manager.isBulked(PEER, chunkKey));
        assertEquals(1L, manager.statsSnapshot().bulkSent());
        assertEquals(1, sink.sentCount(PEER));
    }

    @Test
    void subscribeIsIdempotent(@TempDir Path dir) {
        TestNetworkSink sink = new TestNetworkSink(dir);
        ChunkReplicationManager manager = sink.getReplicationManager();
        World world = StubWorld.create(UUID.randomUUID());
        long chunkKey = ViewSlice.columnKey(0, 0);
        manager.subscribe(PEER, world, chunkKey);
        manager.subscribe(PEER, world, chunkKey);
        assertEquals(1, manager.totalSubscriptionCount());
    }

    @Test
    void diffsAreFlushedAsBatch(@TempDir Path dir) {
        TestNetworkSink sink = new TestNetworkSink(dir);
        ChunkReplicationManager manager = sink.getReplicationManager();
        World world = StubWorld.create(UUID.randomUUID());
        long chunkKey = ViewSlice.columnKey(1, 1);
        manager.subscribe(PEER, world, chunkKey);
        manager.sendBulk(PEER, chunkKey, synthesizeBulkPayload(1, 1));
        sink.clear();
        manager.onBlockChange(world, chunkKey, new BlockChange(BlockChange.pack(3, 80, 7), "minecraft:dirt", BlockChange.FLAG_NONE));
        manager.onBlockChange(world, chunkKey, new BlockChange(BlockChange.pack(4, 81, 7), "minecraft:stone", BlockChange.FLAG_NONE));
        manager.flushTick();
        assertEquals(1, sink.sentCount(PEER));
        WireMessage outbound = sink.sentTo(PEER).get(0);
        assertTrue(outbound instanceof WireMessage.ChunkDiff);
        WireMessage.ChunkDiff diff = (WireMessage.ChunkDiff) outbound;
        assertEquals(1, diff.batches().size());
        assertEquals(2, diff.batches().get(0).blocks().size());
        assertEquals(2L, manager.statsSnapshot().blocksSent());
    }

    @Test
    void unsubscribeClearsState(@TempDir Path dir) {
        TestNetworkSink sink = new TestNetworkSink(dir);
        ChunkReplicationManager manager = sink.getReplicationManager();
        World world = StubWorld.create(UUID.randomUUID());
        long chunkKey = ViewSlice.columnKey(5, 5);
        manager.subscribe(PEER, world, chunkKey);
        manager.sendBulk(PEER, chunkKey, synthesizeBulkPayload(5, 5));
        manager.unsubscribe(PEER, chunkKey);
        assertEquals(0, manager.totalSubscriptionCount());
        assertEquals(0L, manager.canonicalHash(PEER, chunkKey));
    }

    @Test
    void canonicalHashUpdatesOnBulk(@TempDir Path dir) {
        TestNetworkSink sink = new TestNetworkSink(dir);
        ChunkReplicationManager manager = sink.getReplicationManager();
        World world = StubWorld.create(UUID.randomUUID());
        long chunkKey = ViewSlice.columnKey(0, 0);
        manager.subscribe(PEER, world, chunkKey);
        assertEquals(0L, manager.canonicalHash(PEER, chunkKey));
        byte[] payload = synthesizeBulkPayload(0, 0);
        manager.sendBulk(PEER, chunkKey, payload);
        long expected = contentHashOf(payload);
        assertEquals(expected, manager.canonicalHash(PEER, chunkKey));
    }

    private static long contentHashOf(byte[] payload) {
        try {
            return ViewSlice.read(new java.io.DataInputStream(new java.io.ByteArrayInputStream(payload))).contentHash();
        } catch (java.io.IOException ex) {
            throw new AssertionError(ex);
        }
    }

    private static byte[] withFlippedBlock(byte[] payload) {
        try {
            ViewSlice slice = ViewSlice.read(new java.io.DataInputStream(new java.io.ByteArrayInputStream(payload)));
            slice.indices()[0] = (short) (slice.indices()[0] == 0 ? 1 : 0);
            return ChunkBulkBuilder.encodeSliceBytes(slice);
        } catch (java.io.IOException ex) {
            throw new AssertionError(ex);
        }
    }

    @Test
    void canonicalHashRefreshesViaResolverWhenDirtied(@TempDir Path dir) {
        TestNetworkSink sink = new TestNetworkSink(dir);
        ChunkReplicationManager manager = sink.getReplicationManager();
        World world = StubWorld.create(UUID.randomUUID());
        long chunkKey = ViewSlice.columnKey(0, 0);
        manager.subscribe(PEER, world, chunkKey);
        byte[] initial = synthesizeBulkPayload(0, 0);
        manager.sendBulk(PEER, chunkKey, initial);
        long initialHash = manager.canonicalHash(PEER, chunkKey);
        byte[] updated = withFlippedBlock(initial);
        manager.setPayloadResolver((peerName, key) -> updated);
        manager.onBlockChange(world, chunkKey, new BlockChange(BlockChange.pack(0, 60, 0), "minecraft:dirt", BlockChange.FLAG_NONE));
        long refreshed = manager.canonicalHash(PEER, chunkKey);
        assertNotEquals(initialHash, refreshed);
        assertEquals(contentHashOf(updated), refreshed);
    }

    @Test
    void requestResyncResetsBulkAndHash(@TempDir Path dir) {
        TestNetworkSink sink = new TestNetworkSink(dir);
        ChunkReplicationManager manager = sink.getReplicationManager();
        World world = StubWorld.create(UUID.randomUUID());
        long chunkKey = ViewSlice.columnKey(7, 7);
        manager.subscribe(PEER, world, chunkKey);
        manager.sendBulk(PEER, chunkKey, synthesizeBulkPayload(7, 7));
        manager.requestResync(PEER, chunkKey);
        assertFalse(manager.isBulked(PEER, chunkKey));
        assertEquals(0L, manager.canonicalHash(PEER, chunkKey));
        assertEquals(1L, manager.statsSnapshot().resyncRequests());
    }

    @Test
    void preShipSubscribeRegistersAndPromotes(@TempDir Path dir) {
        TestNetworkSink sink = new TestNetworkSink(dir);
        ChunkReplicationManager manager = sink.getReplicationManager();
        World world = StubWorld.create(UUID.randomUUID());
        UUID portalId = UUID.randomUUID();
        long chunkKey = ViewSlice.columnKey(0, 1);
        manager.subscribePreShip(PEER, portalId, world, List.of(chunkKey));
        assertEquals(1, manager.totalSubscriptionCount());
        assertFalse(manager.isPreShipPromoted(PEER, portalId, chunkKey));
        manager.promotePreShip(PEER, portalId);
        assertTrue(manager.isPreShipPromoted(PEER, portalId, chunkKey));
    }

    private static byte[] synthesizeBulkPayload(int chunkX, int chunkZ) {
        int sizeX = 16;
        int sizeY = 24;
        int sizeZ = 16;
        int cells = sizeX * sizeY * sizeZ;
        short[] indices = new short[cells];
        for (int i = 0; i < cells; i++) {
            indices[i] = (short) ((chunkX * 7 + chunkZ * 3 + i) % 2);
        }
        byte[] light = new byte[cells];
        short[] biomes = new short[cells];
        List<String> palette = List.of("minecraft:stone", "minecraft:dirt");
        List<String> biomePalette = List.of("minecraft:plains");
        ViewSlice slice = new ViewSlice(chunkX << 4, 60, chunkZ << 4, sizeX, sizeY, sizeZ, palette, indices, light, biomePalette, biomes);
        try {
            return ChunkBulkBuilder.encodeSliceBytes(slice);
        } catch (java.io.IOException ex) {
            throw new AssertionError(ex);
        }
    }
}
