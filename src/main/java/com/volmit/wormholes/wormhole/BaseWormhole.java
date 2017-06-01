package com.volmit.wormholes.wormhole;

import org.bukkit.entity.Entity;
import com.volmit.wormholes.Wormholes;
import com.volmit.wormholes.portal.LocalPortal;
import com.volmit.wormholes.portal.Portal;
import wraith.GList;

public abstract class BaseWormhole implements Wormhole
{
	private LocalPortal source;
	private Portal destination;
	private GList<WormholeFilter> filters;
	
	public BaseWormhole(LocalPortal source, Portal destination)
	{
		this.source = source;
		this.destination = destination;
		filters = new GList<WormholeFilter>();
	}
	
	@Override
	public LocalPortal getSource()
	{
		return source;
	}
	
	@Override
	public Portal getDestination()
	{
		return destination;
	}
	
	@Override
	public GList<WormholeFilter> getFilters()
	{
		return filters;
	}
	
	@Override
	public void push(Entity e)
	{
		for(WormholeFilter i : getFilters())
		{
			if(i.onFilter(this, e))
			{
				Wormholes.fx.throwBack(e, Wormholes.fx.throwBackVector(e, getSource()), getSource());
				return;
			}
		}
		
		Wormholes.fx.push(e, e.getVelocity(), getSource());
		onPush(e);
	}
	
	public abstract void onPush(Entity e);
}