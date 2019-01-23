package com.volmit.wormholes.portal;

import org.bukkit.Location;

public class PortalBlock
{
	private final PortalType type;
	private final Location location;

	public PortalBlock(PortalType type, Location location)
	{
		this.type = type;
		this.location = location.clone();
	}

	public PortalType getType()
	{
		return type;
	}

	public Location getLocation()
	{
		return location.clone();
	}

	@Override
	public int hashCode()
	{
		final int prime = 31;
		int result = 1;
		result = prime * result + ((location == null) ? 0 : location.hashCode());
		result = prime * result + ((type == null) ? 0 : type.hashCode());
		return result;
	}

	@Override
	public boolean equals(Object obj)
	{
		if(this == obj)
		{
			return true;
		}
		if(obj == null)
		{
			return false;
		}
		if(!(obj instanceof PortalBlock))
		{
			return false;
		}
		PortalBlock other = (PortalBlock) obj;
		if(location == null)
		{
			if(other.location != null)
			{
				return false;
			}
		}
		else if(!location.equals(other.location))
		{
			return false;
		}
		if(type != other.type)
		{
			return false;
		}
		return true;
	}
}
