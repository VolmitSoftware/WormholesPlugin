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

	private ILocalPortal resolveDestination()
	{
		if(portal instanceof ILocalPortal)
		{
			return (ILocalPortal) portal;
		}

		if(pendingDestinationId == null || Wormholes.portalManager == null)
		{
			return null;
		}

		ILocalPortal resolved = Wormholes.portalManager.getLocalPortal(pendingDestinationId);
		if(resolved != null)
		{
			portal = resolved;
		}

		return resolved;
	}
}
