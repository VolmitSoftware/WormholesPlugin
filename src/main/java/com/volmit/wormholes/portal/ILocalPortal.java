package com.volmit.wormholes.portal;

import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.entity.Player;
import org.bukkit.event.Listener;

import com.volmit.wormholes.inventory.Window;
import com.volmit.wormholes.util.AxisAlignedBB;
import com.volmit.wormholes.util.Direction;

public interface ILocalPortal extends IPortal, IPersistant, Listener
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

	public void receive(Traversive t);

	public void setDirection(Direction d);

	public ITunnel getTunnel();

	public boolean hasTunnel();

	public void setDestination(IPortal portal);

	public void destroy();

	public String getRouter(boolean dark);

	public String getRouter(boolean dark, IPortal source);

	public void uiOpenPortalMenu(Player p);

	public Window uiCreatePortalMenu(Player p);

	public void uiChooseDestination(Player p);

	public void uiChangeName(Player p);

	public void uiChangeDirection(Player p);

	public boolean isGateway();

	public boolean supportsProjections();

	public World getWorld();

	public Location getCenter();

	public AxisAlignedBB getArea();
}
