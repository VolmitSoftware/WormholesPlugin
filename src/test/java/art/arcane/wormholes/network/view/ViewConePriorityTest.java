package art.arcane.wormholes.network.view;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ViewConePriorityTest {
    private static final double DELTA = 1.0E-9D;

    @Test
    void sliceDirectlyAheadGetsFullPriority() {
        double factor = ViewBox.conePriorityFactor(
            10.0D, 64.0D, 0.0D,
            0.0D, 64.0D, 0.0D,
            1.0D, 0.0D, 0.0D,
            60.0D, 0.4D);
        assertEquals(1.0D, factor, DELTA);
    }

    @Test
    void sliceDirectlyBehindGetsBehindFactor() {
        double factor = ViewBox.conePriorityFactor(
            -10.0D, 64.0D, 0.0D,
            0.0D, 64.0D, 0.0D,
            1.0D, 0.0D, 0.0D,
            60.0D, 0.4D);
        assertEquals(0.4D, factor, DELTA);
    }

    @Test
    void sliceNinetyDegreesToSideGetsBehindFactor() {
        double factor = ViewBox.conePriorityFactor(
            0.0D, 64.0D, 10.0D,
            0.0D, 64.0D, 0.0D,
            1.0D, 0.0D, 0.0D,
            60.0D, 0.4D);
        assertEquals(0.4D, factor, DELTA);
    }

    @Test
    void sliceJustOutsideConeUsesBehindFactor() {
        double factor = ViewBox.conePriorityFactor(
            5.0D, 64.0D, 5.0D,
            0.0D, 64.0D, 0.0D,
            1.0D, 0.0D, 0.0D,
            60.0D, 0.4D);
        assertTrue(factor >= 0.4D - DELTA && factor <= 0.4D + DELTA,
            "slice just outside cone should map to behindFactor, got " + factor);
    }

    @Test
    void zeroDistanceSliceGetsFullPriority() {
        double factor = ViewBox.conePriorityFactor(
            0.0D, 64.0D, 0.0D,
            0.0D, 64.0D, 0.0D,
            1.0D, 0.0D, 0.0D,
            60.0D, 0.4D);
        assertEquals(1.0D, factor, DELTA);
    }

    @Test
    void zeroLengthForwardVectorGetsFullPriority() {
        double factor = ViewBox.conePriorityFactor(
            10.0D, 64.0D, 0.0D,
            0.0D, 64.0D, 0.0D,
            0.0D, 0.0D, 0.0D,
            60.0D, 0.4D);
        assertEquals(1.0D, factor, DELTA);
    }

    @Test
    void caveSubscriberDeprioritizesSkySlice() {
        double factor = ViewBox.yBiasFactor(20.0D, 200.0D, 50, 200, 0.5D);
        assertEquals(0.5D, factor, DELTA);
    }

    @Test
    void caveSubscriberWithLocalSliceUsesFullPriority() {
        double factor = ViewBox.yBiasFactor(20.0D, 30.0D, 50, 200, 0.5D);
        assertEquals(1.0D, factor, DELTA);
    }

    @Test
    void skySubscriberDeprioritizesUndergroundSlice() {
        double factor = ViewBox.yBiasFactor(250.0D, 10.0D, 50, 200, 0.5D);
        assertEquals(0.5D, factor, DELTA);
    }

    @Test
    void midElevationSubscriberUsesFullPriority() {
        double factor = ViewBox.yBiasFactor(100.0D, 100.0D, 50, 200, 0.5D);
        assertEquals(1.0D, factor, DELTA);
    }

    @Test
    void yBiasFactorIsClampedToValidRange() {
        double low = ViewBox.yBiasFactor(20.0D, 200.0D, 50, 200, -1.0D);
        double high = ViewBox.yBiasFactor(20.0D, 200.0D, 50, 200, 2.0D);
        assertEquals(0.0D, low, DELTA);
        assertEquals(1.0D, high, DELTA);
    }
}
