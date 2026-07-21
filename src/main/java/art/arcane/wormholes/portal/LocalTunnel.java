package art.arcane.wormholes.portal;

import art.arcane.wormholes.Wormholes;
import art.arcane.wormholes.util.JSONObject;

public class LocalTunnel extends Tunnel
{
	public LocalTunnel(ILocalPortal portal)
	{
		super(portal, TunnelType.LOCAL);
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

	@Override
	public void loadJSON(JSONObject j)
	{
		super.loadJSON(j);
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
