package art.arcane.wormholes.portal;

import art.arcane.wormholes.util.Direction;
import org.bukkit.util.Vector;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LocalPortalRejectionTest {
    @Test
    void frontSideRejectionReturnsTravelerAlongViewedNormal() {
        Traversive traversive = traversive(true);

        assertVector(new Vector(2.0D, 65.0D, 1.75D), LocalPortal.sourceRejectionPoint(traversive));
        assertVector(new Vector(0.0D, 0.0D, -3.0D), LocalPortal.sourceRejectionVelocity(traversive));
    }

    @Test
    void backSideRejectionReturnsTravelerToOppositeSourceSide() {
        Traversive traversive = traversive(false);

        assertVector(new Vector(2.0D, 65.0D, 4.25D), LocalPortal.sourceRejectionPoint(traversive));
        assertVector(new Vector(0.0D, 0.0D, 3.0D), LocalPortal.sourceRejectionVelocity(traversive));
    }

    @Test
    void forwardMotionRemainsCommittedWhileBackingAwayCancels() {
        assertDepartureCommitment(traversive(true));
        assertDepartureCommitment(traversive(false));
    }

    private static Traversive traversive(boolean frontSide) {
        PortalFrame frame = PortalFrame.canonical(Direction.N).view(frontSide);
        return new Traversive(
            new Object(),
            TraversableType.ENTITY,
            frame,
            new Vector(2.0D, 65.0D, 3.0D),
            new Vector(2.0D, 65.0D, 3.0D),
            new Vector(0.0D, 0.0D, frontSide ? -0.2D : 0.2D),
            new Vector(0.0D, 0.0D, frontSide ? -1.0D : 1.0D),
            frontSide
        );
    }

    private static void assertVector(Vector expected, Vector actual) {
        assertEquals(expected.getX(), actual.getX(), 1.0E-9D);
        assertEquals(expected.getY(), actual.getY(), 1.0E-9D);
        assertEquals(expected.getZ(), actual.getZ(), 1.0E-9D);
    }

    private static void assertDepartureCommitment(Traversive traversive) {
        Vector normal = traversive.getInFrame().getNormal().toVector().normalize();
        Vector boundary = traversive.getInPoint().clone().add(normal.clone().multiply(2.0D));
        Vector backedAway = traversive.getInPoint().clone().add(normal.clone().multiply(2.01D));
        Vector forward = traversive.getInPoint().clone().subtract(normal.clone().multiply(8.0D));

        assertTrue(LocalPortal.remainsCommittedToDeparture(traversive, boundary));
        assertFalse(LocalPortal.remainsCommittedToDeparture(traversive, backedAway));
        assertTrue(LocalPortal.remainsCommittedToDeparture(traversive, forward));
    }
}
