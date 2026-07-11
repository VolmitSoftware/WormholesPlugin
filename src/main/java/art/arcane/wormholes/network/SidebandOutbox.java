package art.arcane.wormholes.network;

import art.arcane.wormholes.network.view.EntityVisual;

import java.io.IOException;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

final class SidebandOutbox {
    static final int TIER_CONTROL = 0;
    static final int TIER_BULK = 1;
    static final int TIER_BEST_EFFORT = 2;
    private static final int TIER_COUNT = 3;

    private final long maxBytes;
    private final long nonControlLimitBytes;
    private final List<ArrayDeque<MinecraftStatusBridge.EncodedMessage>> queues;
    private final long[] queuedBytesByTier = new long[TIER_COUNT];
    private final long[] inFlightBytesByTier = new long[TIER_COUNT];

    private long queuedBytes;
    private long queuedCount;
    private long droppedBytes;
    private long droppedCount;
    private DrainBatch activeBatch;

    SidebandOutbox(long maxBytes, long controlReserveBytes) {
        if (maxBytes <= 0L) {
            throw new IllegalArgumentException("maxBytes must be positive");
        }
        if (controlReserveBytes < 0L || controlReserveBytes >= maxBytes) {
            throw new IllegalArgumentException("controlReserveBytes must be in [0, maxBytes)");
        }
        this.maxBytes = maxBytes;
        this.nonControlLimitBytes = maxBytes - controlReserveBytes;
        this.queues = List.of(new ArrayDeque<>(), new ArrayDeque<>(), new ArrayDeque<>());
    }

    synchronized boolean offer(List<MinecraftStatusBridge.EncodedMessage> group) {
        Objects.requireNonNull(group, "group");
        if (group.isEmpty()) {
            return true;
        }

        int tier = -1;
        boolean mixedTiers = false;
        long groupBytes = 0L;
        for (MinecraftStatusBridge.EncodedMessage entry : group) {
            Objects.requireNonNull(entry, "group entry");
            Objects.requireNonNull(entry.frame(), "group frame");
            int entryTier = tierOf(entry);
            if (tier < 0) {
                tier = entryTier;
            } else if (tier != entryTier) {
                mixedTiers = true;
            }
            groupBytes += entry.frame().length;
        }
        if (mixedTiers) {
            recordDropped(group.size(), groupBytes);
            return false;
        }

        long tierLimit = tier == TIER_CONTROL ? maxBytes : nonControlLimitBytes;
        if (groupBytes > tierLimit) {
            recordDropped(group.size(), groupBytes);
            return false;
        }

        if (tier != TIER_BEST_EFFORT && !fits(tier, groupBytes)) {
            if (!fitsAfterSheddingQueuedBestEffort(tier, groupBytes)) {
                recordDropped(group.size(), groupBytes);
                return false;
            }
            while (!fits(tier, groupBytes) && !queues.get(TIER_BEST_EFFORT).isEmpty()) {
                shedOldestBestEffort();
            }
        }
        if (!fits(tier, groupBytes)) {
            recordDropped(group.size(), groupBytes);
            return false;
        }

        ArrayDeque<MinecraftStatusBridge.EncodedMessage> queue = queues.get(tier);
        for (MinecraftStatusBridge.EncodedMessage entry : group) {
            queue.addLast(entry);
        }
        queuedBytesByTier[tier] += groupBytes;
        queuedBytes += groupBytes;
        queuedCount += group.size();
        return true;
    }

    synchronized DrainBatch drain(int budgetBytes, int maxMessages) {
        if (activeBatch != null) {
            return DrainBatch.empty(this);
        }
        int messageLimit = Math.max(0, maxMessages);
        long remainingBytes = Math.max(0L, budgetBytes);
        List<MinecraftStatusBridge.EncodedMessage> selected = new ArrayList<>(Math.min(messageLimit, 64));
        List<List<MinecraftStatusBridge.EncodedMessage>> selectedByTier = List.of(
            new ArrayList<>(),
            new ArrayList<>(),
            new ArrayList<>()
        );
        long[] selectedBytesByTier = new long[TIER_COUNT];

        for (int tier = 0; tier < TIER_COUNT && selected.size() < messageLimit; tier++) {
            ArrayDeque<MinecraftStatusBridge.EncodedMessage> queue = queues.get(tier);
            List<MinecraftStatusBridge.EncodedMessage> tierSelection = selectedByTier.get(tier);
            while (selected.size() < messageLimit && !queue.isEmpty()) {
                MinecraftStatusBridge.EncodedMessage next = queue.peekFirst();
                int frameBytes = next.frame().length;
                if (!selected.isEmpty() && frameBytes > remainingBytes) {
                    break;
                }
                queue.removeFirst();
                selected.add(next);
                tierSelection.add(next);
                selectedBytesByTier[tier] += frameBytes;
                remainingBytes -= frameBytes;
            }
        }

        long selectedBytes = 0L;
        long selectedCount = selected.size();
        for (int tier = 0; tier < TIER_COUNT; tier++) {
            long tierBytes = selectedBytesByTier[tier];
            queuedBytesByTier[tier] -= tierBytes;
            inFlightBytesByTier[tier] += tierBytes;
            selectedBytes += tierBytes;
        }
        queuedBytes -= selectedBytes;
        queuedCount -= selectedCount;
        DrainBatch batch = new DrainBatch(this, selected, selectedByTier, selectedBytesByTier, selectedBytes);
        if (!selected.isEmpty()) {
            activeBatch = batch;
        }
        return batch;
    }

    synchronized boolean isEmpty() {
        return queuedCount == 0L;
    }

    synchronized long queuedBytes() {
        return queuedBytes;
    }

    synchronized long queuedCount() {
        return queuedCount;
    }

    synchronized long droppedBytes() {
        return droppedBytes;
    }

    synchronized long droppedCount() {
        return droppedCount;
    }

    synchronized void discardAll() {
        long discardedBytes = queuedBytes;
        long discardedCount = queuedCount;
        for (ArrayDeque<MinecraftStatusBridge.EncodedMessage> queue : queues) {
            queue.clear();
        }
        for (int tier = 0; tier < TIER_COUNT; tier++) {
            queuedBytesByTier[tier] = 0L;
            inFlightBytesByTier[tier] = 0L;
        }
        queuedBytes = 0L;
        queuedCount = 0L;
        if (activeBatch != null) {
            discardedBytes += activeBatch.totalBytes;
            discardedCount += activeBatch.messages.size();
            activeBatch.discarded = true;
            activeBatch.completed = true;
            activeBatch = null;
        }
        recordDropped(discardedCount, discardedBytes);
    }

    static int tierOf(WireMessage message) {
        if (message instanceof WireMessage.SidebandFragment) {
            return TIER_BULK;
        }
        if (message instanceof WireMessage.ViewEntities entities && requiresReliableDelivery(entities)) {
            return TIER_BULK;
        }
        if (message instanceof WireMessage.Routed routed && routed.innerType() == WireMessageType.VIEW_ENTITIES) {
            return requiresReliableDelivery(routed) ? TIER_BULK : TIER_BEST_EFFORT;
        }
        WireMessageType type = message instanceof WireMessage.Routed routed ? routed.innerType() : message.type();
        return switch (type) {
            case VIEW_ENTITIES, VIEW_ENTITY_ANIMATION, CHUNK_HASH_PROBE -> TIER_BEST_EFFORT;
            case CHUNK_BULK, CHUNK_DIFF, VIEW_BULK_COMPLETE -> TIER_BULK;
            default -> TIER_CONTROL;
        };
    }

    static int tierOf(MinecraftStatusBridge.EncodedMessage message) {
        int tier = message.sidebandTier();
        if (tier < TIER_CONTROL || tier > TIER_BEST_EFFORT) {
            throw new IllegalArgumentException("invalid sideband tier " + tier);
        }
        return tier;
    }

    private static boolean requiresReliableDelivery(WireMessage.ViewEntities entities) {
        if (entities.entities().isEmpty()) {
            return true;
        }
        for (EntityVisual entity : entities.entities()) {
            if (entity.isFull()) {
                return true;
            }
        }
        return false;
    }

    private static boolean requiresReliableDelivery(WireMessage.Routed routed) {
        try {
            WireMessage decoded = WireCodec.decodePayload(WireMessageType.VIEW_ENTITIES, routed.payload());
            return !(decoded instanceof WireMessage.ViewEntities entities) || requiresReliableDelivery(entities);
        } catch (IOException | RuntimeException ignored) {
            return true;
        }
    }

    private boolean fits(int tier, long additionalBytes) {
        if (usedBytes() + additionalBytes > maxBytes) {
            return false;
        }
        return tier == TIER_CONTROL || nonControlUsedBytes() + additionalBytes <= nonControlLimitBytes;
    }

    private boolean fitsAfterSheddingQueuedBestEffort(int tier, long additionalBytes) {
        long reclaimableBytes = queuedBytesByTier[TIER_BEST_EFFORT];
        if (usedBytes() - reclaimableBytes + additionalBytes > maxBytes) {
            return false;
        }
        return tier == TIER_CONTROL
            || nonControlUsedBytes() - reclaimableBytes + additionalBytes <= nonControlLimitBytes;
    }

    private long usedBytes() {
        long total = queuedBytes;
        for (long bytes : inFlightBytesByTier) {
            total += bytes;
        }
        return total;
    }

    private long nonControlUsedBytes() {
        return queuedBytesByTier[TIER_BULK]
            + queuedBytesByTier[TIER_BEST_EFFORT]
            + inFlightBytesByTier[TIER_BULK]
            + inFlightBytesByTier[TIER_BEST_EFFORT];
    }

    private void shedOldestBestEffort() {
        MinecraftStatusBridge.EncodedMessage dropped = queues.get(TIER_BEST_EFFORT).removeFirst();
        int bytes = dropped.frame().length;
        queuedBytesByTier[TIER_BEST_EFFORT] -= bytes;
        queuedBytes -= bytes;
        queuedCount--;
        recordDropped(1L, bytes);
    }

    private void recordDropped(long count, long bytes) {
        droppedCount += count;
        droppedBytes += bytes;
    }

    private synchronized void commit(DrainBatch batch) {
        if (batch != null && batch.owner == this && batch.discarded) {
            return;
        }
        finish(batch);
    }

    private synchronized void requeue(DrainBatch batch) {
        if (batch != null && batch.owner == this && batch.discarded) {
            return;
        }
        validateBatch(batch);
        for (int tier = 0; tier < TIER_COUNT; tier++) {
            List<MinecraftStatusBridge.EncodedMessage> tierEntries = batch.messagesByTier.get(tier);
            ArrayDeque<MinecraftStatusBridge.EncodedMessage> queue = queues.get(tier);
            for (int i = tierEntries.size() - 1; i >= 0; i--) {
                queue.addFirst(tierEntries.get(i));
            }
            queuedBytesByTier[tier] += batch.bytesByTier[tier];
            queuedCount += tierEntries.size();
        }
        queuedBytes += batch.totalBytes;
        finish(batch);
    }

    private void finish(DrainBatch batch) {
        validateBatch(batch);
        if (batch.totalBytes > 0L && activeBatch != batch) {
            throw new IllegalStateException("drain batch is not active");
        }
        for (int tier = 0; tier < TIER_COUNT; tier++) {
            inFlightBytesByTier[tier] -= batch.bytesByTier[tier];
        }
        if (activeBatch == batch) {
            activeBatch = null;
        }
        batch.completed = true;
    }

    private void validateBatch(DrainBatch batch) {
        if (batch == null || batch.owner != this) {
            throw new IllegalArgumentException("drain batch belongs to another outbox");
        }
        if (batch.completed) {
            throw new IllegalStateException("drain batch is already completed");
        }
    }

    static final class DrainBatch {
        private final SidebandOutbox owner;
        private final List<MinecraftStatusBridge.EncodedMessage> messages;
        private final List<List<MinecraftStatusBridge.EncodedMessage>> messagesByTier;
        private final long[] bytesByTier;
        private final long totalBytes;
        private boolean completed;
        private boolean discarded;

        private DrainBatch(SidebandOutbox owner, List<MinecraftStatusBridge.EncodedMessage> messages,
                           List<List<MinecraftStatusBridge.EncodedMessage>> messagesByTier,
                           long[] bytesByTier, long totalBytes) {
            this.owner = owner;
            this.messages = List.copyOf(messages);
            this.messagesByTier = List.of(
                List.copyOf(messagesByTier.get(TIER_CONTROL)),
                List.copyOf(messagesByTier.get(TIER_BULK)),
                List.copyOf(messagesByTier.get(TIER_BEST_EFFORT))
            );
            this.bytesByTier = bytesByTier.clone();
            this.totalBytes = totalBytes;
        }

        private static DrainBatch empty(SidebandOutbox owner) {
            return new DrainBatch(
                owner,
                List.of(),
                List.of(List.of(), List.of(), List.of()),
                new long[TIER_COUNT],
                0L
            );
        }

        List<MinecraftStatusBridge.EncodedMessage> messages() {
            return messages;
        }

        void commit() {
            owner.commit(this);
        }

        void requeue() {
            owner.requeue(this);
        }
    }
}
