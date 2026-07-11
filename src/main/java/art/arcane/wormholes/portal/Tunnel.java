package art.arcane.wormholes.portal;

import java.util.UUID;

import art.arcane.wormholes.util.JSONObject;

public abstract class Tunnel implements ITunnel
{
	protected IPortal portal;
	protected UUID pendingDestinationId;
	private TunnelType type;

	public Tunnel(IPortal destination, TunnelType type)
	{
		this.portal = destination;
		this.type = type;
		pendingDestinationId = destination == null ? null : destination.getId();
	}

	@Override
	public IPortal getDestination()
	{
		return portal;
	}

	@Override
	public TunnelType getTunnelType()
	{
		return type;
	}

	@Override
	public abstract void push(Traversive t);

	@Override
	public void saveJSON(JSONObject j)
	{
		j.put("type", getTunnelType().name());
		UUID destinationId = getDestinationId();
		if(destinationId != null)
		{
			j.put("destination", destinationId.toString());
		}
	}

	@Override
	public void loadJSON(JSONObject j)
	{
		type = TunnelType.valueOf(j.getString("type"));
		pendingDestinationId = j.has("destination") ? UUID.fromString(j.getString("destination")) : null;
	}

	@Override
	public JSONObject toJSON()
	{
		JSONObject o = new JSONObject();
		saveJSON(o);

		return o;
	}

	@Override
	public UUID getDestinationId()
	{
		return portal == null ? pendingDestinationId : portal.getId();
	}
}
