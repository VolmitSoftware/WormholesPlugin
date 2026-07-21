package art.arcane.wormholes.portal;

import art.arcane.wormholes.Wormholes;

public class DimensionalTunnel extends Tunnel
{
	public DimensionalTunnel(ILocalPortal portal)
	{
		super(portal, TunnelType.DIMENSIONAL);
	}

	@Override
	public IPortal getDestination()
	{
		return resolveDestination();
	}

	@Override
	public void push(Traversive t)
	{
		ILocalPortal destination = resolveDestination();
		if(t != null && destination instanceof LocalPortal)
		{
			((LocalPortal) destination).receive(t);
		}
	}

	@Override
	public boolean isValid()
	{
		return resolveDestination() != null;
	}

	boolean hasRtpDestination()
	{
		ILocalPortal destination = findDestination();
		return destination != null && destination.getType() == PortalType.RTP;
	}

	private ILocalPortal resolveDestination()
	{
		ILocalPortal resolved = findDestination();
		if(resolved == null || resolved.getType() == PortalType.RTP)
		{
			return null;
		}
		portal = resolved;
		return resolved;
	}

	private ILocalPortal findDestination()
	{
		if(portal instanceof ILocalPortal localPortal)
		{
			return localPortal;
		}
		if(pendingDestinationId == null || Wormholes.portalManager == null)
		{
			return null;
		}
		return Wormholes.portalManager.getLocalPortal(pendingDestinationId);
	}
}
