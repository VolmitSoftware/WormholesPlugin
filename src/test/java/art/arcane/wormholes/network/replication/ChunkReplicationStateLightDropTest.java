package art.arcane.wormholes.network.replication;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ChunkReplicationStateLightDropTest {
    private static byte[] data(byte fill) {
        byte[] data = new byte[LightDiff.DATA_LENGTH];
        java.util.Arrays.fill(data, fill);
        return data;
    }

    @Test
    void droppedSparseSectionForcesFullOnNextAppend() {
        ChunkReplicationState state = new ChunkReplicationState("peer", 1L);
        assertFalse(state.appendLight(LightDiff.pending(4, LightDiff.TYPE_BLOCKLIGHT, data((byte) 0x01), new int[]{5}), 0L));
        assertTrue(state.appendLight(LightDiff.pending(4, LightDiff.TYPE_BLOCKLIGHT, data((byte) 0x02), new int[]{9}), 16L));
        ChunkReplicationState.DrainResult drained = state.drain();
        assertEquals(1, drained.lights().size());
        LightDiff queued = drained.lights().get(0);
        assertNull(queued.sparseCells());
        assertEquals((byte) 0x02, queued.data()[0]);
    }

    @Test
    void droppedSectionDoesNotAffectOtherSectionsOrTypes() {
        ChunkReplicationState state = new ChunkReplicationState("peer", 1L);
        assertFalse(state.appendLight(LightDiff.pending(4, LightDiff.TYPE_BLOCKLIGHT, data((byte) 0x01), new int[]{5}), 0L));
        assertTrue(state.appendLight(LightDiff.pending(4, LightDiff.TYPE_SKYLIGHT, data((byte) 0x03), new int[]{7}), 16L));
        assertTrue(state.appendLight(LightDiff.pending(5, LightDiff.TYPE_BLOCKLIGHT, data((byte) 0x04), new int[]{8}), 16L));
        ChunkReplicationState.DrainResult drained = state.drain();
        assertEquals(2, drained.lights().size());
        assertArrayEquals(new int[]{7}, drained.lights().get(0).sparseCells());
        assertArrayEquals(new int[]{8}, drained.lights().get(1).sparseCells());
    }

    @Test
    void resetBulkClearsDroppedSectionTracking() {
        ChunkReplicationState state = new ChunkReplicationState("peer", 1L);
        assertFalse(state.appendLight(LightDiff.pending(4, LightDiff.TYPE_BLOCKLIGHT, data((byte) 0x01), new int[]{5}), 0L));
        state.resetBulk();
        assertTrue(state.appendLight(LightDiff.pending(4, LightDiff.TYPE_BLOCKLIGHT, data((byte) 0x02), new int[]{9}), 16L));
        ChunkReplicationState.DrainResult drained = state.drain();
        assertEquals(1, drained.lights().size());
        assertArrayEquals(new int[]{9}, drained.lights().get(0).sparseCells());
    }
}
