package art.arcane.wormholes.portal.rtp;

import java.util.Objects;

public record RtpDestination(String worldKey, int blockX, int feetY, int blockZ, long generation, int attempt)
{
	public RtpDestination
	{
		worldKey = Objects.requireNonNull(worldKey, "worldKey");
		if(worldKey.isBlank())
		{
			throw new IllegalArgumentException("worldKey must not be blank");
		}
		if(attempt < 0)
		{
			throw new IllegalArgumentException("attempt must be non-negative");
		}
	}
}
