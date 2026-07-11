package art.arcane.wormholes.network;

import art.arcane.wormholes.network.view.EntityDeltaCodec;
import art.arcane.wormholes.network.view.EntityVisual;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SidebandOutboxTest {
    @Test
    void drainsTiersInPriorityOrderAndPreservesFifoWithinEachTier() {
        SidebandOutbox outbox = new SidebandOutbox(1_000L, 100L);
        MinecraftStatusBridge.EncodedMessage bestOne = bestEffort(1L, 10);
        MinecraftStatusBridge.EncodedMessage bestTwo = bestEffort(2L, 10);
        MinecraftStatusBridge.EncodedMessage bulkOne = bulk(1, 10);
        MinecraftStatusBridge.EncodedMessage bulkTwo = bulk(2, 10);
        MinecraftStatusBridge.EncodedMessage controlOne = control(1L, 10);
        MinecraftStatusBridge.EncodedMessage controlTwo = control(2L, 10);

        assertTrue(outbox.offer(List.of(bestOne, bestTwo)));
        assertTrue(outbox.offer(List.of(bulkOne, bulkTwo)));
        assertTrue(outbox.offer(List.of(controlOne, controlTwo)));

        SidebandOutbox.DrainBatch batch = outbox.drain(1_000, 64);

        assertEquals(List.of(controlOne, controlTwo, bulkOne, bulkTwo, bestOne, bestTwo), batch.messages());
        batch.commit();
    }

    @Test
    void viewBulkCompleteCannotOvertakeQueuedTerrain() {
        SidebandOutbox outbox = new SidebandOutbox(1_000L, 100L);
        MinecraftStatusBridge.EncodedMessage terrain = bulk(1, 10);
        MinecraftStatusBridge.EncodedMessage complete = encoded(new WireMessage.ViewBulkComplete(UUID.randomUUID()), 10);
        MinecraftStatusBridge.EncodedMessage control = control(1L, 10);
        assertTrue(outbox.offer(List.of(terrain)));
        assertTrue(outbox.offer(List.of(complete)));
        assertTrue(outbox.offer(List.of(control)));

        SidebandOutbox.DrainBatch batch = outbox.drain(1_000, 64);

        assertEquals(List.of(control, terrain, complete), batch.messages());
        batch.commit();
    }

    @Test
    void byteCapacityRejectsBestEffortAsOneCountedDrop() {
        SidebandOutbox outbox = new SidebandOutbox(100L, 20L);
        assertTrue(outbox.offer(List.of(bestEffort(1L, 40))));
        assertTrue(outbox.offer(List.of(bestEffort(2L, 40))));

        assertFalse(outbox.offer(List.of(bestEffort(3L, 1))));

        assertEquals(80L, outbox.queuedBytes());
        assertEquals(2L, outbox.queuedCount());
        assertEquals(1L, outbox.droppedBytes());
        assertEquals(1L, outbox.droppedCount());
    }

    @Test
    void controlReserveRemainsAvailableAfterBulkTierSaturates() {
        SidebandOutbox outbox = new SidebandOutbox(100L, 20L);
        MinecraftStatusBridge.EncodedMessage bulk = bulk(1, 80);
        MinecraftStatusBridge.EncodedMessage control = control(1L, 20);

        assertTrue(outbox.offer(List.of(bulk)));
        assertFalse(outbox.offer(List.of(bulk(2, 1))));
        assertTrue(outbox.offer(List.of(control)));

        assertEquals(100L, outbox.queuedBytes());
        SidebandOutbox.DrainBatch batch = outbox.drain(100, 64);
        assertEquals(List.of(control, bulk), batch.messages());
        batch.commit();
    }

    @Test
    void controlAdmissionShedsOldestBestEffortBeforeRejecting() {
        SidebandOutbox outbox = new SidebandOutbox(100L, 20L);
        MinecraftStatusBridge.EncodedMessage oldestBestEffort = bestEffort(1L, 40);
        MinecraftStatusBridge.EncodedMessage newestBestEffort = bestEffort(2L, 40);
        MinecraftStatusBridge.EncodedMessage control = control(1L, 30);
        assertTrue(outbox.offer(List.of(oldestBestEffort, newestBestEffort)));

        assertTrue(outbox.offer(List.of(control)));

        assertEquals(70L, outbox.queuedBytes());
        assertEquals(2L, outbox.queuedCount());
        assertEquals(40L, outbox.droppedBytes());
        assertEquals(1L, outbox.droppedCount());
        SidebandOutbox.DrainBatch batch = outbox.drain(100, 64);
        assertEquals(List.of(control, newestBestEffort), batch.messages());
        batch.commit();
    }

    @Test
    void rejectedBulkDoesNotShedBestEffortWhenSheddingCannotMakeItFit() {
        SidebandOutbox outbox = new SidebandOutbox(100L, 20L);
        MinecraftStatusBridge.EncodedMessage existingBulk = bulk(1, 70);
        MinecraftStatusBridge.EncodedMessage bestEffort = bestEffort(1L, 10);
        assertTrue(outbox.offer(List.of(existingBulk)));
        assertTrue(outbox.offer(List.of(bestEffort)));

        assertFalse(outbox.offer(List.of(bulk(2, 20))));

        assertEquals(80L, outbox.queuedBytes());
        assertEquals(2L, outbox.queuedCount());
        assertEquals(20L, outbox.droppedBytes());
        assertEquals(1L, outbox.droppedCount());
        SidebandOutbox.DrainBatch batch = outbox.drain(100, 64);
        assertEquals(List.of(existingBulk, bestEffort), batch.messages());
        batch.commit();
    }

    @Test
    void fragmentGroupsAreAdmittedOrDroppedAtomically() {
        SidebandOutbox outbox = new SidebandOutbox(100L, 20L);
        MinecraftStatusBridge.EncodedMessage first = fragment(1L, 0, 2, 40);
        MinecraftStatusBridge.EncodedMessage second = fragment(1L, 1, 2, 40);
        assertTrue(outbox.offer(List.of(first, second)));

        MinecraftStatusBridge.EncodedMessage rejectedFirst = fragment(2L, 0, 2, 10);
        MinecraftStatusBridge.EncodedMessage rejectedSecond = fragment(2L, 1, 2, 10);
        assertFalse(outbox.offer(List.of(rejectedFirst, rejectedSecond)));

        assertEquals(80L, outbox.queuedBytes());
        assertEquals(2L, outbox.queuedCount());
        assertEquals(20L, outbox.droppedBytes());
        assertEquals(2L, outbox.droppedCount());
        SidebandOutbox.DrainBatch batch = outbox.drain(100, 64);
        assertEquals(List.of(first, second), batch.messages());
        batch.commit();
    }

    @Test
    void fragmentedTrafficRetainsItsOriginalPriority() {
        SidebandOutbox outbox = new SidebandOutbox(100L, 20L);
        MinecraftStatusBridge.EncodedMessage bestEffortFragment = fragment(1L, 0, 1, 80, SidebandOutbox.TIER_BEST_EFFORT);
        MinecraftStatusBridge.EncodedMessage controlFragment = fragment(2L, 0, 1, 30, SidebandOutbox.TIER_CONTROL);
        assertTrue(outbox.offer(List.of(bestEffortFragment)));

        assertTrue(outbox.offer(List.of(controlFragment)));

        assertEquals(30L, outbox.queuedBytes());
        assertEquals(80L, outbox.droppedBytes());
        SidebandOutbox.DrainBatch batch = outbox.drain(100, 64);
        assertEquals(List.of(controlFragment), batch.messages());
        batch.commit();
    }

    @Test
    void fullEntitySnapshotsAndEmptyRostersCannotBeSilentlyShed() {
        WireMessage.ViewEntities full = new WireMessage.ViewEntities(
            UUID.randomUUID(),
            List.of(fullVisual(new UUID(0L, 1L))),
            List.of(new UUID(0L, 1L))
        );
        WireMessage.ViewEntities empty = new WireMessage.ViewEntities(UUID.randomUUID(), List.of(), List.of());
        WireMessage.ViewEntities delta = new WireMessage.ViewEntities(
            UUID.randomUUID(),
            List.of(deltaVisual(new UUID(0L, 2L))),
            List.of(new UUID(0L, 2L))
        );

        assertEquals(SidebandOutbox.TIER_BULK, SidebandOutbox.tierOf(full));
        assertEquals(SidebandOutbox.TIER_BULK, SidebandOutbox.tierOf(empty));
        assertEquals(SidebandOutbox.TIER_BEST_EFFORT, SidebandOutbox.tierOf(delta));
    }

    @Test
    void routedEntityFramesRetainFullVersusDeltaPriority() throws IOException {
        WireMessage.ViewEntities full = new WireMessage.ViewEntities(
            UUID.randomUUID(),
            List.of(fullVisual(new UUID(0L, 1L))),
            List.of(new UUID(0L, 1L))
        );
        WireMessage.ViewEntities delta = new WireMessage.ViewEntities(
            UUID.randomUUID(),
            List.of(deltaVisual(new UUID(0L, 2L))),
            List.of(new UUID(0L, 2L))
        );
        WireMessage.Routed routedFull = routed(full);
        WireMessage.Routed routedDelta = routed(delta);
        WireMessage.Routed malformed = new WireMessage.Routed("alpha", "beta", 4, WireMessageType.VIEW_ENTITIES, new byte[]{1});

        assertEquals(SidebandOutbox.TIER_BULK, SidebandOutbox.tierOf(routedFull));
        assertEquals(SidebandOutbox.TIER_BEST_EFFORT, SidebandOutbox.tierOf(routedDelta));
        assertEquals(SidebandOutbox.TIER_BULK, SidebandOutbox.tierOf(malformed));
    }

    @Test
    void reliableFullSnapshotShedsDeltaAndSurvivesLaterPressure() {
        SidebandOutbox outbox = new SidebandOutbox(100L, 20L);
        MinecraftStatusBridge.EncodedMessage delta = encoded(
            new WireMessage.ViewEntities(
                UUID.randomUUID(),
                List.of(deltaVisual(new UUID(0L, 1L))),
                List.of(new UUID(0L, 1L))
            ),
            50
        );
        MinecraftStatusBridge.EncodedMessage full = encoded(
            new WireMessage.ViewEntities(
                UUID.randomUUID(),
                List.of(fullVisual(new UUID(0L, 1L))),
                List.of(new UUID(0L, 1L))
            ),
            60
        );

        assertTrue(outbox.offer(List.of(delta)));
        assertTrue(outbox.offer(List.of(full)));
        assertFalse(outbox.offer(List.of(control(9L, 50))));

        assertEquals(60L, outbox.queuedBytes());
        assertEquals(1L, outbox.queuedCount());
        assertEquals(100L, outbox.droppedBytes());
        assertEquals(2L, outbox.droppedCount());
        SidebandOutbox.DrainBatch batch = outbox.drain(100, 64);
        assertEquals(List.of(full), batch.messages());
        batch.commit();
    }

    @Test
    void failedBatchRequeuesAtFrontInOriginalPerTierOrder() {
        SidebandOutbox outbox = new SidebandOutbox(200L, 40L);
        MinecraftStatusBridge.EncodedMessage controlOne = control(1L, 10);
        MinecraftStatusBridge.EncodedMessage controlTwo = control(2L, 10);
        MinecraftStatusBridge.EncodedMessage bulkOne = bulk(1, 20);
        MinecraftStatusBridge.EncodedMessage bulkTwo = bulk(2, 20);
        MinecraftStatusBridge.EncodedMessage bestOne = bestEffort(1L, 20);
        assertTrue(outbox.offer(List.of(controlOne, controlTwo)));
        assertTrue(outbox.offer(List.of(bulkOne, bulkTwo)));
        assertTrue(outbox.offer(List.of(bestOne)));

        SidebandOutbox.DrainBatch failed = outbox.drain(50, 64);
        assertEquals(List.of(controlOne, controlTwo, bulkOne), failed.messages());

        MinecraftStatusBridge.EncodedMessage controlThree = control(3L, 10);
        MinecraftStatusBridge.EncodedMessage bulkThree = bulk(3, 20);
        MinecraftStatusBridge.EncodedMessage bestTwo = bestEffort(2L, 20);
        assertTrue(outbox.offer(List.of(controlThree)));
        assertTrue(outbox.offer(List.of(bulkThree)));
        assertTrue(outbox.offer(List.of(bestTwo)));

        failed.requeue();

        assertEquals(130L, outbox.queuedBytes());
        assertEquals(8L, outbox.queuedCount());
        assertEquals(0L, outbox.droppedCount());
        SidebandOutbox.DrainBatch retried = outbox.drain(200, 64);
        assertEquals(
            List.of(controlOne, controlTwo, controlThree, bulkOne, bulkTwo, bulkThree, bestOne, bestTwo),
            retried.messages()
        );
        retried.commit();
    }

    @Test
    void onlyOneBatchCanBeOutstandingAndFailureKeepsStrictOrder() {
        SidebandOutbox outbox = new SidebandOutbox(200L, 40L);
        MinecraftStatusBridge.EncodedMessage first = control(1L, 20);
        MinecraftStatusBridge.EncodedMessage second = control(2L, 20);
        MinecraftStatusBridge.EncodedMessage third = control(3L, 20);
        assertTrue(outbox.offer(List.of(first, second)));

        SidebandOutbox.DrainBatch inFlight = outbox.drain(20, 64);
        assertEquals(List.of(first), inFlight.messages());
        assertTrue(outbox.drain(200, 64).messages().isEmpty());
        assertTrue(outbox.offer(List.of(third)));

        inFlight.requeue();

        SidebandOutbox.DrainBatch retried = outbox.drain(200, 64);
        assertEquals(List.of(first, second, third), retried.messages());
        retried.commit();
    }

    @Test
    void discardClearsQueuedAndInFlightMessagesWithoutResurrectingTheBatch() {
        SidebandOutbox outbox = new SidebandOutbox(200L, 40L);
        MinecraftStatusBridge.EncodedMessage inFlight = control(1L, 20);
        MinecraftStatusBridge.EncodedMessage queued = control(2L, 20);
        assertTrue(outbox.offer(List.of(inFlight)));
        SidebandOutbox.DrainBatch batch = outbox.drain(20, 64);
        assertEquals(List.of(inFlight), batch.messages());
        assertTrue(outbox.offer(List.of(queued)));

        outbox.discardAll();

        assertEquals(0L, outbox.queuedBytes());
        assertEquals(0L, outbox.queuedCount());
        assertEquals(40L, outbox.droppedBytes());
        assertEquals(2L, outbox.droppedCount());
        batch.requeue();
        batch.commit();
        assertTrue(outbox.drain(200, 64).messages().isEmpty());

        MinecraftStatusBridge.EncodedMessage afterDiscard = control(3L, 20);
        assertTrue(outbox.offer(List.of(afterDiscard)));
        SidebandOutbox.DrainBatch next = outbox.drain(200, 64);
        assertEquals(List.of(afterDiscard), next.messages());
        next.commit();
    }

    private static MinecraftStatusBridge.EncodedMessage control(long id, int frameBytes) {
        return encoded(new WireMessage.Ping(id), frameBytes);
    }

    private static MinecraftStatusBridge.EncodedMessage bulk(int id, int frameBytes) {
        return encoded(new WireMessage.ChunkDiff(List.of()), frameBytes);
    }

    private static MinecraftStatusBridge.EncodedMessage bestEffort(long id, int frameBytes) {
        return encoded(new WireMessage.ViewEntityAnimation(new UUID(0L, id), new UUID(0L, id), false, 0, 0.0F), frameBytes);
    }

    private static EntityVisual fullVisual(UUID id) {
        return EntityVisual.full(
            id,
            "minecraft:zombie",
            0.0D, 64.0D, 0.0D,
            1.95D,
            0.0D, 0.0D, 1.0D,
            0.0F, 0.0F,
            0.0D, 0.0D, 0.0D,
            true,
            "",
            "",
            "",
            null,
            null,
            new byte[0],
            new byte[0],
            0
        );
    }

    private static EntityVisual deltaVisual(UUID id) {
        EntityVisual full = fullVisual(id);
        return EntityDeltaCodec.buildDelta(full, full, 1, EntityVisual.FIELD_POSITION);
    }

    private static WireMessage.Routed routed(WireMessage.ViewEntities message) throws IOException {
        return new WireMessage.Routed("alpha", "beta", 4, WireMessageType.VIEW_ENTITIES, WireCodec.encodePayload(message));
    }

    private static MinecraftStatusBridge.EncodedMessage fragment(long id, int index, int total, int frameBytes) {
        return encoded(new WireMessage.SidebandFragment(id, index, total, frameBytes * total, new byte[]{1}), frameBytes);
    }

    private static MinecraftStatusBridge.EncodedMessage fragment(long id, int index, int total, int frameBytes, int tier) {
        WireMessage.SidebandFragment fragment = new WireMessage.SidebandFragment(id, index, total, frameBytes * total, new byte[]{1});
        return new MinecraftStatusBridge.EncodedMessage(fragment, new byte[frameBytes], tier);
    }

    private static MinecraftStatusBridge.EncodedMessage encoded(WireMessage message, int frameBytes) {
        return new MinecraftStatusBridge.EncodedMessage(message, new byte[frameBytes]);
    }
}
