package art.arcane.wormholes.network.replication;

import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

public final class ChunkReplicationState {
    private final String peerName;
    private final long chunkKey;
    private final AtomicLong lastBroadcastSeq = new AtomicLong(0L);
    private final AtomicLong lastAcked = new AtomicLong(0L);
    private final AtomicLong bulkGeneration = new AtomicLong(0L);
    private final AtomicBoolean bulkSent = new AtomicBoolean(false);
    private final ConcurrentLinkedQueue<BlockChange> pendingBlocks = new ConcurrentLinkedQueue<>();
    private final ConcurrentLinkedQueue<LightDiff> pendingLights = new ConcurrentLinkedQueue<>();
    private final ConcurrentLinkedQueue<BlockEntityDiff> pendingEntities = new ConcurrentLinkedQueue<>();
    private final AtomicLong queuedDiffCount = new AtomicLong(0L);
    private final Set<Integer> droppedLightSections = ConcurrentHashMap.newKeySet();

    public ChunkReplicationState(String peerName, long chunkKey) {
        this.peerName = peerName;
        this.chunkKey = chunkKey;
    }

    public String peerName() {
        return peerName;
    }

    public long chunkKey() {
        return chunkKey;
    }

    public long lastBroadcastSeq() {
        return lastBroadcastSeq.get();
    }

    public long nextBroadcastSeq() {
        return lastBroadcastSeq.incrementAndGet();
    }

    public long lastAcked() {
        return lastAcked.get();
    }

    public void recordAcked(long sequence) {
        long current;
        do {
            current = lastAcked.get();
            if (sequence <= current) {
                return;
            }
        } while (!lastAcked.compareAndSet(current, sequence));
    }

    public boolean markBulkSent() {
        return bulkSent.compareAndSet(false, true);
    }

    public long bulkGeneration() {
        return bulkGeneration.get();
    }

    public void resetBulk() {
        bulkGeneration.incrementAndGet();
        bulkSent.set(false);
        // Do NOT reset lastBroadcastSeq to 0: a re-bulk must get a strictly increasing sequence so the
        // receiver can order it ahead of (and not collide with) diffs already in flight. Resetting it
        // made every re-bulk reuse seq=1, which clobbered newer diffs and dropped block changes.
        lastAcked.set(0L);
        pendingBlocks.clear();
        pendingLights.clear();
        pendingEntities.clear();
        queuedDiffCount.set(0L);
        droppedLightSections.clear();
    }

    public boolean isBulkSent() {
        return bulkSent.get();
    }

    public boolean appendBlock(BlockChange change, long capacity) {
        if (queuedDiffCount.get() >= capacity) {
            return false;
        }
        pendingBlocks.offer(change);
        queuedDiffCount.incrementAndGet();
        return true;
    }

    public boolean appendLight(LightDiff diff, long capacity) {
        int sectionKey = (diff.sectionY() << 1) | (diff.lightType() & 1);
        if (queuedDiffCount.get() >= capacity) {
            droppedLightSections.add(sectionKey);
            return false;
        }
        LightDiff queued = diff;
        if (droppedLightSections.remove(sectionKey) && diff.sparseCells() != null) {
            queued = LightDiff.pending(diff.sectionY(), diff.lightType(), diff.data(), null);
        }
        pendingLights.offer(queued);
        queuedDiffCount.incrementAndGet();
        return true;
    }

    public boolean appendBlockEntity(BlockEntityDiff diff, long capacity) {
        if (queuedDiffCount.get() >= capacity) {
            return false;
        }
        pendingEntities.offer(diff);
        queuedDiffCount.incrementAndGet();
        return true;
    }

    public long queuedDiffCount() {
        return queuedDiffCount.get();
    }

    public boolean hasPendingBlocks() {
        return !pendingBlocks.isEmpty();
    }

    public DrainResult drain() {
        java.util.ArrayList<BlockChange> blocks = new java.util.ArrayList<>();
        java.util.ArrayList<LightDiff> lights = new java.util.ArrayList<>();
        java.util.ArrayList<BlockEntityDiff> entities = new java.util.ArrayList<>();
        BlockChange block;
        while ((block = pendingBlocks.poll()) != null) {
            blocks.add(block);
            queuedDiffCount.decrementAndGet();
        }
        LightDiff light;
        while ((light = pendingLights.poll()) != null) {
            lights.add(light);
            queuedDiffCount.decrementAndGet();
        }
        BlockEntityDiff entity;
        while ((entity = pendingEntities.poll()) != null) {
            entities.add(entity);
            queuedDiffCount.decrementAndGet();
        }
        return new DrainResult(blocks, lights, entities);
    }

    public record DrainResult(java.util.List<BlockChange> blocks, java.util.List<LightDiff> lights, java.util.List<BlockEntityDiff> entities) {
        public boolean isEmpty() {
            return blocks.isEmpty() && lights.isEmpty() && entities.isEmpty();
        }
    }
}
