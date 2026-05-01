package art.arcane.wormholes.portal;

import java.util.UUID;

import art.arcane.wormholes.Wormholes;
import art.arcane.wormholes.util.JSONObject;
import art.arcane.volmlib.util.scheduling.S;

public class LocalTunnel extends Tunnel
{
	public LocalTunnel(ILocalPortal portal)
	{
		super(portal, TunnelType.LOCAL);
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

	@Override
	public void loadJSON(JSONObject j)
	{
		super.loadJSON(j);
		UUID id = UUID.fromString(j.getString("destination"));

		new S()
		{
			@Override
			public void run()
			{
				for(ILocalPortal i : Wormholes.portalManager.getLocalPortals())
				{
					if(i.getId().equals(id))
					{
						portal = i;
					}
				}
			}
		};
	}
}
