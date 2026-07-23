package art.arcane.wormholes.portal.rtp;

public final class RtpTraversalHoldPolicy
{
	public static final long TIMEOUT_MILLIS = 8_000L;
	public static final double ARRIVAL_DRIFT_SQUARED = 256.0D;
	public static final double RETREAT_FREE_DISTANCE = 0.25D;
	public static final double RETREAT_CANCEL_DRIFT_SQUARED = 4.0D;
	public static final double LEASH_DRIFT_SQUARED = 0.5625D;

	private RtpTraversalHoldPolicy()
	{
	}

	public enum Decision
	{
		STOP_ARRIVED,
		BOUNCE_FAILED,
		BOUNCE_TIMEOUT,
		CANCEL_RETREAT,
		HOLD_FREE,
		HOLD_PIN
	}

	public static Decision decide(
			boolean completed,
			boolean inFlight,
			boolean sameWorld,
			double sideDistance,
			double driftSquared,
			long heldMillis)
	{
		if(completed)
		{
			return Decision.STOP_ARRIVED;
		}
		if(!sameWorld || driftSquared > ARRIVAL_DRIFT_SQUARED)
		{
			return Decision.STOP_ARRIVED;
		}
		if(!inFlight)
		{
			return Decision.BOUNCE_FAILED;
		}
		if(heldMillis >= TIMEOUT_MILLIS)
		{
			return Decision.BOUNCE_TIMEOUT;
		}
		if(sideDistance > RETREAT_FREE_DISTANCE)
		{
			return driftSquared > RETREAT_CANCEL_DRIFT_SQUARED
					? Decision.CANCEL_RETREAT
					: Decision.HOLD_FREE;
		}
		return Decision.HOLD_PIN;
	}
}
