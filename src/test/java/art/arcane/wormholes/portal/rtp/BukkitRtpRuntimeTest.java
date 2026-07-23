package art.arcane.wormholes.portal.rtp;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

public final class BukkitRtpRuntimeTest
{
	@Test
	public void degenerateEnvelopeAxesAreExpandedToAMinimalExtent()
	{
		double[] expanded = BukkitRtpRuntime.normalizedEnvelopeAxis(0.5D, 0.5D);
		assertTrue(expanded[1] - expanded[0] >= 0.01D);
		assertTrue(expanded[0] < 0.5D && 0.5D < expanded[1]);
	}

	@Test
	public void healthyEnvelopeAxesPassThroughUnchanged()
	{
		double[] intact = BukkitRtpRuntime.normalizedEnvelopeAxis(-0.3D, 0.3D);
		assertEquals(-0.3D, intact[0], 0.0D);
		assertEquals(0.3D, intact[1], 0.0D);
	}
}
