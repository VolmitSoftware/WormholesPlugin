package art.arcane.wormholes;

import java.util.Set;
import java.util.UUID;

import org.bukkit.ChatColor;
import org.bukkit.Location;
import org.bukkit.block.Block;
import org.bukkit.event.Listener;
import org.bukkit.util.Vector;

import art.arcane.wormholes.portal.LocalPortal;
import art.arcane.wormholes.portal.PortalFrame;
import art.arcane.wormholes.portal.PortalStructure;
import art.arcane.wormholes.portal.PortalType;
import art.arcane.wormholes.util.Axis;
import art.arcane.wormholes.util.Cuboid;
import art.arcane.wormholes.util.Direction;
import art.arcane.volmlib.util.collection.KList;
import art.arcane.volmlib.util.scheduling.FoliaScheduler;

public class ConstructionManager implements Listener
{
	public ConstructionManager()
	{
		Wormholes.v("Starting Construction Manager");
	}

	public boolean constructPortal(UUID ownerId, Set<Block> blocks, PortalType type, Vector look)
	{
		if(blocks == null || blocks.isEmpty())
		{
			return false;
		}

		Block firstBlock = blocks.iterator().next();
		Location anchor = firstBlock.getLocation();
		return FoliaScheduler.runRegion(Wormholes.instance, anchor, () -> performConstruct(blocks, type, look, ownerId), 25L);
	}

	private void performConstruct(Set<Block> blocks, PortalType type, Vector look, UUID ownerId)
	{
		Cuboid c = null;

		for(Block i : blocks)
		{
			if(c == null)
			{
				c = new Cuboid(i.getLocation());
			}
			else
			{
				c = c.getBoundingCuboid(new Cuboid(i.getLocation()));
			}
		}

		if(c == null)
		{
			return;
		}

		int xDepth = c.depth(Axis.X);
		int yDepth = c.depth(Axis.Y);
		int zDepth = c.depth(Axis.Z);

		if(isCoplanarPortalArea(xDepth, yDepth, zDepth))
		{
			Location center = c.getCenter();
			double lookX = look == null ? 0.0D : look.getX();
			double lookY = look == null ? 0.0D : look.getY();
			double lookZ = look == null ? -1.0D : look.getZ();
			Direction normal = derivePortalNormal(xDepth, yDepth, zDepth, lookX, lookY, lookZ);
			PortalStructure s = new PortalStructure();
			s.setBlocks(blocks);
			LocalPortal portal = createPortal(s, type);
			if(ownerId != null)
			{
				portal.setOwner(ownerId);
			}
			portal.setFrame(PortalFrame.fromDirectionAndLook(normal, look));
			portal.open();
			portal.save();
			Wormholes.portalManager.addLocalPortal(portal);
			Wormholes.effectManager.playNotificationSuccess(ChatColor.GREEN + "Portal opened. Hold the wand and CLICK the portal to configure.", center);
			return;
		}

		Wormholes.effectManager.playNotificationFail(ChatColor.RED + "Portal must lie flat on one wall, floor, or ceiling.", new KList<Block>(blocks).getRandom().getLocation());
		Wormholes.effectManager.playPortalFailOpen(blocks);
		Wormholes.blockManager.refund(blocks, type);
	}

	static boolean isCoplanarPortalArea(int xDepth, int yDepth, int zDepth)
	{
		int flatAxes = 0;
		if(xDepth == 0)
		{
			flatAxes++;
		}
		if(yDepth == 0)
		{
			flatAxes++;
		}
		if(zDepth == 0)
		{
			flatAxes++;
		}

		return flatAxes >= 1;
	}

	static Direction derivePortalNormal(int xDepth, int yDepth, int zDepth, double lookX, double lookY, double lookZ)
	{
		double ax = xDepth == 0 ? Math.abs(lookX) : -1.0D;
		double ay = yDepth == 0 ? Math.abs(lookY) : -1.0D;
		double az = zDepth == 0 ? Math.abs(lookZ) : -1.0D;

		if(ax >= ay && ax >= az)
		{
			return lookX >= 0.0D ? Direction.E : Direction.W;
		}

		if(ay >= az)
		{
			return lookY >= 0.0D ? Direction.U : Direction.D;
		}

		return lookZ >= 0.0D ? Direction.S : Direction.N;
	}

	private LocalPortal createPortal(PortalStructure s, PortalType type)
	{
		return new LocalPortal(UUID.randomUUID(), type, s);
	}

}
