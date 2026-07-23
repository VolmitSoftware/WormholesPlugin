package art.arcane.wormholes.render;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.bukkit.Location;
import org.junit.jupiter.api.Test;

import art.arcane.wormholes.util.AxisAlignedBB;
import art.arcane.wormholes.util.Direction;

public final class FrustumContainmentTest {
    private static final AxisAlignedBB APERTURE = new AxisAlignedBB(0.0D, 4.0D, 0.0D, 4.0D, 10.0D, 10.0D);
    private static final double RANGE = 8.0D;

    private static Frustum frustumAt(double eyeX, double eyeY, double eyeZ) {
        return new Frustum(new Location(null, eyeX, eyeY, eyeZ), APERTURE, Direction.S, RANGE, 0.0D);
    }

    @Test
    public void primitiveContainmentAcceptsCellsThroughApertureWithinRange() {
        assertTrue(frustumAt(0.0D, 0.0D, 0.0D).containsPrimitive(2.0D, 2.0D, 12.0D));
    }

    @Test
    public void primitiveContainmentRejectsObserverSideLateralAndFarCells() {
        Frustum frustum = frustumAt(0.0D, 0.0D, 0.0D);

        assertFalse(frustum.containsPrimitive(2.0D, 2.0D, 5.0D));
        assertFalse(frustum.containsPrimitive(8.0D, 8.0D, 12.0D));
        assertFalse(frustum.containsPrimitive(1.0D, 1.0D, 25.0D));
    }

    @Test
    public void farPlaneStaysAnchoredToPortalRegardlessOfEyeDistance() {
        double[] eyeDistances = new double[] { 0.75D, 1.5D, 3.0D, 8.0D, 40.0D };
        for (double eyeDistance : eyeDistances) {
            Frustum frustum = frustumAt(2.0D, 2.0D, 10.0D - eyeDistance);
            assertTrue(frustum.containsPrimitive(2.0D, 2.0D, 17.5D),
                "deepest center-ray cell must stay contained at eye distance " + eyeDistance);
            assertFalse(frustum.containsPrimitive(2.0D, 2.0D, 18.6D),
                "cells past the anchored far plane must stay rejected at eye distance " + eyeDistance);
        }
    }

    @Test
    public void obliqueEdgeCellsReachFullDepthNearPortal() {
        Frustum frustum = frustumAt(2.0D, 2.0D, 9.5D);

        assertTrue(frustum.containsPrimitive(8.5D, 2.5D, 17.5D));
    }

    @Test
    public void lateralExtentCappedByAnchoredRangeBox() {
        Frustum frustum = frustumAt(2.0D, 2.0D, 9.5D);

        assertFalse(frustum.containsPrimitive(13.5D, 2.5D, 12.5D));
    }

    @Test
    public void regionBoundsMatchApertureRangeBoxForDistantEye() {
        Frustum frustum = frustumAt(0.0D, 0.0D, 0.0D);

        AxisAlignedBB region = frustum.getRegion();
        assertEquals(0.0D, region.getXa());
        assertEquals(7.2D, region.getXb(), 1.0E-9D);
        assertEquals(0.0D, region.getYa());
        assertEquals(7.2D, region.getYb(), 1.0E-9D);
        assertEquals(10.0D, region.getZa());
        assertEquals(18.0D, region.getZb());
    }

    @Test
    public void regionBoundsClampToAnchoredRangeBoxForNearEye() {
        Frustum frustum = frustumAt(2.0D, 2.0D, 9.5D);

        AxisAlignedBB region = frustum.getRegion();
        assertEquals(-8.0D, region.getXa());
        assertEquals(12.0D, region.getXb());
        assertEquals(-8.0D, region.getYa());
        assertEquals(12.0D, region.getYb());
        assertEquals(10.0D, region.getZa());
        assertEquals(18.0D, region.getZb());
    }
}
