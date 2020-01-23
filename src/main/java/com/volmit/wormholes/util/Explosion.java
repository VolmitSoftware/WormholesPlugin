package com.volmit.wormholes.util;

import org.bukkit.Location;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityExplodeEvent;

import com.volmit.wormholes.Wormholes;

public class Explosion implements Listener
{
	private float power;
	private boolean pushBlocks;

	public Explosion()
	{
		power = 3f;
		pushBlocks = false;
	}

	public Explosion power(float power)
	{
		this.power = power;
		return this;
	}

	public Explosion pushBlocks()
	{
		pushBlocks = true;
		return this;
	}

	public void boom(Location at)
	{
		at.getWorld().createExplosion(at, power);

		if(pushBlocks)
		{
			Wormholes.instance.registerListener(this);
		}
	}

	@EventHandler(priority = EventPriority.HIGHEST)
	public void on(EntityExplodeEvent e)
	{
		e.setCancelled(true);
		Wormholes.instance.unregisterListener(this);
	}
}
