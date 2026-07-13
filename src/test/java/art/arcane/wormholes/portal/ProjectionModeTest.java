package art.arcane.wormholes.portal;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import net.md_5.bungee.api.ChatColor;
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
	public void portalAndWormholeCycleIncludesMirror()
	{
		assertEquals(ProjectionMode.ON, ProjectionMode.OFF.nextFor(PortalType.PORTAL));
		assertEquals(ProjectionMode.MIRROR, ProjectionMode.ON.nextFor(PortalType.PORTAL));
		assertEquals(ProjectionMode.OFF, ProjectionMode.ONE_WAY.nextFor(PortalType.PORTAL));
		assertEquals(ProjectionMode.OFF, ProjectionMode.MIRROR.nextFor(PortalType.PORTAL));

		assertEquals(ProjectionMode.ON, ProjectionMode.OFF.nextFor(PortalType.WORMHOLE));
		assertEquals(ProjectionMode.MIRROR, ProjectionMode.ON.nextFor(PortalType.WORMHOLE));
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
	public void allowedForNonGatewayRejectsOnlyOneWay()
	{
		assertTrue(ProjectionMode.OFF.isAllowedFor(PortalType.PORTAL));
		assertTrue(ProjectionMode.ON.isAllowedFor(PortalType.PORTAL));
		assertFalse(ProjectionMode.ONE_WAY.isAllowedFor(PortalType.PORTAL));
		assertTrue(ProjectionMode.MIRROR.isAllowedFor(PortalType.PORTAL));

		assertTrue(ProjectionMode.OFF.isAllowedFor(PortalType.WORMHOLE));
		assertTrue(ProjectionMode.ON.isAllowedFor(PortalType.WORMHOLE));
		assertFalse(ProjectionMode.ONE_WAY.isAllowedFor(PortalType.WORMHOLE));
		assertTrue(ProjectionMode.MIRROR.isAllowedFor(PortalType.WORMHOLE));
	}

	@Test
	public void mirrorIsOnlyNonTraversableProjectionMode()
	{
		assertTrue(ProjectionMode.OFF.allowsTraversal());
		assertTrue(ProjectionMode.ON.allowsTraversal());
		assertTrue(ProjectionMode.ONE_WAY.allowsTraversal());
		assertFalse(ProjectionMode.MIRROR.allowsTraversal());
	}

	@Test
	public void primaryProjectionStatesUseBlackAndGoldTheme()
	{
		assertTrue(ProjectionMode.OFF.getDisplayName().startsWith(ChatColor.DARK_GRAY.toString()));
		assertTrue(ProjectionMode.ON.getDisplayName().startsWith(ChatColor.GOLD.toString()));
		assertFalse(ProjectionMode.OFF.getDisplayName().contains(ChatColor.DARK_PURPLE.toString()));
		assertFalse(ProjectionMode.ON.getDisplayName().contains(ChatColor.LIGHT_PURPLE.toString()));
	}
}
