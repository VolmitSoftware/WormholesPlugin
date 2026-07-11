package art.arcane.wormholes.portal.vanilla;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

import org.junit.jupiter.api.Test;

public final class VanillaPortalReplacerEndPlanTest
{
	@Test
	public void arrivalIsOffsetAndTenBlocksAboveSurface()
	{
		VanillaPortalReplacer.EndDestinationPlan plan = VanillaPortalReplacer.endDestinationPlan(64, -64, 320);

		assertEquals(12, plan.x());
		assertEquals(74, plan.y());
		assertEquals(9, plan.z());
		double distanceFromCenter = Math.hypot(plan.x(), plan.z());
		assertTrue(distanceFromCenter >= 15.0D && distanceFromCenter <= 17.0D);
	}

	@Test
	public void arrivalHeightStaysInsideWorldBounds()
	{
		assertEquals(316, VanillaPortalReplacer.endDestinationPlan(315, -64, 320).y());
		assertEquals(-59, VanillaPortalReplacer.endDestinationPlan(-100, -64, 320).y());
	}

	@Test
	public void primaryTargetsStayOffsetDistinctAndInsideSingleChunks()
	{
		List<VanillaPortalReplacer.EndTarget> targets = VanillaPortalReplacer.primaryEndTargets();

		assertEquals(16, targets.size());
		assertEquals(16, new HashSet<VanillaPortalReplacer.EndTarget>(targets).size());
		for(VanillaPortalReplacer.EndTarget target : targets)
		{
			double radius = Math.hypot(target.x(), target.z());
			assertTrue(radius >= 14.5D && radius <= 15.5D);
			assertEquals((target.x() - 1) >> 4, (target.x() + 1) >> 4);
			assertEquals((target.z() - 1) >> 4, (target.z() + 1) >> 4);
		}
	}

	@Test
	public void selectorAdvancesPastOccupiedTargetsAndHasAnOffsetFallback()
	{
		Set<VanillaPortalReplacer.EndTarget> occupied = new HashSet<VanillaPortalReplacer.EndTarget>();
		VanillaPortalReplacer.EndTarget first = VanillaPortalReplacer.primaryEndTargets().get(0);
		occupied.add(first);

		assertEquals(VanillaPortalReplacer.primaryEndTargets().get(1), VanillaPortalReplacer.selectEndTarget(target -> !occupied.contains(target)));
		occupied.addAll(VanillaPortalReplacer.primaryEndTargets());
		VanillaPortalReplacer.EndTarget fallback = VanillaPortalReplacer.selectEndTarget(target -> !occupied.contains(target));
		assertEquals(new VanillaPortalReplacer.EndTarget(20, 20), fallback);
		assertTrue(Math.hypot(fallback.x(), fallback.z()) > 20.0D);
	}

	@Test
	public void fallbackTargetsNeverCrossAChunkBoundary()
	{
		VanillaPortalReplacer.EndTarget target = new VanillaPortalReplacer.EndTarget(20, 20);
		for(int i = 0; i < 16; i++)
		{
			assertTrue(VanillaPortalReplacer.endWindowFitsSingleChunk(target));
			target = VanillaPortalReplacer.nextEndFallbackTarget(target);
		}
		assertEquals(new VanillaPortalReplacer.EndTarget(36, 20),
				VanillaPortalReplacer.nextEndFallbackTarget(new VanillaPortalReplacer.EndTarget(28, 20)));
	}
}
