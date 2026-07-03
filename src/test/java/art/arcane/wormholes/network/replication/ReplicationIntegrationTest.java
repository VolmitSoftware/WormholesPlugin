package art.arcane.wormholes.network.replication;

import art.arcane.wormholes.network.WireMessage;
import art.arcane.wormholes.network.view.ViewSlice;

import org.bukkit.World;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ReplicationIntegrationTest {
    private static final String PEER = "peer-int";

    @Test
    void endToEndBulkAndDiffPropagatesToSink(@TempDir Path dir) throws IOException {
        TestNetworkSink source = new TestNetworkSink(dir);
        ChunkReplicationManager manager = source.getReplicationManager();
        World world = StubWorld.create(UUID.randomUUID());
        long chunkKey = ViewSlice.columnKey(0, 0);
        manager.subscribe(PEER, world, chunkKey);
        byte[] bulkPayload = synthesizeBulkPayload(0, 0, 17L);
        manager.sendBulk(PEER, chunkKey, bulkPayload, contentHashOf(bulkPayload));

        RemoteChunkStore sink = new RemoteChunkStore();
        WireMessage bulkMessage = source.sentTo(PEER).get(0);
        assertTrue(bulkMessage instanceof WireMessage.ChunkBulkBatch);
        WireMessage.ChunkBulkBatch chunkBulkBatch = (WireMessage.ChunkBulkBatch) bulkMessage;
        sink.applyBulk(chunkBulkBatch.chunks().get(0));
        assertEquals(manager.canonicalHash(PEER, chunkKey), sink.hashAt(chunkKey));

        Random random = new Random(42L);
        List<BlockChange> changes = new ArrayList<>(100);
        for (int i = 0; i < 100; i++) {
            int lx = random.nextInt(16);
            int ly = 60 + random.nextInt(24);
            int lz = random.nextInt(16);
            changes.add(new BlockChange(BlockChange.pack(lx, ly, lz), "minecraft:dirt", BlockChange.FLAG_NONE));
        }
        manager.onChunkDrain(world, chunkKey, changes, List.of(), List.of());
        source.clear();
        manager.flushTick();
        assertEquals(0L, manager.canonicalHash(PEER, chunkKey));
        assertTrue(source.sentCount(PEER) >= 1);
        WireMessage diffMessage = source.sentTo(PEER).get(0);
        assertTrue(diffMessage instanceof WireMessage.ChunkDiff);
        WireMessage.ChunkDiff diff = (WireMessage.ChunkDiff) diffMessage;
        for (ChunkDiffBatch batch : diff.batches()) {
            RemoteChunkStore.ApplyOutcome outcome = sink.applyDiff(batch);
            assertTrue(outcome.applied());
        }
    }

    @Test
    void forgedDivergenceDetectedByHashProbe(@TempDir Path dir) throws IOException {
        TestNetworkSink source = new TestNetworkSink(dir);
        ChunkReplicationManager manager = source.getReplicationManager();
        World world = StubWorld.create(UUID.randomUUID());
        long chunkKey = ViewSlice.columnKey(0, 0);
        manager.subscribe(PEER, world, chunkKey);
        byte[] bulkPayload = synthesizeBulkPayload(0, 0, 17L);
        manager.sendBulk(PEER, chunkKey, bulkPayload, contentHashOf(bulkPayload));

        RemoteChunkStore sink = new RemoteChunkStore();
        byte[] tamperedPayload = synthesizeBulkPayload(0, 0, 99L);
        sink.applyBulk(new ChunkBulk(chunkKey, 1L, tamperedPayload));
        assertNotEquals(manager.canonicalHash(PEER, chunkKey), sink.hashAt(chunkKey));

        List<ChunkHashProbe.ChunkHashEntry> probe = List.of(
            new ChunkHashProbe.ChunkHashEntry(chunkKey, 1L, manager.canonicalHash(PEER, chunkKey)));
        List<Long> mismatches = sink.mismatches(probe);
        assertEquals(1, mismatches.size());
        assertEquals(chunkKey, mismatches.get(0).longValue());
    }

    @Test
    void sequenceGapTriggersResyncRequest(@TempDir Path dir) throws IOException {
        RemoteChunkStore sink = new RemoteChunkStore(4, 50L);
        long chunkKey = ViewSlice.columnKey(0, 0);
        sink.applyBulk(new ChunkBulk(chunkKey, 1L, synthesizeBulkPayload(0, 0, 1L)));

        ChunkDiffBatch leap = new ChunkDiffBatch(chunkKey, 10L,
            List.of(new BlockChange(BlockChange.pack(0, 60, 0), "minecraft:dirt", BlockChange.FLAG_NONE)),
            List.<LightDiff>of(),
            List.<BlockEntityDiff>of());
        sink.applyDiff(leap);
        try {
            Thread.sleep(100L);
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
        }
        List<ChunkResyncRequest> requests = sink.collectTimeouts(System.currentTimeMillis());
        assertEquals(1, requests.size());
        assertEquals(chunkKey, requests.get(0).chunkKey());
    }

    @Test
    void resyncReBulksAfterTamper(@TempDir Path dir) throws IOException {
        TestNetworkSink source = new TestNetworkSink(dir);
        ChunkReplicationManager manager = source.getReplicationManager();
        World world = StubWorld.create(UUID.randomUUID());
        long chunkKey = ViewSlice.columnKey(1, 1);
        manager.subscribe(PEER, world, chunkKey);
        byte[] bulkPayload = synthesizeBulkPayload(1, 1, 5L);
        manager.sendBulk(PEER, chunkKey, bulkPayload, contentHashOf(bulkPayload));
        assertTrue(manager.isBulked(PEER, chunkKey));

        manager.requestResync(PEER, chunkKey);
        assertFalse(manager.isBulked(PEER, chunkKey));

        byte[] refreshed = synthesizeBulkPayload(1, 1, 5L);
        manager.sendBulk(PEER, chunkKey, refreshed, contentHashOf(refreshed));
        assertTrue(manager.isBulked(PEER, chunkKey));
        assertEquals(2L, manager.statsSnapshot().bulkSent());
    }

    private static long contentHashOf(byte[] payload) {
        try {
            return ViewSlice.read(new java.io.DataInputStream(new java.io.ByteArrayInputStream(payload))).contentHash();
        } catch (java.io.IOException ex) {
            throw new AssertionError(ex);
        }
    }

    private static byte[] synthesizeBulkPayload(int chunkX, int chunkZ, long seed) throws IOException {
        int sizeX = 16;
        int sizeY = 24;
        int sizeZ = 16;
        int cells = sizeX * sizeY * sizeZ;
        int minX = chunkX << 4;
        int minZ = chunkZ << 4;
        Random rng = new Random(seed);
        short[] indices = new short[cells];
        byte[] light = new byte[cells];
        short[] biomes = new short[ViewSlice.biomeGridSpan(minX, sizeX) * ViewSlice.biomeGridSpan(60, sizeY) * ViewSlice.biomeGridSpan(minZ, sizeZ)];
        for (int i = 0; i < cells; i++) {
            indices[i] = (short) (rng.nextInt(2));
            light[i] = (byte) rng.nextInt(16);
        }
        List<String> palette = new ArrayList<>(List.of("minecraft:stone", "minecraft:dirt"));
        List<String> biomePalette = new ArrayList<>(List.of("minecraft:plains"));
        ViewSlice slice = new ViewSlice(minX, 60, minZ, sizeX, sizeY, sizeZ, palette, indices, light, biomePalette, biomes);
        return ChunkBulkBuilder.encodeSliceBytes(slice);
    }
}
