package art.arcane.wormholes.render;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;

import org.junit.jupiter.api.Test;

import art.arcane.wormholes.util.AxisAlignedBB;
import art.arcane.wormholes.util.Direction;

public final class FrustumAperturePaddingTest {
    private static final double EPSILON = 1e-9D;

    @Test
    public void zeroPaddingKeepsOriginalFace() {
        AxisAlignedBB face = new AxisAlignedBB(1.0D, 1.0D, 10.0D, 14.0D, 20.0D, 24.0D);
        AxisAlignedBB padded = Frustum.padAperture(face, Direction.E, 0.0D);

        assertSame(face, padded);
    }

    @Test
    public void wallFacePaddingExpandsLateralAndVerticalAxesOnly() {
        AxisAlignedBB face = new AxisAlignedBB(5.0D, 5.0D, 64.0D, 68.0D, 10.0D, 14.0D);
        AxisAlignedBB padded = Frustum.padAperture(face, Direction.E, 1.0D);

        assertEquals(5.0D, padded.getXa(), EPSILON);
        assertEquals(5.0D, padded.getXb(), EPSILON);
        assertEquals(63.0D, padded.getYa(), EPSILON);
        assertEquals(69.0D, padded.getYb(), EPSILON);
        assertEquals(9.0D, padded.getZa(), EPSILON);
        assertEquals(15.0D, padded.getZb(), EPSILON);
    }

    @Test
    public void floorOrCeilingFacePaddingExpandsHorizontalScreenAxesOnly() {
        AxisAlignedBB face = new AxisAlignedBB(5.0D, 9.0D, 64.0D, 64.0D, 10.0D, 14.0D);
        AxisAlignedBB padded = Frustum.padAperture(face, Direction.D, 1.5D);

        assertEquals(3.5D, padded.getXa(), EPSILON);
        assertEquals(10.5D, padded.getXb(), EPSILON);
        assertEquals(64.0D, padded.getYa(), EPSILON);
        assertEquals(64.0D, padded.getYb(), EPSILON);
        assertEquals(8.5D, padded.getZa(), EPSILON);
        assertEquals(15.5D, padded.getZb(), EPSILON);
    }
}
