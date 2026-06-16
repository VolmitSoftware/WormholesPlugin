package art.arcane.wormholes.portal;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

public final class ProjectionModeTest
{
	@Test
	public void gatewayCyclesFullSequence()
	{
		ProjectionMode current = ProjectionMode.OFF;
		current = current.nextFor(PortalType.GATEWAY);
		assertEquals(ProjectionMode.ON, current);
		current = current.nextFor(PortalType.GATEWAY);
		assertEquals(ProjectionMode.ONE_WAY, current);
		current = current.nextFor(PortalType.GATEWAY);
		assertEquals(ProjectionMode.MIRROR, current);
		current = current.nextFor(PortalType.GATEWAY);
		assertEquals(ProjectionMode.OFF, current);
	}

	@Test
	public void portalAndWormholeCycleOffOnOnly()
	{
		assertEquals(ProjectionMode.ON, ProjectionMode.OFF.nextFor(PortalType.PORTAL));
		assertEquals(ProjectionMode.OFF, ProjectionMode.ON.nextFor(PortalType.PORTAL));
		assertEquals(ProjectionMode.OFF, ProjectionMode.ONE_WAY.nextFor(PortalType.PORTAL));
		assertEquals(ProjectionMode.OFF, ProjectionMode.MIRROR.nextFor(PortalType.PORTAL));

		assertEquals(ProjectionMode.ON, ProjectionMode.OFF.nextFor(PortalType.WORMHOLE));
		assertEquals(ProjectionMode.OFF, ProjectionMode.ON.nextFor(PortalType.WORMHOLE));
		assertEquals(ProjectionMode.OFF, ProjectionMode.ONE_WAY.nextFor(PortalType.WORMHOLE));
		assertEquals(ProjectionMode.OFF, ProjectionMode.MIRROR.nextFor(PortalType.WORMHOLE));
	}

	@Test
	public void allowedForGatewayAcceptsEveryMode()
	{
		for(ProjectionMode mode : ProjectionMode.values())
		{
			assertTrue(mode.isAllowedFor(PortalType.GATEWAY), "gateway should allow " + mode);
		}
	}

	@Test
	public void allowedForNonGatewayLimitsToOffAndOn()
	{
		assertTrue(ProjectionMode.OFF.isAllowedFor(PortalType.PORTAL));
		assertTrue(ProjectionMode.ON.isAllowedFor(PortalType.PORTAL));
		assertFalse(ProjectionMode.ONE_WAY.isAllowedFor(PortalType.PORTAL));
		assertFalse(ProjectionMode.MIRROR.isAllowedFor(PortalType.PORTAL));

		assertTrue(ProjectionMode.OFF.isAllowedFor(PortalType.WORMHOLE));
		assertTrue(ProjectionMode.ON.isAllowedFor(PortalType.WORMHOLE));
		assertFalse(ProjectionMode.ONE_WAY.isAllowedFor(PortalType.WORMHOLE));
		assertFalse(ProjectionMode.MIRROR.isAllowedFor(PortalType.WORMHOLE));
	}
}
