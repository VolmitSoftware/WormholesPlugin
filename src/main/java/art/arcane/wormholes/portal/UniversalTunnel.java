package art.arcane.wormholes.portal;

import java.util.UUID;

import art.arcane.wormholes.Wormholes;
import art.arcane.wormholes.network.NetworkManager;
import art.arcane.wormholes.network.RemotePortalRegistry;
import art.arcane.wormholes.util.JSONObject;

public class UniversalTunnel extends Tunnel
{
	private String serverName;

	public UniversalTunnel(IRemotePortal portal)
	{
		super(portal, TunnelType.UNIVERSAL);
		serverName = portal == null ? null : portal.getServer().getName();
	}

	public UniversalTunnel(String serverName, UUID destinationId)
	{
		super(null, TunnelType.UNIVERSAL);
		this.serverName = serverName;
		this.pendingDestinationId = destinationId;
	}

	@Override
	public IPortal getDestination()
	{
		return resolveDestination();
	}

	@Override
	public void push(Traversive t)
	{
	}

	public UUID getDestinationPortalId()
	{
		return pendingDestinationId;
	}

	@Override
	public boolean isValid()
	{
		RemotePortal destination = resolveDestination();
		if(destination == null)
		{
			return false;
		}

		NetworkManager network = Wormholes.networkManager;
		return network != null && network.isPeerReady(serverName);
	}

	@Override
	public void saveJSON(JSONObject j)
	{
		super.saveJSON(j);
		if(serverName != null)
		{
			j.put("server", serverName);
		}
	}

	@Override
	public void loadJSON(JSONObject j)
	{
		super.loadJSON(j);
		serverName = j.has("server") ? j.getString("server") : null;
	}

	public String getServerName()
	{
		return serverName;
	}

	private RemotePortal resolveDestination()
	{
		if(serverName == null || pendingDestinationId == null)
		{
			return null;
		}

		RemotePortalRegistry registry = Wormholes.remotePortalRegistry;
		return registry == null ? null : registry.get(serverName, pendingDestinationId);
	}

	@Override
	protected UUID getDestinationId()
	{
		return pendingDestinationId;
	}
}
