package art.arcane.wormholes.portal;

public enum NetworkViewQuality
{
	STANDARD("Standard", 64, 60, 10, 30),
	PERFORMANCE("Performance", 32, 100, 20, 10),
	BALANCED("Balanced", 64, 40, 5, 30),
	CINEMATIC("Cinematic", 96, 20, 2, 45),
	CUSTOM("Custom", -1, -1, -1, -1);

	private final String displayName;
	private final int depth;
	private final int heartbeatTicks;
	private final int entityIntervalTicks;
	private final int unsubscribeGraceSeconds;

	NetworkViewQuality(String displayName, int depth, int heartbeatTicks, int entityIntervalTicks, int unsubscribeGraceSeconds)
	{
		this.displayName = displayName;
		this.depth = depth;
		this.heartbeatTicks = heartbeatTicks;
		this.entityIntervalTicks = entityIntervalTicks;
		this.unsubscribeGraceSeconds = unsubscribeGraceSeconds;
	}

	public String getDisplayName()
	{
		return displayName;
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
