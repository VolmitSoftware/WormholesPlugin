package art.arcane.wormholes.portal;

import java.util.UUID;

import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.event.Listener;

import art.arcane.wormholes.util.AxisAlignedBB;
import art.arcane.wormholes.util.Direction;
import art.arcane.volmlib.util.inventorygui.Window;

public interface ILocalPortal extends IPortal, IPersistant, Listener
{
	public PortalStructure getStructure();

	public PortalType getType();

	public void setType(PortalType type);

	public void update();

	public void close();

	public boolean isOpen();

	public void open();

	public void setOpen(boolean open);

	public void onLooking(Player p, boolean holdingWand);

	public void onWanded(Player p);

	public boolean isLookingAt(Player p);

	public void receive(Traversive t);

	public Location computeExitTarget(Traversive t);

	public void completeRemoteArrival(Entity entity, Traversive t);

	public void rejectRemoteArrival(Entity entity, Traversive t);

	public void rejectDeparture(Entity entity, Traversive t);

	public boolean canDepart(Entity entity);

	public boolean canArrive(Entity entity);

	public void setDirection(Direction d);

	public void setFrame(PortalFrame frame);

	public ITunnel getTunnel();

	public boolean hasTunnel();

	public void setDestination(IPortal portal);

	public void linkRemote(String serverName, UUID portalId);

	public void unlink();

	public void destroy();

	public String getRouter(boolean dark);

	public String getRouter(boolean dark, IPortal source);

	public void uiOpenPortalMenu(Player p);

	public Window uiCreatePortalMenu(Player p);

	public void uiChooseMode(Player p);

	public void uiChooseDestination(Player p);

	public void uiChangeName(Player p);

	public void uiChangeDirection(Player p);

	public boolean isGateway();

	public boolean supportsProjections();

	public boolean isProjecting();

	public ProjectionMode getProjectionMode();

	public void setProjectionMode(ProjectionMode mode);

	public PortalPermissionMode getPermissionMode();

	public void setPermissionMode(PortalPermissionMode mode);

	public String getPermissionNode();

	public boolean isOutgoingTraversalsEnabled();

	public void setOutgoingTraversalsEnabled(boolean enabled);

	public boolean isIncomingTraversalsEnabled();

	public void setIncomingTraversalsEnabled(boolean enabled);

	public int getNetworkViewDepth();

	public void setNetworkViewDepth(int depth);

	public int getNetworkViewLateralPad();

	public void setNetworkViewLateralPad(int lateralPad);

	public int getNetworkViewHeartbeatTicks();

	public void setNetworkViewHeartbeatTicks(int ticks);

	public int getNetworkViewEntityIntervalTicks();

	public void setNetworkViewEntityIntervalTicks(int ticks);

	public int getNetworkViewUnsubscribeGraceSeconds();

	public void setNetworkViewUnsubscribeGraceSeconds(int seconds);

	public String getNetworkViewFallbackBlock();

	public void setNetworkViewFallbackBlock(String blockState);

	public AxisAlignedBB getView();

	public World getWorld();

	public Location getCenter();

	public AxisAlignedBB getArea();
}
