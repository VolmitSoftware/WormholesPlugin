package art.arcane.wormholes.portal.vanilla;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import org.bukkit.Material;
import org.junit.jupiter.api.Test;

public final class PortalSiteBuilderPlanTest
{
	@Test
	public void generatedNetherPortalHasACompletePerimeter()
	{
		int width = 2;
		int height = 3;
		int edges = 0;
		for(int u = -1; u <= width; u++)
		{
			for(int v = -1; v <= height; v++)
			{
				if(PortalSiteBuilder.isNetherFrameEdge(u, v, width, height))
				{
					edges++;
				}
			}
		}

		assertEquals((2 * width) + (2 * height) + 4, edges);
		assertTrue(PortalSiteBuilder.isNetherFrameEdge(-1, 1, width, height));
		assertTrue(PortalSiteBuilder.isNetherFrameEdge(1, height, width, height));
		assertFalse(PortalSiteBuilder.isNetherFrameEdge(0, 0, width, height));
	}

	@Test
	public void generatedNetherPortalPreservesVanillaMaximumWidth()
	{
		assertEquals(1, PortalSiteBuilder.netherInteriorWidth(-1));
		assertEquals(1, PortalSiteBuilder.netherInteriorWidth(1));
		assertEquals(21, PortalSiteBuilder.netherInteriorWidth(21));
		assertEquals(21, PortalSiteBuilder.netherInteriorWidth(22));
		assertEquals(21, PortalSiteBuilder.netherInteriorWidth(40));
	}

	@Test
	public void productionPlanBuildsFrameInteriorAndSideClearAcrossOwningChunks()
	{
		List<PortalSiteBuilder.NetherMutation> plan = PortalSiteBuilder.planNetherMutations(15, 64, 15, true, 21, 3);

		assertEquals(52, plan.stream().filter(mutation -> mutation.material() == Material.OBSIDIAN).count());
		assertEquals(63, plan.stream().filter(PortalSiteBuilder.NetherMutation::interior).count());
		assertEquals(126, plan.stream().filter(PortalSiteBuilder.NetherMutation::preserveObsidian).count());
		assertTrue(plan.stream().filter(PortalSiteBuilder.NetherMutation::interior)
				.allMatch(mutation -> mutation.x() >= 5 && mutation.x() <= 25 && mutation.y() >= 64 && mutation.y() <= 66 && mutation.z() == 15));
		assertTrue(plan.stream().filter(PortalSiteBuilder.NetherMutation::interior)
				.noneMatch(PortalSiteBuilder.NetherMutation::preserveObsidian));
		Set<String> chunks = plan.stream()
				.map(mutation -> (mutation.x() >> 4) + "," + (mutation.z() >> 4))
				.collect(Collectors.toSet());
		assertEquals(Set.of("0,0", "1,0", "0,1", "1,1"), chunks);
	}

	@Test
	public void productionPlanSupportsTheOtherAxisAndNegativeChunks()
	{
		List<PortalSiteBuilder.NetherMutation> plan = PortalSiteBuilder.planNetherMutations(-16, 70, -16, false, 4, 3);

		assertEquals(18, plan.stream().filter(mutation -> mutation.material() == Material.OBSIDIAN).count());
		assertEquals(12, plan.stream().filter(PortalSiteBuilder.NetherMutation::interior).count());
		assertTrue(plan.stream().filter(PortalSiteBuilder.NetherMutation::interior).allMatch(mutation -> mutation.x() == -16));
	}

	@Test
	public void tallFallbackCannotPunchThroughTheNetherRoof()
	{
		assertTrue(PortalSiteBuilder.netherFrameFits(104, 21, -64, 128));
		assertFalse(PortalSiteBuilder.netherFrameFits(118, 21, -64, 128));
	}
}
