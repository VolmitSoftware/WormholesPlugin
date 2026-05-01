package art.arcane.wormholes.portal;

import art.arcane.wormholes.util.JSONObject;

public abstract class Tunnel implements ITunnel
{
	protected IPortal portal;
	private TunnelType type;

	public Tunnel(IPortal destination, TunnelType type)
	{
		this.portal = destination;
		this.type = type;
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
		j.put("destination", portal.getId().toString());
	}

	@Override
	public void loadJSON(JSONObject j)
	{
		type = TunnelType.valueOf(j.getString("type"));
	}

	@Override
	public JSONObject toJSON()
	{
		JSONObject o = new JSONObject();
		saveJSON(o);

		return o;
	}
}
