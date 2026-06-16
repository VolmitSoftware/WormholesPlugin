package art.arcane.wormholes.network.view;

import java.util.UUID;

public final class EntitySendState {
    private final UUID entityId;
    private EntityVisual lastSentSnapshot;
    private int nextSequence;
    private int lastAckedSequence;
    private int missCounter;
    private boolean forceFullNext;

    public EntitySendState(UUID entityId) {
        this.entityId = entityId;
        this.lastSentSnapshot = null;
        this.nextSequence = 0;
        this.lastAckedSequence = -1;
        this.missCounter = 0;
        this.forceFullNext = true;
    }

    public UUID getEntityId() {
        return entityId;
    }

    public EntityVisual getLastSentSnapshot() {
        return lastSentSnapshot;
    }

    public int getNextSequence() {
        return nextSequence;
    }

    public int getLastAckedSequence() {
        return lastAckedSequence;
    }

    public int getMissCounter() {
        return missCounter;
    }

    public boolean isForceFullNext() {
        return forceFullNext;
    }

    public int allocateSequence() {
        int allocated = nextSequence;
        nextSequence = (nextSequence + 1) & 0x7FFFFFFF;
        return allocated;
    }

    public void recordSent(EntityVisual fullSnapshot, boolean wasFullMode) {
        this.lastSentSnapshot = fullSnapshot;
        if (wasFullMode) {
            this.forceFullNext = false;
            this.missCounter = 0;
        }
    }

    public void recordAck(int acknowledgedSequence) {
        if (acknowledgedSequence > lastAckedSequence) {
            lastAckedSequence = acknowledgedSequence;
        }
        this.missCounter = 0;
    }

    public void recordMiss(int missesBeforeResync) {
        missCounter++;
        if (missCounter >= Math.max(1, missesBeforeResync)) {
            forceFullNext = true;
            missCounter = 0;
        }
    }

    public void requestFull() {
        this.forceFullNext = true;
    }

    public void reset() {
        this.lastSentSnapshot = null;
        this.nextSequence = 0;
        this.lastAckedSequence = -1;
        this.missCounter = 0;
        this.forceFullNext = true;
    }
}
