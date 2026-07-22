package art.arcane.wormholes.portal;

import art.arcane.wormholes.Wormholes;
import art.arcane.wormholes.localization.WormholesMessages;

public enum PortalTravelMode
{
	BOTH(true, true),
	OUTBOUND(true, false),
	INBOUND(false, true),
	LOCKED(false, false);

	private static final PortalTravelMode[] CYCLE = values();
	private final boolean outgoing;
	private final boolean incoming;

	PortalTravelMode(boolean outgoing, boolean incoming)
	{
		this.outgoing = outgoing;
		this.incoming = incoming;
	}

	public String getDisplayName()
	{
		return Wormholes.text().plain(switch(this)
		{
			case BOTH -> WormholesMessages.PORTAL_LABEL_BOTH_WAYS;
			case OUTBOUND -> WormholesMessages.PORTAL_LABEL_OUTBOUND_ONLY;
			case INBOUND -> WormholesMessages.PORTAL_LABEL_INBOUND_ONLY;
			case LOCKED -> WormholesMessages.PORTAL_LABEL_LOCKED;
		});
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
