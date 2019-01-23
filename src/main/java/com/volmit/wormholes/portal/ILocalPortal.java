package com.volmit.wormholes.portal;

import org.bukkit.entity.Player;

public interface ILocalPortal extends IPortal
{
	public PortalStructure getStructure();

	public PortalType getType();

	public void update();

	public void close();

	public boolean isOpen();

	public void open();

	public void setOpen(boolean open);

	public void onLooking(Player p, boolean holdingWand);

	public void onWanded(Player p);

	public boolean isLookingAt(Player p);
}
