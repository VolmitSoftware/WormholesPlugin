package art.arcane.wormholes.portal;

import art.arcane.wormholes.util.JSONObject;

public interface ITunnel extends IWritable
{
	public IPortal getDestination();

	public TunnelType getTunnelType();

	public void push(Traversive t);

	public boolean isValid();

	public static ITunnel createTunnel(JSONObject j)
	{
		TunnelType t = TunnelType.valueOf(j.getString("type"));

		switch(t)
		{
			case DIMENSIONAL:
				DimensionalTunnel tux = new DimensionalTunnel(null);
				tux.loadJSON(j);
				return tux;
			case LOCAL:
				LocalTunnel tu = new LocalTunnel(null);
				tu.loadJSON(j);
				return tu;
			case UNIVERSAL:
				UniversalTunnel tun = new UniversalTunnel(null);
				tun.loadJSON(j);
				return tun;
		}

		return null;
	}
}
