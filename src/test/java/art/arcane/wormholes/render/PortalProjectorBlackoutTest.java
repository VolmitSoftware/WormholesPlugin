package art.arcane.wormholes.render;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.bukkit.Location;
import org.junit.jupiter.api.Test;

import art.arcane.wormholes.util.AxisAlignedBB;
import art.arcane.wormholes.util.Direction;

public final class PortalProjectorBlackoutTest {
	@Test
	public void farPlaneCellTriggersOnAnchoredDepthBand() {
		assertTrue(PortalProjector.isFarPlaneCell(47.5D, 47.0D, 0.0D, -49.0D, 49.0D, 0.0D, -49.0D, 49.0D));
		assertFalse(PortalProjector.isFarPlaneCell(40.0D, 47.0D, 0.0D, -49.0D, 49.0D, 0.0D, -49.0D, 49.0D));
	}

	@Test
	public void farPlaneCellTriggersOnAnchoredLateralBands() {
		assertTrue(PortalProjector.isFarPlaneCell(10.0D, 47.0D, 49.5D, -49.0D, 49.0D, 0.0D, -49.0D, 49.0D));
		assertTrue(PortalProjector.isFarPlaneCell(10.0D, 47.0D, -49.5D, -49.0D, 49.0D, 0.0D, -49.0D, 49.0D));
		assertTrue(PortalProjector.isFarPlaneCell(10.0D, 47.0D, 0.0D, -49.0D, 49.0D, 49.2D, -49.0D, 49.0D));
		assertFalse(PortalProjector.isFarPlaneCell(10.0D, 47.0D, 48.0D, -49.0D, 49.0D, -48.0D, -49.0D, 49.0D));
	}

	@Test
	public void shouldBlackoutSampleFillsEverythingExceptOccludingBlocks() {
		assertTrue(PortalProjector.shouldBlackoutSample(PortalProjector.ProjectedSampleKind.NO_SAMPLE, false));
		assertTrue(PortalProjector.shouldBlackoutSample(PortalProjector.ProjectedSampleKind.NO_SAMPLE, true));
		assertTrue(PortalProjector.shouldBlackoutSample(PortalProjector.ProjectedSampleKind.MASK_AIR, false));
		assertTrue(PortalProjector.shouldBlackoutSample(PortalProjector.ProjectedSampleKind.MASK_AIR, true));
		assertTrue(PortalProjector.shouldBlackoutSample(PortalProjector.ProjectedSampleKind.REMOTE_AIR, false));
		assertTrue(PortalProjector.shouldBlackoutSample(PortalProjector.ProjectedSampleKind.REMOTE_AIR, true));
		assertTrue(PortalProjector.shouldBlackoutSample(PortalProjector.ProjectedSampleKind.BLOCK, false));
		assertFalse(PortalProjector.shouldBlackoutSample(PortalProjector.ProjectedSampleKind.BLOCK, true));
	}

	@Test
	public void blackoutBandSealsDeepestFrustumCellHeadOnThinPortal() {
		assertDeepestFrustumCellBlackoutFilled(1.0D, 0.0D, 100.0D);
	}

	@Test
	public void blackoutBandSealsDeepestFrustumCellThirtyDegreesThinPortal() {
		assertDeepestFrustumCellBlackoutFilled(1.0D, Math.tan(Math.toRadians(30.0D)), 100.0D);
	}

	@Test
	public void blackoutBandSealsDeepestFrustumCellSixtyDegreesThinPortal() {
		assertDeepestFrustumCellBlackoutFilled(1.0D, Math.tan(Math.toRadians(60.0D)), 100.0D);
	}

	@Test
	public void blackoutBandSealsDeepestFrustumCellHeadOnTwoThickPortal() {
		assertDeepestFrustumCellBlackoutFilled(2.0D, 0.0D, 100.0D);
	}

	@Test
	public void blackoutBandSealsDeepestFrustumCellHeadOnThinPortalNearEye() {
		assertDeepestFrustumCellBlackoutFilled(1.0D, 0.0D, 1.5D);
	}

	@Test
	public void blackoutBandSealsDeepestFrustumCellSixtyDegreesThinPortalNearEye() {
		assertDeepestFrustumCellBlackoutFilled(1.0D, Math.tan(Math.toRadians(60.0D)), 1.5D);
	}

	@Test
	public void blackoutBandSealsDeepestFrustumCellHeadOnTwoThickPortalNearEye() {
		assertDeepestFrustumCellBlackoutFilled(2.0D, 0.0D, 1.5D);
	}

	private static void assertDeepestFrustumCellBlackoutFilled(double thickness, double offAxisSlope, double eyeDistance) {
		double range = 48.0D;
		double structureMinX = 0.0D;
		double structureMaxX = thickness;
		double realFacePlaneX = structureMaxX;
		double centerX = thickness * 0.5D;
		double clearance = Math.max(0.5001D, (thickness * 0.5D) + 0.001D);
		double lateralMargin = 1.0D;
		double depthSealThreshold = range - 1.0D;
		double lateralSealLow = -2.0D - range + lateralMargin;
		double lateralSealHigh = 2.0D + range - lateralMargin;

		double dirX = -1.0D;
		double dirY = offAxisSlope;
		double dirZ = 0.0D;
		double dirLength = Math.sqrt((dirX * dirX) + (dirY * dirY) + (dirZ * dirZ));
		dirX = dirX / dirLength;
		dirY = dirY / dirLength;
		dirZ = dirZ / dirLength;

		double apertureCenterY = 0.0D;
		double apertureCenterZ = 0.0D;
		double eyeX = realFacePlaneX - (dirX * eyeDistance);
		double eyeY = apertureCenterY - (dirY * eyeDistance);
		double eyeZ = apertureCenterZ - (dirZ * eyeDistance);
		Location eye = new Location(null, eyeX, eyeY, eyeZ);

		AxisAlignedBB apertureFace = new AxisAlignedBB(realFacePlaneX, realFacePlaneX, -2.0D, 2.0D, -2.0D, 2.0D);
		Frustum frustum = new Frustum(eye, apertureFace, Direction.E, range, 0.0D);

		double stepLimit = eyeDistance + (range * 3.0D) + clearance + 8.0D;
		boolean found = false;
		int deepestX = 0;
		int deepestY = 0;
		int deepestZ = 0;
		int previousX = Integer.MIN_VALUE;
		int previousY = Integer.MIN_VALUE;
		int previousZ = Integer.MIN_VALUE;
		for (double t = 0.0D; t <= stepLimit; t = t + 0.1D) {
			int blockX = (int) Math.floor(eyeX + (dirX * t));
			int blockY = (int) Math.floor(eyeY + (dirY * t));
			int blockZ = (int) Math.floor(eyeZ + (dirZ * t));
			if (blockX == previousX && blockY == previousY && blockZ == previousZ) {
				continue;
			}
			previousX = blockX;
			previousY = blockY;
			previousZ = blockZ;
			double cellX = blockX + 0.5D;
			double cellY = blockY + 0.5D;
			double cellZ = blockZ + 0.5D;
			if (cellX >= centerX) {
				continue;
			}
			if (!frustum.containsPrimitive(cellX, cellY, cellZ)) {
				continue;
			}
			deepestX = blockX;
			deepestY = blockY;
			deepestZ = blockZ;
			found = true;
		}

		assertTrue(found, "expected at least one frustum-surviving projected cell for thickness "
			+ thickness + " slope " + offAxisSlope + " eye distance " + eyeDistance);
		double cellX = deepestX + 0.5D;
		double cellY = deepestY + 0.5D;
		double cellZ = deepestZ + 0.5D;
		double blackoutPlaneX = PortalProjector.eyeSideFacePlane(eyeX, centerX, structureMinX, structureMaxX);
		double depthBeyondFacePlane = blackoutPlaneX - cellX;
		assertTrue(PortalProjector.isFarPlaneCell(depthBeyondFacePlane, depthSealThreshold,
				cellY, lateralSealLow, lateralSealHigh, cellZ, lateralSealLow, lateralSealHigh),
			"deepest frustum-surviving cell must be blackout-filled for thickness "
				+ thickness + " slope " + offAxisSlope + " eye distance " + eyeDistance);
	}
}
