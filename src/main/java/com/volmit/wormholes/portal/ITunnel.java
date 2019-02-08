package com.volmit.wormholes.portal;

import com.volmit.wormholes.util.JSONObject;

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
				LocalTunnel tux = new LocalTunnel(null);
				tux.loadJSON(j);
				return tux;
			case LOCAL:
				LocalTunnel tu = new LocalTunnel(null);
				tu.loadJSON(j);
				return tu;
			case UNIVERSAL:
				// TODO NOT YET IMPLEMENTED
				break;
		}

		return null;
	}
}
