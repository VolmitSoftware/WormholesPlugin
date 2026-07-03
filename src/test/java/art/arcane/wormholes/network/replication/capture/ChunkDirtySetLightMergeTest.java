package art.arcane.wormholes.network.replication.capture;

import art.arcane.wormholes.network.replication.LightDiff;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;

class ChunkDirtySetLightMergeTest {
    private static byte[] data(byte fill) {
        byte[] data = new byte[LightDiff.DATA_LENGTH];
        java.util.Arrays.fill(data, fill);
        return data;
    }

    private static LightDiff drainSingleLight(ChunkDirtySet set) {
        ChunkDirtySet.Drain drained = set.drainAll();
        assertEquals(1, drained.lights().size());
        return drained.lights().get(0);
    }

    @Test
    void twoSparsePutsForSameSectionUnionCellsAndKeepNewestData() {
        ChunkDirtySet set = new ChunkDirtySet(1L);
        byte[] first = data((byte) 0x11);
        byte[] second = data((byte) 0x22);
        set.putBlockLight(LightDiff.pending(4, LightDiff.TYPE_BLOCKLIGHT, first, new int[]{2, 9, 30}));
        set.putBlockLight(LightDiff.pending(4, LightDiff.TYPE_BLOCKLIGHT, second, new int[]{1, 9, 44}));
        LightDiff merged = drainSingleLight(set);
        assertArrayEquals(new int[]{1, 2, 9, 30, 44}, merged.sparseCells());
        assertSame(second, merged.data());
    }

    @Test
    void mergeWithExistingFullEntryStaysFull() {
        ChunkDirtySet set = new ChunkDirtySet(1L);
        set.putSkyLight(LightDiff.pending(2, LightDiff.TYPE_SKYLIGHT, data((byte) 0x01), null));
        set.putSkyLight(LightDiff.pending(2, LightDiff.TYPE_SKYLIGHT, data((byte) 0x02), new int[]{5}));
        LightDiff merged = drainSingleLight(set);
        assertNull(merged.sparseCells());
        assertEquals((byte) 0x02, merged.data()[0]);
    }

    @Test
    void incomingFullReplacesSparseEntry() {
        ChunkDirtySet set = new ChunkDirtySet(1L);
        set.putBlockLight(LightDiff.pending(3, LightDiff.TYPE_BLOCKLIGHT, data((byte) 0x0A), new int[]{7, 8}));
        set.putBlockLight(LightDiff.pending(3, LightDiff.TYPE_BLOCKLIGHT, data((byte) 0x0B), null));
        LightDiff merged = drainSingleLight(set);
        assertNull(merged.sparseCells());
        assertEquals((byte) 0x0B, merged.data()[0]);
    }

    @Test
    void unionExceedingSparseCapCollapsesToFull() {
        ChunkDirtySet set = new ChunkDirtySet(1L);
        int[] firstCells = new int[LightDiff.SPARSE_MAX_CELLS];
        int[] secondCells = new int[LightDiff.SPARSE_MAX_CELLS];
        for (int i = 0; i < LightDiff.SPARSE_MAX_CELLS; i++) {
            firstCells[i] = i * 2;
            secondCells[i] = i * 2 + 1;
        }
        set.putBlockLight(LightDiff.pending(0, LightDiff.TYPE_BLOCKLIGHT, data((byte) 0x01), firstCells));
        set.putBlockLight(LightDiff.pending(0, LightDiff.TYPE_BLOCKLIGHT, data((byte) 0x02), secondCells));
        LightDiff merged = drainSingleLight(set);
        assertNull(merged.sparseCells());
    }

    @Test
    void blockAndSkySectionsAreIsolated() {
        ChunkDirtySet set = new ChunkDirtySet(1L);
        set.putBlockLight(LightDiff.pending(1, LightDiff.TYPE_BLOCKLIGHT, data((byte) 0x01), new int[]{3}));
        set.putSkyLight(LightDiff.pending(1, LightDiff.TYPE_SKYLIGHT, data((byte) 0x02), new int[]{4}));
        ChunkDirtySet.Drain drained = set.drainAll();
        assertEquals(2, drained.lights().size());
    }
}
