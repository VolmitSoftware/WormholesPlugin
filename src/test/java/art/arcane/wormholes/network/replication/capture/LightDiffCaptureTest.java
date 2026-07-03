package art.arcane.wormholes.network.replication.capture;

import art.arcane.wormholes.network.replication.LightDiff;

import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LightDiffCaptureTest {
    @Test
    void chunkLightShadowDetectsChange() {
        ChunkLightShadow shadow = new ChunkLightShadow();
        byte[] sectionData = new byte[LightDiff.DATA_LENGTH];
        Arrays.fill(sectionData, (byte) 0x00);
        shadow.putBlockLight(4, sectionData);
        byte[] previous = shadow.getBlockLight(4);
        assertNotNull(previous);

        byte[] mutated = sectionData.clone();
        mutated[42] = (byte) 0xFF;
        assertFalse(Arrays.equals(previous, mutated));
        shadow.putBlockLight(4, mutated);
        byte[] post = shadow.getBlockLight(4);
        assertTrue(Arrays.equals(mutated, post));
    }

    @Test
    void chunkLightShadowIsolatesPerSection() {
        ChunkLightShadow shadow = new ChunkLightShadow();
        byte[] a = new byte[LightDiff.DATA_LENGTH];
        byte[] b = new byte[LightDiff.DATA_LENGTH];
        Arrays.fill(a, (byte) 0x11);
        Arrays.fill(b, (byte) 0x22);
        shadow.putBlockLight(1, a);
        shadow.putSkyLight(1, b);
        assertTrue(Arrays.equals(a, shadow.getBlockLight(1)));
        assertTrue(Arrays.equals(b, shadow.getSkyLight(1)));
    }

    @Test
    void chunkLightShadowReturnsNullForUnknown() {
        ChunkLightShadow shadow = new ChunkLightShadow();
        assertNull(shadow.getBlockLight(99));
    }

    @Test
    void tryBeginSampleEnforcesCooldownWindow() {
        ChunkLightShadow shadow = new ChunkLightShadow();
        assertTrue(shadow.tryBeginSample(0L, 250L));
        assertFalse(shadow.tryBeginSample(100L, 250L));
        assertTrue(shadow.tryBeginSample(250L, 250L));
    }

    @Test
    void deferredSectionsDrainExactlyOnceAfterCooldown() {
        ChunkLightShadow shadow = new ChunkLightShadow();
        shadow.deferSections(Set.of(4, 5));
        Set<Integer> drained = shadow.drainPendingSections();
        assertEquals(Set.of(4, 5), drained);
        assertTrue(shadow.drainPendingSections().isEmpty());
    }

    @Test
    void pendingFlagClearsAfterDrain() {
        ChunkLightShadow shadow = new ChunkLightShadow();
        assertFalse(shadow.hasPending());
        shadow.deferSections(Set.of(7));
        assertTrue(shadow.hasPending());
        shadow.drainPendingSections();
        assertFalse(shadow.hasPending());
    }

    @Test
    void changedCellsReturnsExactNibbleIndices() {
        byte[] previous = new byte[LightDiff.DATA_LENGTH];
        byte[] next = previous.clone();
        next[0] = (byte) 0x0F;
        next[10] = (byte) 0xF0;
        next[20] = (byte) 0xFF;
        int[] cells = LightDiffCapture.changedCells(previous, next);
        assertArrayEquals(new int[]{0, 21, 40, 41}, cells);
    }

    @Test
    void changedCellsReturnsEmptyForEqualArrays() {
        byte[] data = new byte[LightDiff.DATA_LENGTH];
        Arrays.fill(data, (byte) 0x37);
        assertEquals(0, LightDiffCapture.changedCells(data, data.clone()).length);
    }

    @Test
    void applyingChangedCellsOntoPreviousReproducesNext() {
        java.util.Random random = new java.util.Random(77L);
        for (int round = 0; round < 100; round++) {
            byte[] previous = new byte[LightDiff.DATA_LENGTH];
            random.nextBytes(previous);
            byte[] next = previous.clone();
            int mutations = 1 + random.nextInt(64);
            for (int m = 0; m < mutations; m++) {
                next[random.nextInt(next.length)] = (byte) random.nextInt(256);
            }
            int[] cells = LightDiffCapture.changedCells(previous, next);
            byte[] reconstructed = previous.clone();
            for (int i = 0; i < cells.length; i++) {
                int cell = cells[i];
                int level = (cell & 1) == 0 ? (next[cell >> 1] & 0x0F) : ((next[cell >> 1] >> 4) & 0x0F);
                if ((cell & 1) == 0) {
                    reconstructed[cell >> 1] = (byte) ((reconstructed[cell >> 1] & 0xF0) | level);
                } else {
                    reconstructed[cell >> 1] = (byte) ((reconstructed[cell >> 1] & 0x0F) | (level << 4));
                }
            }
            assertArrayEquals(next, reconstructed, "round " + round);
        }
    }

    @Test
    void firstEmissionWithoutShadowIsFull() {
        byte[] sampled = new byte[LightDiff.DATA_LENGTH];
        sampled[3] = (byte) 0x21;
        assertNull(LightDiffCapture.emissionCells(null, sampled));
    }

    @Test
    void emissionExceedingSparseCapIsFull() {
        byte[] previous = new byte[LightDiff.DATA_LENGTH];
        byte[] next = new byte[LightDiff.DATA_LENGTH];
        Arrays.fill(next, (byte) 0xFF);
        assertNull(LightDiffCapture.emissionCells(previous, next));
    }

    @Test
    void smallEmissionStaysSparse() {
        byte[] previous = new byte[LightDiff.DATA_LENGTH];
        byte[] next = previous.clone();
        next[42] = (byte) 0x05;
        int[] cells = LightDiffCapture.emissionCells(previous, next);
        assertNotNull(cells);
        assertArrayEquals(new int[]{84}, cells);
    }

    @Test
    void sixteenthEmissionCounterForcesFull() {
        ChunkLightShadow shadow = new ChunkLightShadow();
        for (int emission = 0; emission < 15; emission++) {
            assertTrue(shadow.nextEmitCounter(4, LightDiff.TYPE_BLOCKLIGHT) < 15);
        }
        assertEquals(15, shadow.nextEmitCounter(4, LightDiff.TYPE_BLOCKLIGHT));
        assertEquals(0, shadow.nextEmitCounter(4, LightDiff.TYPE_BLOCKLIGHT));
        assertEquals(0, shadow.nextEmitCounter(4, LightDiff.TYPE_SKYLIGHT));
        assertEquals(0, shadow.nextEmitCounter(5, LightDiff.TYPE_BLOCKLIGHT));
    }
}
