package com.volmit.wormholes.service;

import org.bukkit.entity.Player;
import com.volmit.wormholes.Wormholes;
import com.volmit.wormholes.portal.LocalPortal;
import com.volmit.wormholes.portal.Portal;
import com.volmit.wormholes.projection.ProjectionSet;
import wraith.GList;
import wraith.GMap;

public class PortalRegistry
{
	protected GList<Portal> destroyQueue;
	protected GList<Portal> localPortals;
	protected GMap<String, GList<Portal>> mutexPortals;
	
	public PortalRegistry()
	{
		localPortals = new GList<Portal>();
		destroyQueue = new GList<Portal>();
		mutexPortals = new GMap<String, GList<Portal>>();
	}
	
	public GList<Portal> getDestroyQueue()
	{
		return destroyQueue;
	}
	
	public GList<Portal> getLocalPortals()
	{
		return localPortals;
	}
	
	public GMap<String, GList<Portal>> getMutexPortals()
	{
		return mutexPortals;
	}
	
	public ProjectionSet getOtherLocalPortals(Portal local)
	{
		ProjectionSet set = new ProjectionSet();
		
		for(Portal i : getLocalPortals())
		{
			if(!i.equals(local))
			{
				set.add(i.getPosition().getArea());
			}
		}
		
		return set;
	}
	
	public boolean isLookingAt(Player p, Portal portal)
	{
		return portal instanceof LocalPortal && ((LocalPortal) portal).isPlayerLookingAt(p);
	}
	
	public Portal getPortalLookingAt(Player p)
	{
		for(Portal i : Wormholes.host.getLocalPortals())
		{
			if(((LocalPortal) i).isPlayerLookingAt(p))
			{
				return i;
			}
		}
		
		return null;
	}
}