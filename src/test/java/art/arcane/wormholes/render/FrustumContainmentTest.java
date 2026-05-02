package art.arcane.wormholes.render;

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
}
