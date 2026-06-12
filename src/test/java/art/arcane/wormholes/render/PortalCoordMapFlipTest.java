package art.arcane.wormholes.render;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

import art.arcane.wormholes.portal.PortalFrame;
import art.arcane.wormholes.util.Direction;

public final class PortalCoordMapFlipTest {
	@Test
	public void mirrorOnVerticalNormalFlipsWorldUp() {
		assertTrue(PortalCoordMap.reflectionFlipsWorldUp(PortalFrame.canonical(Direction.U)));
		assertTrue(PortalCoordMap.reflectionFlipsWorldUp(PortalFrame.canonical(Direction.D)));
	}

	@Test
	public void mirrorOnWallNormalKeepsWorldUp() {
		assertFalse(PortalCoordMap.reflectionFlipsWorldUp(PortalFrame.canonical(Direction.N)));
		assertFalse(PortalCoordMap.reflectionFlipsWorldUp(PortalFrame.canonical(Direction.S)));
		assertFalse(PortalCoordMap.reflectionFlipsWorldUp(PortalFrame.canonical(Direction.E)));
		assertFalse(PortalCoordMap.reflectionFlipsWorldUp(PortalFrame.canonical(Direction.W)));
	}

	@Test
	public void floorToCeilingTunnelFlipsWorldUp() {
		PortalFrame floor = PortalFrame.canonical(Direction.U);
		PortalFrame ceiling = PortalFrame.canonical(Direction.D);
		assertTrue(PortalCoordMap.transformFlipsWorldUp(floor, ceiling));
		assertTrue(PortalCoordMap.transformFlipsWorldUp(ceiling, floor));
	}

	@Test
	public void matchingVerticalTunnelKeepsWorldUp() {
		PortalFrame floorA = PortalFrame.canonical(Direction.U);
		PortalFrame floorB = PortalFrame.canonical(Direction.U);
		assertFalse(PortalCoordMap.transformFlipsWorldUp(floorA, floorB));
	}

	@Test
	public void wallTunnelsKeepWorldUp() {
		for (Direction fromNormal : new Direction[] { Direction.N, Direction.S, Direction.E, Direction.W }) {
			for (Direction toNormal : new Direction[] { Direction.N, Direction.S, Direction.E, Direction.W }) {
				assertFalse(PortalCoordMap.transformFlipsWorldUp(PortalFrame.canonical(fromNormal), PortalFrame.canonical(toNormal)));
			}
		}
	}

	@Test
	public void wallToFloorTunnelDoesNotFlip() {
		PortalFrame wall = PortalFrame.canonical(Direction.N);
		PortalFrame floor = PortalFrame.canonical(Direction.U);
		assertFalse(PortalCoordMap.transformFlipsWorldUp(wall, floor));
		assertFalse(PortalCoordMap.transformFlipsWorldUp(floor, wall));
	}

	@Test
	public void eyeSideFlipOfVerticalFrameRestoresWorldUp() {
		PortalFrame floorBackside = PortalFrame.canonical(Direction.U).flipNormal();
		PortalFrame ceiling = PortalFrame.canonical(Direction.D);
		assertFalse(PortalCoordMap.transformFlipsWorldUp(floorBackside, ceiling));
	}
}
