package art.arcane.wormholes;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.UUID;

import org.junit.jupiter.api.Test;

import art.arcane.wormholes.config.VisualQualityProfile;

public final class EffectManagerPlanTest
{
	@Test
	public void formationDisplayCapsStayBoundedByQuality()
	{
		assertEquals(8, EffectManager.formationDisplayCap(VisualQualityProfile.PERFORMANCE));
		assertEquals(16, EffectManager.formationDisplayCap(VisualQualityProfile.BALANCED));
		assertEquals(18, EffectManager.formationDisplayCap(VisualQualityProfile.AUTO));
		assertEquals(24, EffectManager.formationDisplayCap(VisualQualityProfile.CINEMATIC));
	}

	@Test
	public void openingRingPointsStayBoundedByQuality()
	{
		assertEquals(6, EffectManager.openingRingPoints(VisualQualityProfile.PERFORMANCE));
		assertEquals(10, EffectManager.openingRingPoints(VisualQualityProfile.BALANCED));
		assertEquals(12, EffectManager.openingRingPoints(VisualQualityProfile.AUTO));
		assertEquals(16, EffectManager.openingRingPoints(VisualQualityProfile.CINEMATIC));
	}

	@Test
	public void glassShardVelocityAlwaysMovesOutwardAcrossEveryPortalPlane()
	{
		assertOutward(0, 1, 2);
		assertOutward(1, 0, 2);
		assertOutward(2, 0, 1);
	}

	@Test
	public void closingEffectsScaleWithVisualQuality()
	{
		EffectManager.CloseEffectPlan performance = EffectManager.closeEffectPlan(VisualQualityProfile.PERFORMANCE);
		EffectManager.CloseEffectPlan cinematic = EffectManager.closeEffectPlan(VisualQualityProfile.CINEMATIC);

		assertTrue(performance.branches() < cinematic.branches());
		assertTrue(performance.segments() < cinematic.segments());
		assertTrue(performance.shards() < cinematic.shards());
	}

	@Test
	public void kawooshScalesWithVisualQuality()
	{
		EffectManager.KawooshPlan performance = EffectManager.kawooshPlan(VisualQualityProfile.PERFORMANCE);
		EffectManager.KawooshPlan cinematic = EffectManager.kawooshPlan(VisualQualityProfile.CINEMATIC);

		assertTrue(performance.arms() <= cinematic.arms());
		assertTrue(performance.armPoints() < cinematic.armPoints());
		assertTrue(performance.impactReverse() < cinematic.impactReverse());
		assertTrue(performance.impactEndRod() < cinematic.impactEndRod());
		assertTrue(performance.surgeCount() < cinematic.surgeCount());
	}

	@Test
	public void openingImpactSoundsStayBelowFullVolume()
	{
		EffectManager.OpeningSoundPlan plan = EffectManager.openingSoundPlan();

		assertEquals(0.65f, plan.frameVolume());
		assertEquals(0.75f, plan.portalImpactVolume());
		assertEquals(0.65f, plan.beaconImpactVolume());
		assertEquals(0.25f, plan.sonicBoomVolume());
		assertTrue(plan.frameVolume() < 1.0f);
		assertTrue(plan.portalImpactVolume() < 1.0f);
		assertTrue(plan.beaconImpactVolume() < 1.0f);
		assertTrue(plan.sonicBoomVolume() < 1.0f);
	}

	@Test
	public void crackRadiusStaysOnRectangularPaneEllipse()
	{
		assertEquals(1.0D, EffectManager.ellipseRadius(1.0D, 5.0D, 0.0D), 0.000001D);
		assertEquals(5.0D, EffectManager.ellipseRadius(1.0D, 5.0D, Math.PI / 2.0D), 0.000001D);
	}

	@Test
	public void vortexMarkerMatchesWithinRadius()
	{
		UUID world = UUID.randomUUID();
		assertTrue(EffectManager.vortexMarkerMatches(world, 10.0D, 64.0D, 10.0D, 2000L, 1000L, world, 12.0D, 65.0D, 11.0D));
	}

	@Test
	public void vortexMarkerMissesBeyondRadius()
	{
		UUID world = UUID.randomUUID();
		assertFalse(EffectManager.vortexMarkerMatches(world, 10.0D, 64.0D, 10.0D, 2000L, 1000L, world, 15.0D, 64.0D, 10.0D));
	}

	@Test
	public void vortexMarkerMissesWhenExpired()
	{
		UUID world = UUID.randomUUID();
		assertFalse(EffectManager.vortexMarkerMatches(world, 10.0D, 64.0D, 10.0D, 1000L, 1000L, world, 10.0D, 64.0D, 10.0D));
	}

	@Test
	public void vortexMarkerMissesOnWorldMismatch()
	{
		assertFalse(EffectManager.vortexMarkerMatches(UUID.randomUUID(), 10.0D, 64.0D, 10.0D, 2000L, 1000L, UUID.randomUUID(), 10.0D, 64.0D, 10.0D));
	}

	private static void assertOutward(int normalAxis, int planeA, int planeB)
	{
		double radialA = 0.6D;
		double radialB = 0.8D;
		double[] positive = EffectManager.outwardShardVelocity(normalAxis, planeA, planeB, radialA, radialB, 1.0D);
		double[] negative = EffectManager.outwardShardVelocity(normalAxis, planeA, planeB, radialA, radialB, -1.0D);
		assertTrue((positive[planeA] * radialA) + (positive[planeB] * radialB) > 0.0D);
		assertTrue((negative[planeA] * radialA) + (negative[planeB] * radialB) > 0.0D);
		assertTrue(positive[normalAxis] > 0.0D);
		assertTrue(negative[normalAxis] < 0.0D);
	}
}
