package art.arcane.wormholes.portal;

import java.util.Locale;

public enum DimensionalPortalKind
{
	NONE,
	NETHER,
	END_SOURCE,
	END_ARRIVAL;

	public boolean isManagedEndPortal()
	{
		return this == END_SOURCE || this == END_ARRIVAL;
	}

	public boolean isManagedPortal()
	{
		return this != NONE;
	}

	public boolean isReceiverOnly()
	{
		return this == END_ARRIVAL;
	}

	public boolean isGenericDestination()
	{
		return !isManagedPortal();
	}

	public static DimensionalPortalKind fromName(String name)
	{
		if(name == null || name.isBlank())
		{
			return NONE;
		}
		try
		{
			return valueOf(name.trim().toUpperCase(Locale.ROOT));
		}
		catch(IllegalArgumentException ignored)
		{
			return NONE;
		}
	}
}
