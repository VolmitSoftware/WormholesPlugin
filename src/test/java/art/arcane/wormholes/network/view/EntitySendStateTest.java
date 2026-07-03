package art.arcane.wormholes.network.view;

import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class EntitySendStateTest {
    private static EntityVisual snapshot(UUID id, int sequence) {
        return EntityVisual.full(
            id,
            "minecraft:zombie",
            10.0D, 64.0D, 20.0D,
            1.95D,
            0.0D, 0.0D, 1.0D,
            90.0F, 0.0F,
            0.0D, 0.0D, 0.0D,
            true,
            "",
            "",
            "",
            null,
            null,
            new byte[]{1, 2, 3},
            new byte[]{4, 5, 6},
            sequence
        );
    }

    @Test
    void newStateStartsForceFull() {
        EntitySendState state = new EntitySendState(new UUID(1L, 1L));
        assertTrue(state.isForceFullNext());
        assertNull(state.getLastSentSnapshot());
        assertEquals(0L, state.getLastFullSentTick());
        assertEquals(0L, state.getNextEligibleTick());
    }

    @Test
    void recordSentFullClearsForceFullAndStampsTick() {
        UUID id = new UUID(2L, 2L);
        EntitySendState state = new EntitySendState(id);
        state.recordSent(snapshot(id, 0), true, 42L);
        assertFalse(state.isForceFullNext());
        assertEquals(42L, state.getLastFullSentTick());
    }

    @Test
    void recordSentDeltaDoesNotStampFullTick() {
        UUID id = new UUID(3L, 3L);
        EntitySendState state = new EntitySendState(id);
        state.recordSent(snapshot(id, 0), true, 42L);
        state.recordSent(snapshot(id, 1), false, 99L);
        assertEquals(42L, state.getLastFullSentTick());
        assertFalse(state.isForceFullNext());
    }

    @Test
    void requestFullSetsForceFullNext() {
        UUID id = new UUID(4L, 4L);
        EntitySendState state = new EntitySendState(id);
        state.recordSent(snapshot(id, 0), true, 10L);
        assertFalse(state.isForceFullNext());
        state.requestFull();
        assertTrue(state.isForceFullNext());
    }

    @Test
    void sidebandFullDueAfterIntervalPlusJitter() {
        UUID id = new UUID(0L, 0L);
        assertEquals(0, id.hashCode());
        EntitySendState state = new EntitySendState(id);
        state.recordSent(snapshot(id, 0), true, 100L);
        assertFalse(state.isSidebandFullDue(299L, 200L, 80L));
        assertTrue(state.isSidebandFullDue(300L, 200L, 80L));
        assertTrue(state.isSidebandFullDue(301L, 200L, 80L));
    }

    @Test
    void resetClearsSnapshotAndSequenceAndFullTick() {
        UUID id = new UUID(5L, 5L);
        EntitySendState state = new EntitySendState(id);
        state.recordSent(snapshot(id, 0), true, 42L);
        state.setNextEligibleTick(77L);
        state.allocateSequence();
        state.reset();
        assertNull(state.getLastSentSnapshot());
        assertEquals(0, state.getNextSequence());
        assertTrue(state.isForceFullNext());
        assertEquals(0L, state.getLastFullSentTick());
        assertEquals(0L, state.getNextEligibleTick());
    }
}
