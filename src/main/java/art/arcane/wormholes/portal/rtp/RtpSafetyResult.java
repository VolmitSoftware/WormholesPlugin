package art.arcane.wormholes.portal.rtp;

import java.util.Objects;

public record RtpSafetyResult(Code code, RtpDestination destination)
{
	public RtpSafetyResult
	{
		code = Objects.requireNonNull(code, "code");
		destination = Objects.requireNonNull(destination, "destination");
	}

	public static RtpSafetyResult safe(RtpDestination destination)
	{
		return new RtpSafetyResult(Code.SAFE, destination);
	}

	public static RtpSafetyResult rejected(Code code, RtpDestination destination)
	{
		if(code == Code.SAFE)
		{
			throw new IllegalArgumentException("rejection code must not be SAFE");
		}
		return new RtpSafetyResult(code, destination);
	}

	public boolean safe()
	{
		return code == Code.SAFE;
	}

	public enum Code
	{
		SAFE,
		WORLD_MISMATCH,
		ENVELOPE_TOO_LARGE,
		TOO_MANY_CHUNKS,
		TOO_MANY_REGIONS,
		WORLD_HEIGHT,
		WORLD_BORDER,
		NETHER_ROOF,
		MISSING_SNAPSHOT,
		INVALID_SNAPSHOT,
		LIQUID,
		HAZARD,
		BODY_COLLISION,
		UNSUPPORTED,
		END_VOID
	}
}
