package art.arcane.wormholes;

import java.util.Set;
import java.util.UUID;
import java.util.function.Consumer;

import org.bukkit.ChatColor;
import org.bukkit.Location;
import org.bukkit.block.Block;
import org.bukkit.entity.Player;
import org.bukkit.event.Listener;
import org.bukkit.util.Vector;

import art.arcane.wormholes.portal.ILocalPortal;
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

	public void constructPortal(Player player, Set<Block> blocks, PortalType type, Direction d)
	{
		constructPortal(player, blocks, type, d, player == null ? null : player.getLocation().getDirection());
	}

	public void constructPortal(Player player, Set<Block> blocks, PortalType type, Direction d, Vector look)
	{
		constructPortal(player, blocks, type, d, look, null);
	}

	public void constructPortal(Player player, Set<Block> blocks, PortalType type, Direction d, Vector look, Consumer<ILocalPortal> onCreated)
	{
		if(blocks == null || blocks.isEmpty())
		{
			return;
		}

		Block firstBlock = blocks.iterator().next();
		Location anchor = firstBlock.getLocation();

		FoliaScheduler.runRegion(Wormholes.instance, anchor, () -> performConstruct(blocks, type, d, look, onCreated), 25L);
	}

	private void performConstruct(Set<Block> blocks, PortalType type, Direction d, Vector look, Consumer<ILocalPortal> onCreated)
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

		boolean success = isFlatPortalArea(c.depth(Axis.X), c.depth(Axis.Y), c.depth(Axis.Z));

		if(success)
		{
			Location center = c.getCenter();
			PortalStructure s = new PortalStructure();
			s.setBlocks(blocks);
			ILocalPortal portal = createPortal(s, type);
			portal.setFrame(PortalFrame.fromDirectionAndLook(d, look));
			portal.open();
			portal.save();
			Wormholes.portalManager.addLocalPortal(portal);
			if(onCreated != null)
			{
				onCreated.accept(portal);
			}
			Wormholes.effectManager.playNotificationSuccess(ChatColor.GREEN + "Portal opened. Hold the wand and CLICK the portal to configure.", center);
			Wormholes.effectManager.playPortalOpenClimax(center.getWorld(), center, c.depth(Axis.X), c.depth(Axis.Y), c.depth(Axis.Z));
			return;
		}

		Wormholes.effectManager.playNotificationFail(ChatColor.RED + "Portal shape must be one connected flat plane.", new KList<Block>(blocks).getRandom().getLocation());
		Wormholes.effectManager.playPortalFailOpen(blocks);
		Wormholes.blockManager.refund(blocks, type);
	}

	static boolean isFlatPortalArea(int xDepth, int yDepth, int zDepth)
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

		return flatAxes == 1;
	}

	private ILocalPortal createPortal(PortalStructure s, PortalType type)
	{
		return new LocalPortal(UUID.randomUUID(), type, s);
	}

}
