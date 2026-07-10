package art.arcane.wormholes.portal;

public enum PortalTravelMode
{
	BOTH("Both Ways", true, true),
	OUTBOUND("Outbound Only", true, false),
	INBOUND("Inbound Only", false, true),
	LOCKED("Locked", false, false);

	private static final PortalTravelMode[] CYCLE = values();
	private final String displayName;
	private final boolean outgoing;
	private final boolean incoming;

	PortalTravelMode(String displayName, boolean outgoing, boolean incoming)
	{
		this.displayName = displayName;
		this.outgoing = outgoing;
		this.incoming = incoming;
	}

	public String getDisplayName()
	{
		return displayName;
	}

	public boolean allowsOutgoing()
	{
		return outgoing;
	}

	public boolean allowsIncoming()
	{
		return incoming;
	}

	public PortalTravelMode next()
	{
		return CYCLE[(ordinal() + 1) % CYCLE.length];
	}

	public static PortalTravelMode from(boolean outgoing, boolean incoming)
	{
		if(outgoing)
		{
			return incoming ? BOTH : OUTBOUND;
		}
		return incoming ? INBOUND : LOCKED;
	}
}
