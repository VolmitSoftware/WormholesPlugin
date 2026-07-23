package art.arcane.wormholes.portal.rtp;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

public final class RtpTraversalHoldPolicyTest
{
	@Test
	public void completedTraversalStopsBeforeAnyOtherCheck()
	{
		assertEquals(RtpTraversalHoldPolicy.Decision.STOP_ARRIVED,
				RtpTraversalHoldPolicy.decide(true, false, true, -0.5D, 0.1D, 0L));
		assertEquals(RtpTraversalHoldPolicy.Decision.STOP_ARRIVED,
				RtpTraversalHoldPolicy.decide(true, true, true, -0.5D, 0.1D,
						RtpTraversalHoldPolicy.TIMEOUT_MILLIS + 1_000L));
	}

	@Test
	public void worldChangeStopsTheHold()
	{
		assertEquals(RtpTraversalHoldPolicy.Decision.STOP_ARRIVED,
				RtpTraversalHoldPolicy.decide(false, true, false, 0.0D, Double.MAX_VALUE, 100L));
		assertEquals(RtpTraversalHoldPolicy.Decision.STOP_ARRIVED,
				RtpTraversalHoldPolicy.decide(false, false, false, 0.0D, Double.MAX_VALUE, 100L));
	}

	@Test
	public void farDriftReadsAsArrival()
	{
		assertEquals(RtpTraversalHoldPolicy.Decision.STOP_ARRIVED,
				RtpTraversalHoldPolicy.decide(false, true, true, -0.5D,
						RtpTraversalHoldPolicy.ARRIVAL_DRIFT_SQUARED + 1.0D, 100L));
	}

	@Test
	public void clearedFlagNearThePortalBouncesTheTraveler()
	{
		assertEquals(RtpTraversalHoldPolicy.Decision.BOUNCE_FAILED,
				RtpTraversalHoldPolicy.decide(false, false, true, -0.5D, 0.2D, 100L));
	}

	@Test
	public void exhaustedHoldTimesOut()
	{
		assertEquals(RtpTraversalHoldPolicy.Decision.BOUNCE_TIMEOUT,
				RtpTraversalHoldPolicy.decide(false, true, true, -0.5D, 0.2D,
						RtpTraversalHoldPolicy.TIMEOUT_MILLIS));
	}

	@Test
	public void retreatBeyondTheCancelMarginReleasesSilently()
	{
		assertEquals(RtpTraversalHoldPolicy.Decision.CANCEL_RETREAT,
				RtpTraversalHoldPolicy.decide(false, true, true, 1.0D,
						RtpTraversalHoldPolicy.RETREAT_CANCEL_DRIFT_SQUARED + 0.5D, 100L));
	}

	@Test
	public void gentleRetreatTowardTheFrontIsLeftFree()
	{
		assertEquals(RtpTraversalHoldPolicy.Decision.HOLD_FREE,
				RtpTraversalHoldPolicy.decide(false, true, true, 1.0D, 1.0D, 100L));
	}

	@Test
	public void committedTravelerIsPinned()
	{
		assertEquals(RtpTraversalHoldPolicy.Decision.HOLD_PIN,
				RtpTraversalHoldPolicy.decide(false, true, true, -0.5D, 0.4D, 100L));
		assertEquals(RtpTraversalHoldPolicy.Decision.HOLD_PIN,
				RtpTraversalHoldPolicy.decide(false, true, true,
						RtpTraversalHoldPolicy.RETREAT_FREE_DISTANCE, 0.4D, 100L));
	}
}
