package art.arcane.wormholes.network.replication;

import art.arcane.wormholes.network.view.ViewSlice;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RemoteChunkStoreTest {
    @Test
    void applyBulkInstallsSlice() throws IOException {
        RemoteChunkStore store = new RemoteChunkStore();
        byte[] payload = synthesizeBulkPayload(0, 0);
        long chunkKey = ViewSlice.columnKey(0, 0);
        RemoteChunkStore.ReplicatedChunk chunk = store.applyBulk(new ChunkBulk(chunkKey, 1L, payload));
        assertNotNull(chunk);
        assertNotNull(chunk.slice());
        assertEquals(1L, chunk.lastAppliedSeq());
    }

    @Test
    void applyInOrderDiffUpdatesState() throws IOException {
        RemoteChunkStore store = new RemoteChunkStore();
        long chunkKey = ViewSlice.columnKey(0, 0);
        store.applyBulk(new ChunkBulk(chunkKey, 1L, synthesizeBulkPayload(0, 0)));
        ChunkDiffBatch batch = new ChunkDiffBatch(chunkKey, 2L,
            List.of(new BlockChange(BlockChange.pack(0, 60, 0), "minecraft:dirt", BlockChange.FLAG_NONE)),
            List.<LightDiff>of(),
            List.<BlockEntityDiff>of());
        RemoteChunkStore.ApplyOutcome outcome = store.applyDiff(batch);
        assertTrue(outcome.applied());
        assertFalse(outcome.resyncRequested());
        assertEquals(2L, store.get(chunkKey).lastAppliedSeq());
    }

    @Test
    void diffIntroducingNewBlockExtendsPaletteAndResolvesToThatState() throws IOException {
        RemoteChunkStore store = new RemoteChunkStore();
        long chunkKey = ViewSlice.columnKey(0, 0);
        store.applyBulk(new ChunkBulk(chunkKey, 1L, synthesizeBulkPayload(0, 0)));
        String fence = "minecraft:oak_fence[east=false,north=true,south=false,waterlogged=false,west=false]";
        int packed = BlockChange.pack(3, 60, 5);
        ChunkDiffBatch batch = new ChunkDiffBatch(chunkKey, 2L,
            List.of(new BlockChange(packed, fence, BlockChange.FLAG_NONE)),
            List.<LightDiff>of(),
            List.<BlockEntityDiff>of());
        assertTrue(store.applyDiff(batch).applied());
        ViewSlice slice = store.get(chunkKey).slice();
        int cellIndex = slice.cellIndex((0 << 4) + 3, 60, (0 << 4) + 5);
        int paletteIndex = slice.indices()[cellIndex] & 0xFFFF;
        assertEquals(fence, slice.palette().get(paletteIndex),
            "a diff carrying a block state absent from the bulk palette must extend the palette, not corrupt to a foreign id");
    }

    @Test
    void reBulkPreservesBufferedNewerDiff() throws IOException {
        RemoteChunkStore store = new RemoteChunkStore();
        long chunkKey = ViewSlice.columnKey(0, 0);
        store.applyBulk(new ChunkBulk(chunkKey, 1L, synthesizeBulkPayload(0, 0)));
        String air = "minecraft:air";
        int packed = BlockChange.pack(3, 60, 5);
        // The break (seq 3) outruns its predecessor (gap at seq 2) and is buffered.
        store.applyDiff(new ChunkDiffBatch(chunkKey, 3L,
            List.of(new BlockChange(packed, air, BlockChange.FLAG_NONE)),
            List.<LightDiff>of(), List.<BlockEntityDiff>of()));
        // A re-bulk (seq 2) carrying a STALE (pre-break) snapshot must NOT drop the buffered break;
        // it must be re-applied on top so the cell ends at AIR.
        store.applyBulk(new ChunkBulk(chunkKey, 2L, synthesizeBulkPayload(0, 0)));
        ViewSlice slice = store.get(chunkKey).slice();
        int cellIndex = slice.cellIndex(3, 60, 5);
        int paletteIndex = slice.indices()[cellIndex] & 0xFFFF;
        assertEquals(air, slice.palette().get(paletteIndex),
            "a buffered newer break diff must survive a re-bulk (be re-applied), not be cleared");
    }

    @Test
    void applyOutOfOrderDiffIsBuffered() throws IOException {
        RemoteChunkStore store = new RemoteChunkStore();
        long chunkKey = ViewSlice.columnKey(0, 0);
        store.applyBulk(new ChunkBulk(chunkKey, 1L, synthesizeBulkPayload(0, 0)));
        ChunkDiffBatch outOfOrder = new ChunkDiffBatch(chunkKey, 5L,
            List.of(new BlockChange(BlockChange.pack(1, 60, 1), "minecraft:dirt", BlockChange.FLAG_NONE)),
            List.<LightDiff>of(),
            List.<BlockEntityDiff>of());
        RemoteChunkStore.ApplyOutcome outcome = store.applyDiff(outOfOrder);
        assertTrue(outcome.applied());
        assertFalse(outcome.resyncRequested());
        assertEquals(1L, store.get(chunkKey).lastAppliedSeq());
        ChunkDiffBatch nextExpected = new ChunkDiffBatch(chunkKey, 2L,
            List.of(new BlockChange(BlockChange.pack(2, 60, 2), "minecraft:dirt", BlockChange.FLAG_NONE)),
            List.<LightDiff>of(),
            List.<BlockEntityDiff>of());
        store.applyDiff(nextExpected);
        assertEquals(2L, store.get(chunkKey).lastAppliedSeq());
    }

    @Test
    void gapExceedingWindowRequestsResync() throws IOException {
        RemoteChunkStore store = new RemoteChunkStore(2, 1_000L);
        long chunkKey = ViewSlice.columnKey(0, 0);
        store.applyBulk(new ChunkBulk(chunkKey, 1L, synthesizeBulkPayload(0, 0)));
        store.applyDiff(diff(chunkKey, 5L));
        store.applyDiff(diff(chunkKey, 6L));
        RemoteChunkStore.ApplyOutcome overflow = store.applyDiff(diff(chunkKey, 7L));
        assertTrue(overflow.resyncRequested());
    }

    @Test
    void timeoutOnBufferedGapEmitsResyncRequest() throws IOException {
        RemoteChunkStore store = new RemoteChunkStore(32, 50L);
        long chunkKey = ViewSlice.columnKey(0, 0);
        store.applyBulk(new ChunkBulk(chunkKey, 1L, synthesizeBulkPayload(0, 0)));
        store.applyDiff(diff(chunkKey, 5L));
        try {
            Thread.sleep(100L);
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
        }
        List<ChunkResyncRequest> requests = store.collectTimeouts(System.currentTimeMillis());
        assertEquals(1, requests.size());
        assertEquals(chunkKey, requests.get(0).chunkKey());
    }

    @Test
    void diffForUnknownChunkSignalsResync() {
        RemoteChunkStore store = new RemoteChunkStore();
        long chunkKey = ViewSlice.columnKey(7, 7);
        RemoteChunkStore.ApplyOutcome outcome = store.applyDiff(diff(chunkKey, 2L));
        assertFalse(outcome.applied());
        assertTrue(outcome.resyncRequested());
    }

    @Test
    void mismatchesIncludeUnknownChunks() {
        RemoteChunkStore store = new RemoteChunkStore();
        long chunkKey = ViewSlice.columnKey(3, 4);
        List<Long> mismatches = store.mismatches(List.of(new ChunkHashProbe.ChunkHashEntry(chunkKey, 1L, 1234L)));
        assertEquals(1, mismatches.size());
        assertEquals(chunkKey, mismatches.get(0).longValue());
    }

    @Test
    void hashAtReflectsLatestPayload() throws IOException {
        RemoteChunkStore store = new RemoteChunkStore();
        long chunkKey = ViewSlice.columnKey(0, 0);
        byte[] payload = synthesizeBulkPayload(0, 0);
        store.applyBulk(new ChunkBulk(chunkKey, 1L, payload));
        long expected = contentHashOf(payload);
        assertEquals(expected, store.hashAt(chunkKey));
    }

    private static long contentHashOf(byte[] payload) {
        try {
            return ViewSlice.read(new java.io.DataInputStream(new java.io.ByteArrayInputStream(payload))).contentHash();
        } catch (IOException ex) {
            throw new AssertionError(ex);
        }
    }

    @Test
    void sparseLightDiffPatchesOnlyListedCells() throws IOException {
        RemoteChunkStore store = new RemoteChunkStore();
        long chunkKey = ViewSlice.columnKey(0, 0);
        store.applyBulk(new ChunkBulk(chunkKey, 1L, synthesizeBulkPayload(0, 0)));
        int cellA = (0 << 8) | (2 << 4) | 3;
        int cellB = (0 << 8) | (6 << 4) | 4;
        int[] cells = {cellA, cellB};
        byte[] levels = {(byte) ((9 << 4) | 5)};
        ChunkDiffBatch skyBatch = new ChunkDiffBatch(chunkKey, 2L,
            List.<BlockChange>of(),
            List.of(LightDiff.sparse(4, LightDiff.TYPE_SKYLIGHT, cells, levels)),
            List.<BlockEntityDiff>of());
        assertTrue(store.applyDiff(skyBatch).applied());
        ViewSlice slice = store.get(chunkKey).slice();
        assertEquals(0x50, slice.light()[slice.cellIndex(3, 64, 2)] & 0xFF);
        assertEquals(0x90, slice.light()[slice.cellIndex(4, 64, 6)] & 0xFF);
        int nonZero = 0;
        for (byte level : slice.light()) {
            if (level != 0) {
                nonZero++;
            }
        }
        assertEquals(2, nonZero);

        ChunkDiffBatch blockBatch = new ChunkDiffBatch(chunkKey, 3L,
            List.<BlockChange>of(),
            List.of(LightDiff.sparse(4, LightDiff.TYPE_BLOCKLIGHT, new int[]{cellA}, new byte[]{0x07})),
            List.<BlockEntityDiff>of());
        assertTrue(store.applyDiff(blockBatch).applied());
        assertEquals(0x57, slice.light()[slice.cellIndex(3, 64, 2)] & 0xFF,
            "block-light sparse patch must preserve the sky nibble");
    }

    @Test
    void sparseLightDiffForOutOfRangeSectionIsNoOp() throws IOException {
        RemoteChunkStore store = new RemoteChunkStore();
        long chunkKey = ViewSlice.columnKey(0, 0);
        store.applyBulk(new ChunkBulk(chunkKey, 1L, synthesizeBulkPayload(0, 0)));
        ChunkDiffBatch batch = new ChunkDiffBatch(chunkKey, 2L,
            List.<BlockChange>of(),
            List.of(LightDiff.sparse(20, LightDiff.TYPE_SKYLIGHT, new int[]{0}, new byte[]{0x0F})),
            List.<BlockEntityDiff>of());
        assertTrue(store.applyDiff(batch).applied());
        ViewSlice slice = store.get(chunkKey).slice();
        for (byte level : slice.light()) {
            assertEquals(0, level);
        }
    }

    @Test
    void fullLightDiffAppliesOnlyToOverlappingRows() throws IOException {
        RemoteChunkStore store = new RemoteChunkStore();
        long chunkKey = ViewSlice.columnKey(0, 0);
        store.applyBulk(new ChunkBulk(chunkKey, 1L, synthesizeBulkPayload(0, 0)));
        byte[] data = new byte[LightDiff.DATA_LENGTH];
        java.util.Arrays.fill(data, (byte) 0xBA);
        ChunkDiffBatch batch = new ChunkDiffBatch(chunkKey, 2L,
            List.<BlockChange>of(),
            List.of(LightDiff.full(3, LightDiff.TYPE_BLOCKLIGHT, data)),
            List.<BlockEntityDiff>of());
        assertTrue(store.applyDiff(batch).applied());
        ViewSlice slice = store.get(chunkKey).slice();
        for (int y = 60; y < 84; y++) {
            for (int z = 0; z < 16; z++) {
                for (int x = 0; x < 16; x++) {
                    int actual = slice.light()[slice.cellIndex(x, y, z)] & 0xFF;
                    if (y <= 63) {
                        int sourceCell = ((y - 48) << 8) | (z << 4) | x;
                        int expected = (sourceCell & 1) == 0 ? 0x0A : 0x0B;
                        assertEquals(expected, actual, "cell " + x + "," + y + "," + z);
                    } else {
                        assertEquals(0, actual, "cell " + x + "," + y + "," + z);
                    }
                }
            }
        }
    }

    @Test
    void hashAtCacheInvalidatesOnBlockDiff() throws IOException {
        RemoteChunkStore store = new RemoteChunkStore();
        long chunkKey = ViewSlice.columnKey(0, 0);
        store.applyBulk(new ChunkBulk(chunkKey, 1L, synthesizeBulkPayload(0, 0)));
        long before = store.hashAt(chunkKey);
        assertEquals(before, store.hashAt(chunkKey));
        ChunkDiffBatch batch = new ChunkDiffBatch(chunkKey, 2L,
            List.of(new BlockChange(BlockChange.pack(1, 61, 1), "minecraft:oak_planks", BlockChange.FLAG_NONE)),
            List.<LightDiff>of(),
            List.<BlockEntityDiff>of());
        assertTrue(store.applyDiff(batch).applied());
        long after = store.hashAt(chunkKey);
        assertTrue(before != after, "content hash must change after an applied block diff");
        assertEquals(store.get(chunkKey).slice().contentHash(), after);
    }

    @Test
    void lightOnlyDiffKeepsHashAtAndProbeMatch() throws IOException {
        RemoteChunkStore store = new RemoteChunkStore();
        long chunkKey = ViewSlice.columnKey(0, 0);
        store.applyBulk(new ChunkBulk(chunkKey, 1L, synthesizeBulkPayload(0, 0)));
        long before = store.hashAt(chunkKey);
        byte[] litData = new byte[LightDiff.DATA_LENGTH];
        java.util.Arrays.fill(litData, (byte) 0x77);
        ChunkDiffBatch batch = new ChunkDiffBatch(chunkKey, 2L,
            List.<BlockChange>of(),
            List.of(LightDiff.full(4, LightDiff.TYPE_SKYLIGHT, litData)),
            List.<BlockEntityDiff>of());
        assertTrue(store.applyDiff(batch).applied());
        assertEquals(before, store.hashAt(chunkKey));
        assertTrue(store.mismatches(List.of(new ChunkHashProbe.ChunkHashEntry(chunkKey, 2L, before))).isEmpty());
    }

    @Test
    void removeWipesChunk() throws IOException {
        RemoteChunkStore store = new RemoteChunkStore();
        long chunkKey = ViewSlice.columnKey(0, 0);
        store.applyBulk(new ChunkBulk(chunkKey, 1L, synthesizeBulkPayload(0, 0)));
        store.remove(chunkKey);
        assertNull(store.get(chunkKey));
    }

    private static ChunkDiffBatch diff(long chunkKey, long sequence) {
        return new ChunkDiffBatch(chunkKey, sequence,
            List.of(new BlockChange(BlockChange.pack(0, 60, 0), "minecraft:dirt", BlockChange.FLAG_NONE)),
            List.<LightDiff>of(),
            List.<BlockEntityDiff>of());
    }

    private static byte[] synthesizeBulkPayload(int chunkX, int chunkZ) {
        int sizeX = 16;
        int sizeY = 24;
        int sizeZ = 16;
        int cells = sizeX * sizeY * sizeZ;
        int minX = chunkX << 4;
        int minZ = chunkZ << 4;
        short[] indices = new short[cells];
        byte[] light = new byte[cells];
        short[] biomes = new short[ViewSlice.biomeGridSpan(minX, sizeX) * ViewSlice.biomeGridSpan(60, sizeY) * ViewSlice.biomeGridSpan(minZ, sizeZ)];
        List<String> palette = List.of("minecraft:stone", "minecraft:dirt");
        List<String> biomePalette = List.of("minecraft:plains");
        ViewSlice slice = new ViewSlice(minX, 60, minZ, sizeX, sizeY, sizeZ, palette, indices, light, biomePalette, biomes);
        try {
            return ChunkBulkBuilder.encodeSliceBytes(slice);
        } catch (IOException ex) {
            throw new AssertionError(ex);
        }
    }
}
