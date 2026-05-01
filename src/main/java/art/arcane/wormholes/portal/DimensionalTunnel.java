package art.arcane.wormholes.portal;

import art.arcane.wormholes.Wormholes;

public class DimensionalTunnel extends Tunnel
{
	public DimensionalTunnel(ILocalPortal portal)
	{
		super(portal, TunnelType.DIMENSIONAL);
	}

	@Override
	public void push(Traversive t)
	{
		if(t != null)
		{
			((LocalPortal) getDestination()).receive(t);
		}
	}

	@Override
	public boolean isValid()
	{
		return Wormholes.portalManager.hasLocalPortal(getDestination().getId());
	}
}
