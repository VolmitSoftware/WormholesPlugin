package art.arcane.wormholes.render;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

import art.arcane.wormholes.portal.PortalFrame;
import art.arcane.wormholes.util.AxisAlignedBB;
import art.arcane.wormholes.util.Direction;

public final class PortalProjectorPlaneTest {
	@Test
	public void onlyOppositeSideCellsPastThePortalSlabAreProjected() {
		double clearance = 0.5001D;

		assertFalse(PortalProjector.projectsBehindPortalPlane(2.0D, true, clearance));
		assertFalse(PortalProjector.projectsBehindPortalPlane(0.25D, true, clearance));
		assertFalse(PortalProjector.projectsBehindPortalPlane(-0.25D, true, clearance));
		assertTrue(PortalProjector.projectsBehindPortalPlane(-1.0D, true, clearance));

		assertFalse(PortalProjector.projectsBehindPortalPlane(-2.0D, false, clearance));
		assertFalse(PortalProjector.projectsBehindPortalPlane(-0.25D, false, clearance));
		assertFalse(PortalProjector.projectsBehindPortalPlane(0.25D, false, clearance));
		assertTrue(PortalProjector.projectsBehindPortalPlane(1.0D, false, clearance));
	}

	@Test
	public void planeClearanceTracksPortalNormalThickness() {
		AxisAlignedBB northPortal = new AxisAlignedBB(0.0D, 4.999D, 64.0D, 68.999D, 10.0D, 10.999D);
		double northClearance = PortalProjector.portalPlaneClearance(northPortal, PortalFrame.canonical(Direction.N));
		assertTrue(northClearance > 0.5D);
		assertTrue(northClearance < 0.502D);

		AxisAlignedBB thickDownPortal = new AxisAlignedBB(0.0D, 4.999D, 63.0D, 64.999D, 10.0D, 14.999D);
		double downClearance = PortalProjector.portalPlaneClearance(thickDownPortal, PortalFrame.canonical(Direction.D));
		assertTrue(downClearance > 0.999D);
		assertTrue(downClearance < 1.002D);
	}

	@Test
	public void scanBoundsIncludeBlockCentersAtTheProjectionEdges() {
		assertEquals(4, PortalProjector.minBlockForCenter(4.5D));
		assertEquals(8, PortalProjector.maxBlockForCenter(8.5D));
		assertEquals(4, PortalProjector.minBlockForCenter(4.5000003D));
		assertEquals(8, PortalProjector.maxBlockForCenter(8.4999997D));
		assertEquals(5, PortalProjector.minBlockForCenter(4.500002D));
		assertEquals(7, PortalProjector.maxBlockForCenter(8.499998D));
	}
}
