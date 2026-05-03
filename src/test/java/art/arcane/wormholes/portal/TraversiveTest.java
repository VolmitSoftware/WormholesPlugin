package art.arcane.wormholes.portal;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.bukkit.util.Vector;
import org.junit.jupiter.api.Test;

import art.arcane.wormholes.util.Direction;

public final class TraversiveTest {
    private static final double EPSILON = 1e-9D;

    @Test
    public void outPointPreservesPortalRightAndUpOffset() {
        PortalFrame inFrame = PortalFrame.canonical(Direction.N);
        PortalFrame outFrame = PortalFrame.canonical(Direction.E);
        Vector inOrigin = new Vector(10.0D, 64.0D, 20.0D);
        Vector outOrigin = new Vector(100.0D, 70.0D, -30.0D);
        Vector inPoint = new Vector(11.25D, 64.5D, 19.5D);
        Vector velocity = new Vector(0.0D, 0.0D, -0.4D);
        Vector look = new Vector(0.0D, 0.0D, -1.0D);
        Traversive traversive = new Traversive(new Object(), TraversableType.ENTITY, inFrame, inOrigin, inPoint, velocity, look);

        Vector outPoint = traversive.getOutPoint(outFrame, outOrigin);
        Vector outOffset = traversive.getOutOffset(outFrame);
        Vector outVelocity = traversive.getOutVelocity(outFrame);

        assertVector(new Vector(100.5D, 70.5D, -28.75D), outPoint);
        assertVector(new Vector(0.5D, 0.5D, 1.25D), outOffset);
        assertVector(new Vector(0.4D, 0.0D, 0.0D), outVelocity);
    }

    @Test
    public void backSideTraversalFlipsHorizontalScreenAxis() {
        PortalFrame inFrame = PortalFrame.canonical(Direction.N).view(false);
        PortalFrame outFrame = PortalFrame.canonical(Direction.E);
        Vector inOrigin = new Vector(10.0D, 64.0D, 20.0D);
        Vector outOrigin = new Vector(100.0D, 70.0D, -30.0D);
        Vector inPoint = new Vector(8.75D, 64.5D, 20.5D);
        Vector velocity = new Vector(0.0D, 0.0D, -0.4D);
        Vector look = new Vector(0.0D, 0.0D, -1.0D);
        Traversive traversive = new Traversive(new Object(), TraversableType.ENTITY, inFrame, inOrigin, inPoint, velocity, look, false);

        Vector outPoint = traversive.getOutPoint(outFrame, outOrigin);
        Vector outVelocity = traversive.getOutVelocity(outFrame);

        assertVector(new Vector(99.5D, 70.5D, -31.25D), outPoint);
        assertVector(new Vector(0.4D, 0.0D, 0.0D), outVelocity);
    }

    private static void assertVector(Vector expected, Vector actual) {
        assertEquals(expected.getX(), actual.getX(), EPSILON);
        assertEquals(expected.getY(), actual.getY(), EPSILON);
        assertEquals(expected.getZ(), actual.getZ(), EPSILON);
    }
}
