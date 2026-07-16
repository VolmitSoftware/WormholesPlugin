package art.arcane.wormholes.portal;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import net.md_5.bungee.api.ChatColor;
import org.junit.jupiter.api.Test;

public final class ProjectionModeTest
{
	@Test
	public void cyclesStrictlyBetweenOffAndOn()
	{
		assertArrayEquals(new ProjectionMode[] {ProjectionMode.OFF, ProjectionMode.ON}, ProjectionMode.values());
		assertEquals(ProjectionMode.ON, ProjectionMode.OFF.next());
		assertEquals(ProjectionMode.OFF, ProjectionMode.ON.next());
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
