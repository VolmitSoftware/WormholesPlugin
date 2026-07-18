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
	public void platformPaddingScalesFromOneToThreeBlocksWithPortalSize()
	{
		assertEquals(1, PortalSiteBuilder.netherPlatformPadding(2, 3));
		assertEquals(2, PortalSiteBuilder.netherPlatformPadding(8, 3));
		assertEquals(3, PortalSiteBuilder.netherPlatformPadding(21, 3));
		assertEquals(3, PortalSiteBuilder.netherPlatformPadding(2, 21));
	}

	@Test
	public void productionPlanBuildsFrameInteriorAndSideClearAcrossOwningChunks()
	{
		List<PortalSiteBuilder.NetherMutation> plan = PortalSiteBuilder.planNetherMutations(15, 64, 15, true, 21, 3);

		assertEquals(52, plan.stream().filter(mutation -> mutation.material() == Material.OBSIDIAN).count());
		assertEquals(63, plan.stream().filter(PortalSiteBuilder.NetherMutation::interior).count());
		assertEquals(180, plan.stream().filter(PortalSiteBuilder.NetherMutation::preserveObsidian).count());
		assertEquals(180, plan.stream().filter(mutation -> mutation.material() == Material.NETHERRACK).count());
		assertTrue(plan.stream().filter(PortalSiteBuilder.NetherMutation::interior)
				.allMatch(mutation -> mutation.x() >= 5 && mutation.x() <= 25 && mutation.y() >= 64 && mutation.y() <= 66 && mutation.z() == 15));
		assertTrue(plan.stream().filter(PortalSiteBuilder.NetherMutation::interior)
				.noneMatch(PortalSiteBuilder.NetherMutation::preserveObsidian));
		Set<String> chunks = plan.stream()
				.map(mutation -> (mutation.x() >> 4) + "," + (mutation.z() >> 4))
				.collect(Collectors.toSet());
		assertEquals(Set.of("0,0", "1,0", "0,1", "1,1"), chunks);
		assertEquals(plan.size(), plan.stream()
				.map(mutation -> mutation.x() + "," + mutation.y() + "," + mutation.z())
				.collect(Collectors.toSet()).size());
	}

	@Test
	public void productionPlanSupportsTheOtherAxisAndNegativeChunks()
	{
		List<PortalSiteBuilder.NetherMutation> plan = PortalSiteBuilder.planNetherMutations(-16, 70, -16, false, 4, 3);

		assertEquals(18, plan.stream().filter(mutation -> mutation.material() == Material.OBSIDIAN).count());
		assertEquals(12, plan.stream().filter(PortalSiteBuilder.NetherMutation::interior).count());
		assertTrue(plan.stream().filter(PortalSiteBuilder.NetherMutation::interior).allMatch(mutation -> mutation.x() == -16));
		assertEquals(18, plan.stream().filter(mutation -> mutation.material() == Material.NETHERRACK).count());
		assertTrue(plan.stream().filter(mutation -> mutation.material() == Material.NETHERRACK)
				.allMatch(mutation -> mutation.x() >= -17 && mutation.x() <= -15
						&& mutation.y() == 69 && mutation.z() >= -20 && mutation.z() <= -13));
	}

	@Test
	public void productionPlanClearsBothArrivalFacesAcrossThePortalInterior()
	{
		List<PortalSiteBuilder.NetherMutation> plan = PortalSiteBuilder.planNetherMutations(0, 64, 0, true, 2, 3);
		Set<String> cleared = plan.stream()
				.filter(mutation -> mutation.material() == Material.AIR && !mutation.interior())
				.map(mutation -> mutation.x() + "," + mutation.y() + "," + mutation.z())
				.collect(Collectors.toSet());

		for(int x = -1; x <= 0; x++)
		{
			for(int y = 64; y <= 66; y++)
			{
				assertTrue(cleared.contains(x + "," + y + ",-1"));
				assertTrue(cleared.contains(x + "," + y + ",1"));
			}
		}
	}

	@Test
	public void productionPlanCarvesStandingRoomAcrossThePaddedLandingArea()
	{
		List<PortalSiteBuilder.NetherMutation> plan = PortalSiteBuilder.planNetherMutations(0, 64, 0, true, 2, 3);
		Set<String> cleared = plan.stream()
				.filter(mutation -> mutation.material() == Material.AIR && !mutation.interior())
				.map(mutation -> mutation.x() + "," + mutation.y() + "," + mutation.z())
				.collect(Collectors.toSet());
		assertTrue(plan.stream()
				.filter(mutation -> mutation.material() == Material.AIR && !mutation.interior())
				.noneMatch(PortalSiteBuilder.NetherMutation::preserveObsidian));

		for(int x = -3; x <= 2; x++)
		{
			for(int y = 64; y <= 66; y++)
			{
				assertTrue(cleared.contains(x + "," + y + ",-1"));
				assertTrue(cleared.contains(x + "," + y + ",1"));
			}
		}
	}

	@Test
	public void productionPlanCarvesThePaddedLandingAreaAlongTheOtherAxis()
	{
		List<PortalSiteBuilder.NetherMutation> plan = PortalSiteBuilder.planNetherMutations(0, 64, 0, false, 2, 3);
		Set<String> cleared = plan.stream()
				.filter(mutation -> mutation.material() == Material.AIR && !mutation.interior())
				.map(mutation -> mutation.x() + "," + mutation.y() + "," + mutation.z())
				.collect(Collectors.toSet());

		for(int z = -3; z <= 2; z++)
		{
			for(int y = 64; y <= 66; y++)
			{
				assertTrue(cleared.contains("-1," + y + "," + z));
				assertTrue(cleared.contains("1," + y + "," + z));
			}
		}
	}

	@Test
	public void tallFallbackCannotPunchThroughTheNetherRoof()
	{
		assertTrue(PortalSiteBuilder.netherFrameFits(104, 21, -64, 128));
		assertFalse(PortalSiteBuilder.netherFrameFits(118, 21, -64, 128));
	}
}
