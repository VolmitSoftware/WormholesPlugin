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
import java.util.concurrent.atomic.AtomicInteger;

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
        manager.subscribe(PEER, world.getUID(), world, chunkKey);
        assertFalse(manager.isBulked(PEER, chunkKey));
        byte[] payload = synthesizeBulkPayload(2, 3);
        assertTrue(manager.sendBulk(PEER, world.getUID(), chunkKey, payload, contentHashOf(payload)));
        assertTrue(manager.isBulked(PEER, chunkKey));
        assertEquals(1L, manager.statsSnapshot().bulkSent());
        assertEquals(1, sink.sentCount(PEER));
    }

    @Test
    void rejectedBulkRemainsUnbulkedAndUncounted(@TempDir Path dir) {
        TestNetworkSink sink = new TestNetworkSink(dir);
        ChunkReplicationManager manager = sink.getReplicationManager();
        World world = StubWorld.create(UUID.randomUUID());
        long chunkKey = ViewSlice.columnKey(8, 9);
        manager.subscribe(PEER, world.getUID(), world, chunkKey);
        byte[] payload = synthesizeBulkPayload(8, 9);

        sink.setAccepting(false);
        assertFalse(manager.sendBulk(PEER, world.getUID(), chunkKey, payload, contentHashOf(payload)));

        assertFalse(manager.isBulked(PEER, chunkKey));
        assertEquals(0L, manager.canonicalHash(PEER, chunkKey));
        assertEquals(0L, manager.statsSnapshot().bulkSent());
        assertEquals(0, sink.sentCount(PEER));
    }

    @Test
    void subscribeIsIdempotent(@TempDir Path dir) {
        TestNetworkSink sink = new TestNetworkSink(dir);
        ChunkReplicationManager manager = sink.getReplicationManager();
        World world = StubWorld.create(UUID.randomUUID());
        long chunkKey = ViewSlice.columnKey(0, 0);
        manager.subscribe(PEER, world.getUID(), world, chunkKey);
        manager.subscribe(PEER, world.getUID(), world, chunkKey);
        assertEquals(1, manager.totalSubscriptionCount());
        manager.unsubscribe(PEER, world.getUID(), chunkKey);
        assertEquals(0, manager.totalSubscriptionCount());
    }

    @Test
    void overlappingPortalSubscriptionsReleaseOnlyAfterTheLastSession(@TempDir Path dir) {
        TestNetworkSink sink = new TestNetworkSink(dir);
        ChunkReplicationManager manager = sink.getReplicationManager();
        World world = StubWorld.create(UUID.randomUUID());
        UUID firstPortal = UUID.randomUUID();
        UUID secondPortal = UUID.randomUUID();
        long chunkKey = ViewSlice.columnKey(4, 4);
        manager.subscribe(PEER, firstPortal, world, chunkKey);
        manager.subscribe(PEER, secondPortal, world, chunkKey);

        manager.unsubscribe(PEER, firstPortal, chunkKey);

        assertTrue(manager.isSubscribed(PEER, chunkKey));
        assertEquals(1, manager.totalSubscriptionCount());
        byte[] payload = synthesizeBulkPayload(4, 4);
        assertFalse(manager.sendBulk(PEER, firstPortal, chunkKey, payload, contentHashOf(payload)));
        assertTrue(manager.sendBulk(PEER, secondPortal, chunkKey, payload, contentHashOf(payload)));

        manager.unsubscribe(PEER, secondPortal, chunkKey);

        assertFalse(manager.isSubscribed(PEER, chunkKey));
        assertEquals(0, manager.totalSubscriptionCount());
    }

    @Test
    void diffsAreFlushedAsBatch(@TempDir Path dir) {
        TestNetworkSink sink = new TestNetworkSink(dir);
        ChunkReplicationManager manager = sink.getReplicationManager();
        World world = StubWorld.create(UUID.randomUUID());
        long chunkKey = ViewSlice.columnKey(1, 1);
        manager.subscribe(PEER, world.getUID(), world, chunkKey);
        byte[] payload = synthesizeBulkPayload(1, 1);
        manager.sendBulk(PEER, world.getUID(), chunkKey, payload, contentHashOf(payload));
        sink.clear();
        manager.onChunkDrain(world, chunkKey, List.of(
            new BlockChange(BlockChange.pack(3, 80, 7), "minecraft:dirt", BlockChange.FLAG_NONE),
            new BlockChange(BlockChange.pack(4, 81, 7), "minecraft:stone", BlockChange.FLAG_NONE)
        ), List.of(), List.of());
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
    void rejectedMultiChunkDiffResetsEveryChunkForCanonicalRebulk(@TempDir Path dir) {
        TestNetworkSink sink = new TestNetworkSink(dir);
        ChunkReplicationManager manager = sink.getReplicationManager();
        World world = StubWorld.create(UUID.randomUUID());
        long firstChunk = ViewSlice.columnKey(10, 10);
        long secondChunk = ViewSlice.columnKey(11, 10);
        manager.subscribe(PEER, world.getUID(), world, firstChunk);
        manager.subscribe(PEER, world.getUID(), world, secondChunk);
        byte[] firstPayload = synthesizeBulkPayload(10, 10);
        byte[] secondPayload = synthesizeBulkPayload(11, 10);
        manager.sendBulk(PEER, world.getUID(), firstChunk, firstPayload, contentHashOf(firstPayload));
        manager.sendBulk(PEER, world.getUID(), secondChunk, secondPayload, contentHashOf(secondPayload));
        manager.onChunkDrain(world, firstChunk, List.of(
            new BlockChange(BlockChange.pack(1, 70, 1), "minecraft:dirt", BlockChange.FLAG_NONE)
        ), List.of(), List.of());
        manager.onChunkDrain(world, secondChunk, List.of(
            new BlockChange(BlockChange.pack(2, 71, 2), "minecraft:stone", BlockChange.FLAG_NONE)
        ), List.of(), List.of());
        sink.clear();
        sink.setAccepting(false);
        List<Long> retries = new ArrayList<>();
        manager.setBulkRetryListener((peerName, chunkKey) -> {
            assertEquals(PEER, peerName);
            retries.add(chunkKey);
        });

        manager.flushTick();

        assertFalse(manager.isBulked(PEER, firstChunk));
        assertFalse(manager.isBulked(PEER, secondChunk));
        assertEquals(0L, manager.canonicalHash(PEER, firstChunk));
        assertEquals(0L, manager.canonicalHash(PEER, secondChunk));
        assertEquals(0L, manager.statsSnapshot().diffsSent());
        assertEquals(0L, manager.statsSnapshot().blocksSent());
        assertEquals(0, sink.sentCount(PEER));
        assertEquals(List.of(firstChunk, secondChunk), retries);
    }

    @Test
    void unsubscribeClearsState(@TempDir Path dir) {
        TestNetworkSink sink = new TestNetworkSink(dir);
        ChunkReplicationManager manager = sink.getReplicationManager();
        World world = StubWorld.create(UUID.randomUUID());
        long chunkKey = ViewSlice.columnKey(5, 5);
        manager.subscribe(PEER, world.getUID(), world, chunkKey);
        byte[] payload = synthesizeBulkPayload(5, 5);
        manager.sendBulk(PEER, world.getUID(), chunkKey, payload, contentHashOf(payload));
        manager.unsubscribe(PEER, world.getUID(), chunkKey);
        assertEquals(0, manager.totalSubscriptionCount());
        assertEquals(0L, manager.canonicalHash(PEER, chunkKey));
    }

    @Test
    void lateBulkAfterUnsubscribeIsRejectedWithoutRecreatingState(@TempDir Path dir) {
        TestNetworkSink sink = new TestNetworkSink(dir);
        ChunkReplicationManager manager = sink.getReplicationManager();
        World world = StubWorld.create(UUID.randomUUID());
        long chunkKey = ViewSlice.columnKey(12, 13);
        manager.subscribe(PEER, world.getUID(), world, chunkKey);
        manager.unsubscribe(PEER, world.getUID(), chunkKey);

        byte[] payload = synthesizeBulkPayload(12, 13);
        assertFalse(manager.sendBulk(PEER, world.getUID(), chunkKey, payload, contentHashOf(payload)));

        assertFalse(manager.isSubscribed(PEER, chunkKey));
        assertEquals(0, manager.totalSubscriptionCount());
        assertEquals(0, sink.sentCount(PEER));
    }

    @Test
    void bulkCompletionActionWaitsForEveryRecoveryBulk(@TempDir Path dir) {
        TestNetworkSink sink = new TestNetworkSink(dir);
        ChunkReplicationManager manager = sink.getReplicationManager();
        World world = StubWorld.create(UUID.randomUUID());
        long firstChunk = ViewSlice.columnKey(14, 14);
        long secondChunk = ViewSlice.columnKey(15, 14);
        manager.subscribe(PEER, world.getUID(), world, firstChunk);
        manager.subscribe(PEER, world.getUID(), world, secondChunk);
        byte[] firstPayload = synthesizeBulkPayload(14, 14);
        byte[] secondPayload = synthesizeBulkPayload(15, 14);
        assertTrue(manager.sendBulk(PEER, world.getUID(), firstChunk, firstPayload, contentHashOf(firstPayload)));
        assertTrue(manager.sendBulk(PEER, world.getUID(), secondChunk, secondPayload, contentHashOf(secondPayload)));
        manager.requestResync(PEER, firstChunk);
        AtomicInteger completions = new AtomicInteger();

        assertFalse(manager.sendWhenAllBulked(PEER, world.getUID(), List.of(firstChunk, secondChunk), () -> {
            completions.incrementAndGet();
            return true;
        }));
        assertEquals(0, completions.get());

        assertTrue(manager.sendBulk(PEER, world.getUID(), firstChunk, firstPayload, contentHashOf(firstPayload)));
        assertTrue(manager.sendWhenAllBulked(PEER, world.getUID(), List.of(firstChunk, secondChunk), () -> {
            completions.incrementAndGet();
            return true;
        }));
        assertEquals(1, completions.get());
    }

    @Test
    void canonicalHashUpdatesOnBulk(@TempDir Path dir) {
        TestNetworkSink sink = new TestNetworkSink(dir);
        ChunkReplicationManager manager = sink.getReplicationManager();
        World world = StubWorld.create(UUID.randomUUID());
        long chunkKey = ViewSlice.columnKey(0, 0);
        manager.subscribe(PEER, world.getUID(), world, chunkKey);
        assertEquals(0L, manager.canonicalHash(PEER, chunkKey));
        byte[] payload = synthesizeBulkPayload(0, 0);
        manager.sendBulk(PEER, world.getUID(), chunkKey, payload, contentHashOf(payload));
        long expected = contentHashOf(payload);
        assertEquals(expected, manager.canonicalHash(PEER, chunkKey));
    }

    @Test
    void canonicalHashZeroAfterBlockChangeUntilRebulk(@TempDir Path dir) {
        TestNetworkSink sink = new TestNetworkSink(dir);
        ChunkReplicationManager manager = sink.getReplicationManager();
        World world = StubWorld.create(UUID.randomUUID());
        long chunkKey = ViewSlice.columnKey(0, 0);
        manager.subscribe(PEER, world.getUID(), world, chunkKey);
        byte[] initial = synthesizeBulkPayload(0, 0);
        manager.sendBulk(PEER, world.getUID(), chunkKey, initial, contentHashOf(initial));
        assertNotEquals(0L, manager.canonicalHash(PEER, chunkKey));
        manager.onChunkDrain(world, chunkKey,
            List.of(new BlockChange(BlockChange.pack(0, 60, 0), "minecraft:dirt", BlockChange.FLAG_NONE)),
            List.of(), List.of());
        assertEquals(0L, manager.canonicalHash(PEER, chunkKey));
        manager.flushTick();
        assertEquals(0L, manager.canonicalHash(PEER, chunkKey));
        byte[] updated = withFlippedBlock(initial);
        long updatedHash = contentHashOf(updated);
        manager.requestResync(PEER, chunkKey);
        assertTrue(manager.sendBulk(PEER, world.getUID(), chunkKey, updated, updatedHash));
        assertEquals(updatedHash, manager.canonicalHash(PEER, chunkKey));
    }

    @Test
    void rejectedRecoveryBulkPreservesGenerationAndQueuedDiffs(@TempDir Path dir) {
        TestNetworkSink sink = new TestNetworkSink(dir);
        ChunkReplicationManager manager = sink.getReplicationManager();
        World world = StubWorld.create(UUID.randomUUID());
        long chunkKey = ViewSlice.columnKey(3, 5);
        manager.subscribe(PEER, world.getUID(), world, chunkKey);
        BlockChange queued = new BlockChange(BlockChange.pack(2, 64, 4), "minecraft:stone", BlockChange.FLAG_NONE);
        manager.onChunkDrain(world, chunkKey, List.of(queued), List.of(), List.of());
        long generation = manager.bulkGeneration(PEER, chunkKey);
        byte[] payload = synthesizeBulkPayload(3, 5);

        sink.setAccepting(false);
        assertFalse(manager.sendBulk(PEER, world.getUID(), chunkKey, payload, contentHashOf(payload), generation));
        assertEquals(generation, manager.bulkGeneration(PEER, chunkKey));

        sink.setAccepting(true);
        assertTrue(manager.sendBulk(PEER, world.getUID(), chunkKey, payload, contentHashOf(payload), generation));
        assertEquals(0L, manager.canonicalHash(PEER, chunkKey));
        sink.clear();
        manager.flushTick();

        assertEquals(1, sink.sentCount(PEER));
        WireMessage.ChunkDiff diff = (WireMessage.ChunkDiff) sink.sentTo(PEER).get(0);
        assertEquals(List.of(queued), diff.batches().get(0).blocks());
    }

    @Test
    void canonicalHashUnaffectedByLightAndBlockEntityChanges(@TempDir Path dir) {
        TestNetworkSink sink = new TestNetworkSink(dir);
        ChunkReplicationManager manager = sink.getReplicationManager();
        World world = StubWorld.create(UUID.randomUUID());
        long chunkKey = ViewSlice.columnKey(0, 0);
        manager.subscribe(PEER, world.getUID(), world, chunkKey);
        byte[] payload = synthesizeBulkPayload(0, 0);
        long expected = contentHashOf(payload);
        manager.sendBulk(PEER, world.getUID(), chunkKey, payload, expected);
        manager.onChunkDrain(world, chunkKey, List.of(),
            List.of(LightDiff.full(4, LightDiff.TYPE_SKYLIGHT, new byte[LightDiff.DATA_LENGTH])),
            List.of(new BlockEntityDiff(BlockChange.pack(1, 61, 1), new byte[]{1})));
        assertEquals(expected, manager.canonicalHash(PEER, chunkKey));
    }

    @Test
    void requestResyncResetsBulkAndHash(@TempDir Path dir) {
        TestNetworkSink sink = new TestNetworkSink(dir);
        ChunkReplicationManager manager = sink.getReplicationManager();
        World world = StubWorld.create(UUID.randomUUID());
        long chunkKey = ViewSlice.columnKey(7, 7);
        manager.subscribe(PEER, world.getUID(), world, chunkKey);
        byte[] payload = synthesizeBulkPayload(7, 7);
        manager.sendBulk(PEER, world.getUID(), chunkKey, payload, contentHashOf(payload));
        manager.requestResync(PEER, chunkKey);
        assertFalse(manager.isBulked(PEER, chunkKey));
        assertEquals(0L, manager.canonicalHash(PEER, chunkKey));
        assertEquals(1L, manager.statsSnapshot().resyncRequests());
    }

    @Test
    void canonicalResyncRejectsSnapshotFromPriorGeneration(@TempDir Path dir) {
        TestNetworkSink sink = new TestNetworkSink(dir);
        ChunkReplicationManager manager = sink.getReplicationManager();
        World world = StubWorld.create(UUID.randomUUID());
        long chunkKey = ViewSlice.columnKey(9, 7);
        manager.subscribe(PEER, world.getUID(), world, chunkKey);
        long initialGeneration = manager.bulkGeneration(PEER, chunkKey);
        byte[] payload = synthesizeBulkPayload(9, 7);
        assertTrue(manager.sendBulk(PEER, world.getUID(), chunkKey, payload, contentHashOf(payload), initialGeneration));
        List<Long> retries = new ArrayList<>();
        manager.setBulkRetryListener((peerName, key) -> retries.add(key));

        manager.forceResync(world, chunkKey);

        long recoveryGeneration = manager.bulkGeneration(PEER, chunkKey);
        assertTrue(recoveryGeneration > initialGeneration);
        assertFalse(manager.sendBulk(PEER, world.getUID(), chunkKey, payload, contentHashOf(payload), initialGeneration));
        assertTrue(manager.sendBulk(PEER, world.getUID(), chunkKey, payload, contentHashOf(payload), recoveryGeneration));
        assertEquals(List.of(chunkKey), retries);
    }

    @Test
    void peerDiffOverflowFallsBackToCanonicalBulk(@TempDir Path dir) {
        TestNetworkSink sink = new TestNetworkSink(dir);
        ChunkReplicationManager manager = sink.getReplicationManager();
        manager.applyConfig(new ChunkReplicationManager.ReplicationConfig(1L));
        World world = StubWorld.create(UUID.randomUUID());
        long chunkKey = ViewSlice.columnKey(6, 8);
        manager.subscribe(PEER, world.getUID(), world, chunkKey);
        byte[] payload = synthesizeBulkPayload(6, 8);
        assertTrue(manager.sendBulk(PEER, world.getUID(), chunkKey, payload, contentHashOf(payload)));
        List<Long> retries = new ArrayList<>();
        manager.setBulkRetryListener((peerName, key) -> retries.add(key));

        manager.onChunkDrain(world, chunkKey, List.of(
            new BlockChange(BlockChange.pack(1, 70, 1), "minecraft:dirt", BlockChange.FLAG_NONE),
            new BlockChange(BlockChange.pack(2, 70, 1), "minecraft:stone", BlockChange.FLAG_NONE)
        ), List.of(), List.of());

        assertFalse(manager.isBulked(PEER, chunkKey));
        assertEquals(List.of(chunkKey), retries);
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

    @Test
    void subscribedChunkKeysReflectsSubscribeAndUnsubscribe(@TempDir Path dir) {
        TestNetworkSink sink = new TestNetworkSink(dir);
        ChunkReplicationManager manager = sink.getReplicationManager();
        World world = StubWorld.create(UUID.randomUUID());
        long chunkKeyA = ViewSlice.columnKey(0, 0);
        long chunkKeyB = ViewSlice.columnKey(1, 0);
        manager.subscribe(PEER, world.getUID(), world, chunkKeyA);
        manager.subscribe(PEER, world.getUID(), world, chunkKeyB);
        List<Long> keys = manager.subscribedChunkKeys(world.getUID());
        assertEquals(2, keys.size());
        assertTrue(keys.contains(chunkKeyA));
        assertTrue(keys.contains(chunkKeyB));
        manager.unsubscribe(PEER, world.getUID(), chunkKeyA);
        List<Long> remaining = manager.subscribedChunkKeys(world.getUID());
        assertEquals(1, remaining.size());
        assertTrue(remaining.contains(chunkKeyB));
        assertTrue(manager.subscribedChunkKeys(UUID.randomUUID()).isEmpty());
    }

    @Test
    void subscribedChunkKeysOmitsChunksWhoseLastPeerCleared(@TempDir Path dir) {
        TestNetworkSink sink = new TestNetworkSink(dir);
        ChunkReplicationManager manager = sink.getReplicationManager();
        World world = StubWorld.create(UUID.randomUUID());
        long chunkKey = ViewSlice.columnKey(2, 2);
        manager.subscribe(PEER, world.getUID(), world, chunkKey);
        manager.clearPeer(PEER);
        assertTrue(manager.subscribedChunkKeys(world.getUID()).isEmpty());
    }

    @Test
    void evictionListenerFiresOnlyWhenLastSubscriberLeaves(@TempDir Path dir) {
        TestNetworkSink sink = new TestNetworkSink(dir);
        ChunkReplicationManager manager = sink.getReplicationManager();
        World world = StubWorld.create(UUID.randomUUID());
        long chunkKey = ViewSlice.columnKey(3, 3);
        List<Long> evicted = new ArrayList<>();
        manager.setEvictionListener((worldId, key) -> {
            assertEquals(world.getUID(), worldId);
            evicted.add(key);
        });
        manager.subscribe(PEER, world.getUID(), world, chunkKey);
        manager.subscribe("peer-b", world.getUID(), world, chunkKey);
        manager.unsubscribe(PEER, world.getUID(), chunkKey);
        assertTrue(evicted.isEmpty());
        manager.unsubscribe("peer-b", world.getUID(), chunkKey);
        assertEquals(List.of(chunkKey), evicted);
        manager.subscribe(PEER, world.getUID(), world, chunkKey);
        manager.clearPeer(PEER);
        assertEquals(List.of(chunkKey, chunkKey), evicted);
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
}
