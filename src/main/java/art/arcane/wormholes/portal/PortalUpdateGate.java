package art.arcane.wormholes.portal;

import java.util.UUID;

public final class PortalUpdateGate
{
	public static final int IDLE_UPDATE_INTERVAL_TICKS = 10;

	private PortalUpdateGate()
	{
	}

	public static boolean isDue(boolean open, boolean attended, long driverTick, int staggerOffset)
	{
		return open || attended || (driverTick + staggerOffset) % IDLE_UPDATE_INTERVAL_TICKS == 0;
	}

	public static int staggerOffset(UUID portalId)
	{
		return Math.floorMod(portalId.hashCode(), IDLE_UPDATE_INTERVAL_TICKS);
	}
}
