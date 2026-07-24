package art.arcane.wormholes.render;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

import art.arcane.wormholes.portal.PortalFrame;
import art.arcane.wormholes.portal.PortalStructure;
import art.arcane.wormholes.util.AxisAlignedBB;
import art.arcane.wormholes.util.Direction;

public final class PortalPlaneWindowPerCellToleranceTest {
    private static final double ORIGIN_X = 0.5D;
    private static final double ORIGIN_Y = 64.5D;
    private static final double ORIGIN_Z = 10.5D;
    private static final double EYE_X = 0.5D;
    private static final double EYE_Y = 64.5D;
    private static final double EYE_Z = 5.0D;
    private static final double EYE_SIGNED_DISTANCE = EYE_Z - ORIGIN_Z;
    private static final double CELL_SIGNED_DISTANCE = 16.0D - ORIGIN_Z;

    private static PortalProjector.PortalPlaneWindow window(double padding) {
        return PortalProjector.PortalPlaneWindow.create(new NotchedStructure(),
            new AxisAlignedBB(0.0D, 3.0D, 64.0D, 66.0D, 10.0D, 11.0D),
            PortalFrame.canonical(Direction.S),
            ORIGIN_X, ORIGIN_Y, ORIGIN_Z,
            padding,
            EYE_SIGNED_DISTANCE);
    }

    @Test
    public void notchHitWithinPaddingOfMemberBlockIsAccepted() {
        assertTrue(window(0.75D).containsRayIntersection(EYE_X, EYE_Y, EYE_Z, 3.1D, 64.5D, 16.0D, CELL_SIGNED_DISTANCE));
    }

    @Test
    public void notchHitBeyondToleranceOfMemberBlockIsRejected() {
        assertFalse(window(0.05D).containsRayIntersection(EYE_X, EYE_Y, EYE_Z, 3.1D, 64.5D, 16.0D, CELL_SIGNED_DISTANCE));
    }

    @Test
    public void directMemberHitIsAcceptedWithoutPadding() {
        assertTrue(window(0.0D).containsRayIntersection(EYE_X, EYE_Y, EYE_Z, 0.5D, 64.5D, 16.0D, CELL_SIGNED_DISTANCE));
    }

    private static final class NotchedStructure extends PortalStructure {
        @Override
        public boolean isFullCuboid() {
            return false;
        }

        @Override
        public boolean containsBlock(int x, int y, int z) {
            return (x == 0 || x == 2) && y == 64 && z == 10;
        }
    }
}
