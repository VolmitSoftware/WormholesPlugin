package org.cyberpwn.vortex.event;

import org.bukkit.entity.Entity;
import org.cyberpwn.vortex.portal.Portal;

public class WormholePushEntityEvent extends PortalEvent
{
	private final Entity entity;
	
	public WormholePushEntityEvent(Portal portal, Entity entity)
	{
		super(portal);
		
		this.entity = entity;
	}
	
	public Entity getEntity()
	{
		return entity;
	}
}