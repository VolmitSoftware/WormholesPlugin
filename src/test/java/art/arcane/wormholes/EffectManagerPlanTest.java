package art.arcane.wormholes;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

import art.arcane.wormholes.config.VisualQualityProfile;

public final class EffectManagerPlanTest
{
	@Test
	public void formationDisplayCapsStayBoundedByQuality()
	{
		assertEquals(6, EffectManager.formationDisplayCap(VisualQualityProfile.PERFORMANCE));
		assertEquals(10, EffectManager.formationDisplayCap(VisualQualityProfile.BALANCED));
		assertEquals(12, EffectManager.formationDisplayCap(VisualQualityProfile.AUTO));
		assertEquals(16, EffectManager.formationDisplayCap(VisualQualityProfile.CINEMATIC));
	}

	@Test
	public void openingRingPointsStayBoundedByQuality()
	{
		assertEquals(6, EffectManager.openingRingPoints(VisualQualityProfile.PERFORMANCE));
		assertEquals(8, EffectManager.openingRingPoints(VisualQualityProfile.BALANCED));
		assertEquals(10, EffectManager.openingRingPoints(VisualQualityProfile.AUTO));
		assertEquals(12, EffectManager.openingRingPoints(VisualQualityProfile.CINEMATIC));
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
	public void crackRadiusStaysOnRectangularPaneEllipse()
	{
		assertEquals(1.0D, EffectManager.ellipseRadius(1.0D, 5.0D, 0.0D), 0.000001D);
		assertEquals(5.0D, EffectManager.ellipseRadius(1.0D, 5.0D, Math.PI / 2.0D), 0.000001D);
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
