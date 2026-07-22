package art.arcane.wormholes.portal;

import art.arcane.wormholes.Wormholes;
import art.arcane.wormholes.localization.WormholesMessages;

public enum NetworkViewQuality
{
	STANDARD(64, 60, 10, 30),
	PERFORMANCE(32, 100, 20, 10),
	BALANCED(64, 40, 5, 30),
	CINEMATIC(96, 20, 2, 45),
	CUSTOM(-1, -1, -1, -1);

	private final int depth;
	private final int heartbeatTicks;
	private final int entityIntervalTicks;
	private final int unsubscribeGraceSeconds;

	NetworkViewQuality(int depth, int heartbeatTicks, int entityIntervalTicks, int unsubscribeGraceSeconds)
	{
		this.depth = depth;
		this.heartbeatTicks = heartbeatTicks;
		this.entityIntervalTicks = entityIntervalTicks;
		this.unsubscribeGraceSeconds = unsubscribeGraceSeconds;
	}

	public String getDisplayName()
	{
		return Wormholes.text().plain(switch(this)
		{
			case STANDARD -> WormholesMessages.PORTAL_LABEL_STANDARD;
			case PERFORMANCE -> WormholesMessages.PORTAL_LABEL_PERFORMANCE;
			case BALANCED -> WormholesMessages.PORTAL_LABEL_BALANCED;
			case CINEMATIC -> WormholesMessages.PORTAL_LABEL_CINEMATIC;
			case CUSTOM -> WormholesMessages.PORTAL_LABEL_CUSTOM;
		});
	}

	public int getDepth()
	{
		return depth;
	}

	public int getHeartbeatTicks()
	{
		return heartbeatTicks;
	}

	public int getEntityIntervalTicks()
	{
		return entityIntervalTicks;
	}

	public int getUnsubscribeGraceSeconds()
	{
		return unsubscribeGraceSeconds;
	}

	public NetworkViewQuality next()
	{
		return switch(this)
		{
			case STANDARD -> PERFORMANCE;
			case PERFORMANCE -> BALANCED;
			case BALANCED -> CINEMATIC;
			case CINEMATIC, CUSTOM -> STANDARD;
		};
	}

	public static NetworkViewQuality from(int depth, int heartbeatTicks, int entityIntervalTicks, int unsubscribeGraceSeconds)
	{
		for(NetworkViewQuality quality : values())
		{
			if(quality == CUSTOM)
			{
				continue;
			}
			if(quality.depth == depth
					&& quality.heartbeatTicks == heartbeatTicks
					&& quality.entityIntervalTicks == entityIntervalTicks
					&& quality.unsubscribeGraceSeconds == unsubscribeGraceSeconds)
			{
				return quality;
			}
		}
		return CUSTOM;
	}
}
