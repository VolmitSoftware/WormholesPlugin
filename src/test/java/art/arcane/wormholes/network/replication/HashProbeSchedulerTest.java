package art.arcane.wormholes.network.replication;

import art.arcane.wormholes.network.WireMessage;
import art.arcane.wormholes.network.view.ViewSlice;

import org.bukkit.World;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class HashProbeSchedulerTest {
    private static final String PEER = "peer-h";

    @Test
    void probeEntriesCarryRealHashes(@TempDir Path dir) {
        TestNetworkSink sink = new TestNetworkSink(dir);
        sink.registerFakePeer(PEER);
        ChunkReplicationManager manager = sink.getReplicationManager();
        World world = StubWorld.create(UUID.randomUUID());
        long chunkKey = ViewSlice.columnKey(2, 4);
        manager.subscribe(PEER, world, chunkKey);
        byte[] payload = synthesizeBulkPayload(2, 4);
        manager.sendBulk(PEER, chunkKey, payload, contentHashOf(payload));
        sink.clear();
        long expected = contentHashOf(payload);
        ChunkHashProbe.ChunkHashEntry entry = new ChunkHashProbe.ChunkHashEntry(chunkKey, manager.lastBroadcastSeq(PEER, chunkKey), manager.canonicalHash(PEER, chunkKey));
        assertEquals(expected, entry.hash());
        assertNotEquals(0L, entry.hash());

        HashProbeScheduler scheduler = new HashProbeScheduler(sink, manager);
        scheduler.configure(30L, 8);
        scheduler.probeOnce();
        List<WireMessage> sent = sink.sentTo(PEER);
        assertTrue(sent.size() >= 1);
        WireMessage probeMsg = sent.get(sent.size() - 1);
        assertTrue(probeMsg instanceof WireMessage.ChunkHashProbeMessage);
        WireMessage.ChunkHashProbeMessage probe = (WireMessage.ChunkHashProbeMessage) probeMsg;
        boolean foundChunk = false;
        for (ChunkHashProbe.ChunkHashEntry probedEntry : probe.probe().entries()) {
            if (probedEntry.chunkKey() == chunkKey) {
                foundChunk = true;
                assertEquals(expected, probedEntry.hash());
            }
        }
        assertTrue(foundChunk);
    }

    @Test
    void senderReceiverHashMatchAvoidsResync(@TempDir Path dir) {
        TestNetworkSink sink = new TestNetworkSink(dir);
        ChunkReplicationManager manager = sink.getReplicationManager();
        World world = StubWorld.create(UUID.randomUUID());
        long chunkKey = ViewSlice.columnKey(0, 0);
        manager.subscribe(PEER, world, chunkKey);
        byte[] payload = synthesizeBulkPayload(0, 0);
        manager.sendBulk(PEER, chunkKey, payload, contentHashOf(payload));

        RemoteChunkStore store = new RemoteChunkStore();
        try {
            store.applyBulk(new ChunkBulk(chunkKey, 1L, payload));
        } catch (java.io.IOException ex) {
            throw new AssertionError(ex);
        }
        long localHash = store.hashAt(chunkKey);
        long senderHash = manager.canonicalHash(PEER, chunkKey);
        assertEquals(senderHash, localHash);
        List<Long> mismatches = store.mismatches(List.of(new ChunkHashProbe.ChunkHashEntry(chunkKey, 1L, senderHash)));
        assertTrue(mismatches.isEmpty());
    }

    @Test
    void mismatchedHashTriggersResync(@TempDir Path dir) {
        TestNetworkSink sink = new TestNetworkSink(dir);
        ChunkReplicationManager manager = sink.getReplicationManager();
        World world = StubWorld.create(UUID.randomUUID());
        long chunkKey = ViewSlice.columnKey(0, 0);
        manager.subscribe(PEER, world, chunkKey);
        byte[] payload = synthesizeBulkPayload(0, 0);
        manager.sendBulk(PEER, chunkKey, payload, contentHashOf(payload));

        RemoteChunkStore store = new RemoteChunkStore();
        try {
            byte[] mutated = withFlippedBlock(payload);
            store.applyBulk(new ChunkBulk(chunkKey, 1L, mutated));
        } catch (java.io.IOException ex) {
            throw new AssertionError(ex);
        }
        long senderHash = manager.canonicalHash(PEER, chunkKey);
        List<Long> mismatches = store.mismatches(List.of(new ChunkHashProbe.ChunkHashEntry(chunkKey, 1L, senderHash)));
        assertEquals(1, mismatches.size());
        assertEquals(chunkKey, mismatches.get(0).longValue());
    }

    @Test
    void probeCarriesZeroHashForBlockDirtyChunk(@TempDir Path dir) {
        TestNetworkSink sink = new TestNetworkSink(dir);
        sink.registerFakePeer(PEER);
        ChunkReplicationManager manager = sink.getReplicationManager();
        World world = StubWorld.create(UUID.randomUUID());
        long chunkKey = ViewSlice.columnKey(6, 6);
        manager.subscribe(PEER, world, chunkKey);
        byte[] payload = synthesizeBulkPayload(6, 6);
        manager.sendBulk(PEER, chunkKey, payload, contentHashOf(payload));
        manager.onChunkDrain(world, chunkKey,
            List.of(new BlockChange(BlockChange.pack(1, 62, 1), "minecraft:dirt", BlockChange.FLAG_NONE)),
            List.of(), List.of());
        manager.flushTick();
        sink.clear();

        HashProbeScheduler scheduler = new HashProbeScheduler(sink, manager);
        scheduler.configure(30L, 8);
        scheduler.probeOnce();
        List<WireMessage> sent = sink.sentTo(PEER);
        assertTrue(sent.size() >= 1);
        WireMessage probeMsg = sent.get(sent.size() - 1);
        assertTrue(probeMsg instanceof WireMessage.ChunkHashProbeMessage);
        WireMessage.ChunkHashProbeMessage probe = (WireMessage.ChunkHashProbeMessage) probeMsg;
        boolean foundChunk = false;
        for (ChunkHashProbe.ChunkHashEntry probedEntry : probe.probe().entries()) {
            if (probedEntry.chunkKey() == chunkKey) {
                foundChunk = true;
                assertEquals(0L, probedEntry.hash());
                assertEquals(manager.lastBroadcastSeq(PEER, chunkKey), probedEntry.sequence());
                assertTrue(probedEntry.sequence() >= 2L);
            }
        }
        assertTrue(foundChunk);
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
        int minX = chunkX << 4;
        int minZ = chunkZ << 4;
        short[] biomes = new short[ViewSlice.biomeGridSpan(minX, sizeX) * ViewSlice.biomeGridSpan(60, sizeY) * ViewSlice.biomeGridSpan(minZ, sizeZ)];
        List<String> palette = List.of("minecraft:stone", "minecraft:dirt");
        List<String> biomePalette = List.of("minecraft:plains");
        ViewSlice slice = new ViewSlice(minX, 60, minZ, sizeX, sizeY, sizeZ, palette, indices, light, biomePalette, biomes);
        try {
            return ChunkBulkBuilder.encodeSliceBytes(slice);
        } catch (java.io.IOException ex) {
            throw new AssertionError(ex);
        }
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
}
