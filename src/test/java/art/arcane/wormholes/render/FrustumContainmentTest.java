package art.arcane.wormholes.render;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.bukkit.Location;
import org.junit.jupiter.api.Test;

import art.arcane.wormholes.util.AxisAlignedBB;
import art.arcane.wormholes.util.Direction;

public final class FrustumContainmentTest {
    @Test
    public void primitiveContainmentAcceptsCellsThroughApertureWithinRange() {
        Frustum frustum = new Frustum(new Location(null, 0.0D, 0.0D, 0.0D),
            new AxisAlignedBB(0.0D, 4.0D, 0.0D, 4.0D, 10.0D, 10.0D),
            Direction.S,
            8.0D,
            0.0D);

        assertTrue(frustum.containsPrimitive(2.0D, 2.0D, 12.0D));
    }

    @Test
    public void primitiveContainmentRejectsObserverSideLateralAndFarCells() {
        Frustum frustum = new Frustum(new Location(null, 0.0D, 0.0D, 0.0D),
            new AxisAlignedBB(0.0D, 4.0D, 0.0D, 4.0D, 10.0D, 10.0D),
            Direction.S,
            8.0D,
            0.0D);

        assertFalse(frustum.containsPrimitive(2.0D, 2.0D, 5.0D));
        assertFalse(frustum.containsPrimitive(8.0D, 8.0D, 12.0D));
        assertFalse(frustum.containsPrimitive(1.0D, 1.0D, 25.0D));
    }

    @Test
    public void regionBoundsMatchApexApertureAndRange() {
        double range = 8.0D;
        Frustum frustum = new Frustum(new Location(null, 0.0D, 0.0D, 0.0D),
            new AxisAlignedBB(0.0D, 4.0D, 0.0D, 4.0D, 10.0D, 10.0D),
            Direction.S,
            range,
            0.0D);

        double minX = Double.POSITIVE_INFINITY;
        double minY = Double.POSITIVE_INFINITY;
        double minZ = Double.POSITIVE_INFINITY;
        double maxX = Double.NEGATIVE_INFINITY;
        double maxY = Double.NEGATIVE_INFINITY;
        double maxZ = Double.NEGATIVE_INFINITY;
        double[][] corners = new double[][] {
            { 4.0D, 4.0D, 10.0D },
            { 4.0D, 0.0D, 10.0D },
            { 0.0D, 4.0D, 10.0D },
            { 0.0D, 0.0D, 10.0D }
        };
        for (double[] corner : corners) {
            double dx = corner[0];
            double dy = corner[1];
            double dz = corner[2];
            double len = Math.sqrt((dx * dx) + (dy * dy) + (dz * dz));
            double farX = corner[0] + ((dx / len) * range);
            double farY = corner[1] + ((dy / len) * range);
            double farZ = corner[2] + ((dz / len) * range);
            minX = Math.min(minX, Math.min(corner[0], farX));
            minY = Math.min(minY, Math.min(corner[1], farY));
            minZ = Math.min(minZ, Math.min(corner[2], farZ));
            maxX = Math.max(maxX, Math.max(corner[0], farX));
            maxY = Math.max(maxY, Math.max(corner[1], farY));
            maxZ = Math.max(maxZ, Math.max(corner[2], farZ));
        }

        AxisAlignedBB region = frustum.getRegion();
        assertEquals(minX, region.getXa());
        assertEquals(maxX, region.getXb());
        assertEquals(minY, region.getYa());
        assertEquals(maxY, region.getYb());
        assertEquals(minZ, region.getZa());
        assertEquals(maxZ, region.getZb());
    }
}
