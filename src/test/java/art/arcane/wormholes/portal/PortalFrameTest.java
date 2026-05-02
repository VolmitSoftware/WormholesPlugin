package art.arcane.wormholes.portal;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.bukkit.util.Vector;
import org.junit.jupiter.api.Test;

import art.arcane.wormholes.util.AxisAlignedBB;
import art.arcane.wormholes.util.Direction;

public final class PortalFrameTest {
	private static final double EPSILON = 1e-9D;

	@Test
	public void canonicalDirectionPairsAreInvertible() {
		Vector fromOrigin = new Vector(7.25D, -3.5D, 4.75D);
		Vector toOrigin = new Vector(-11.25D, 9.5D, 22.75D);
		Vector point = new Vector(8.25D, -1.5D, 10.75D);
		Vector vector = new Vector(2.0D, -3.0D, 5.0D);

		for (Direction fromNormal : Direction.values()) {
			PortalFrame fromFrame = PortalFrame.canonical(fromNormal);
			for (Direction toNormal : Direction.values()) {
				PortalFrame toFrame = PortalFrame.canonical(toNormal);
				Vector projectedPoint = fromFrame.transformPoint(point, fromOrigin, toOrigin, toFrame);
				Vector restoredPoint = toFrame.transformPoint(projectedPoint, toOrigin, fromOrigin, fromFrame);
				assertVector(point, restoredPoint);

				Vector projectedVector = fromFrame.transformVector(vector, toFrame);
				Vector restoredVector = toFrame.transformVector(projectedVector, fromFrame);
				assertVector(vector, restoredVector);
			}
		}
	}

	@Test
	public void uprightToDownMapsScreenAxesIntoDownFrame() {
		PortalFrame downFrame = PortalFrame.canonical(Direction.D);
		double[] scratch = new double[3];

		for (Direction normal : new Direction[] { Direction.N, Direction.E, Direction.S, Direction.W }) {
			PortalFrame uprightFrame = PortalFrame.canonical(normal);
			assertEquals(downFrame.getRight(), uprightFrame.transformDirection(uprightFrame.getRight(), downFrame, scratch));
			assertEquals(downFrame.getUp(), uprightFrame.transformDirection(uprightFrame.getUp(), downFrame, scratch));
			assertEquals(downFrame.getNormal(), uprightFrame.transformDirection(uprightFrame.getNormal(), downFrame, scratch));
		}
	}

	@Test
	public void downToUprightReverseTransformRestoresOriginalPointAndVector() {
		PortalFrame downFrame = PortalFrame.fromDirectionAndLook(Direction.D, new Vector(0.0D, -1.0D, -1.0D));
		PortalFrame uprightFrame = PortalFrame.canonical(Direction.N);
		Vector downOrigin = new Vector(20.0D, 64.0D, -10.0D);
		Vector uprightOrigin = new Vector(-5.0D, 80.0D, 40.0D);
		Vector point = new Vector(23.0D, 61.0D, -14.0D);
		Vector vector = new Vector(1.0D, -2.0D, -3.0D);

		Vector throughPortal = downFrame.transformPoint(point, downOrigin, uprightOrigin, uprightFrame);
		Vector backThroughPortal = uprightFrame.transformPoint(throughPortal, uprightOrigin, downOrigin, downFrame);
		assertVector(point, backThroughPortal);

		Vector throughVector = downFrame.transformVector(vector, uprightFrame);
		Vector backVector = uprightFrame.transformVector(throughVector, downFrame);
		assertVector(vector, backVector);
	}

	@Test
	public void verticalFrameUsesLookYawForScreenUp() {
		PortalFrame downNorth = PortalFrame.fromDirectionAndLook(Direction.D, new Vector(0.0D, -1.0D, -1.0D));
		assertEquals(Direction.D, downNorth.getNormal());
		assertEquals(Direction.N, downNorth.getUp());
		assertEquals(Direction.E, downNorth.getRight());

		PortalFrame upSouth = PortalFrame.fromDirectionAndLook(Direction.U, new Vector(0.0D, 1.0D, 1.0D));
		assertEquals(Direction.U, upSouth.getNormal());
		assertEquals(Direction.S, upSouth.getUp());
		assertEquals(Direction.E, upSouth.getRight());
	}

	@Test
	public void verticalFrameDerivationUsesShapeBeforeFallback() {
		PortalFrame eastWideDown = PortalFrame.derive(new AxisAlignedBB(0.0D, 5.0D, 64.0D, 64.0D, 0.0D, 2.0D), Direction.D);
		assertEquals(Direction.E, eastWideDown.getUp());

		PortalFrame northWideDown = PortalFrame.derive(new AxisAlignedBB(0.0D, 2.0D, 64.0D, 64.0D, 0.0D, 5.0D), Direction.D);
		assertEquals(Direction.N, northWideDown.getUp());
	}

	@Test
	public void basisDirectionsRotateLikeProjectedBlockFacesAndAxes() {
		PortalFrame remoteDown = PortalFrame.fromDirectionAndLook(Direction.D, new Vector(0.0D, -1.0D, -1.0D));
		PortalFrame localNorth = PortalFrame.canonical(Direction.N);
		double[] scratch = new double[3];

		assertEquals(Direction.E, remoteDown.transformDirection(Direction.E, localNorth, scratch));
		assertEquals(Direction.U, remoteDown.transformDirection(Direction.N, localNorth, scratch));
		assertEquals(Direction.N, remoteDown.transformDirection(Direction.D, localNorth, scratch));
		assertEquals(Direction.D, remoteDown.transformDirection(Direction.S, localNorth, scratch));
	}

	private static void assertVector(Vector expected, Vector actual) {
		assertEquals(expected.getX(), actual.getX(), EPSILON);
		assertEquals(expected.getY(), actual.getY(), EPSILON);
		assertEquals(expected.getZ(), actual.getZ(), EPSILON);
	}
}
