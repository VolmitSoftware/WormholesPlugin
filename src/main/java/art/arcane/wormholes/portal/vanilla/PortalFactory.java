package art.arcane.wormholes.portal.vanilla;

import java.util.Set;
import java.util.UUID;

import org.bukkit.block.Block;

import art.arcane.wormholes.Wormholes;
import art.arcane.wormholes.portal.ILocalPortal;
import art.arcane.wormholes.portal.LocalPortal;
import art.arcane.wormholes.portal.PortalFrame;
import art.arcane.wormholes.portal.PortalStructure;
import art.arcane.wormholes.portal.PortalType;

public final class PortalFactory
{
	private PortalFactory()
	{
	}

	public static ILocalPortal createFromCells(Set<Block> planeCells, PortalFrame frame, PortalType type, String name)
	{
		if(planeCells == null || planeCells.isEmpty() || frame == null || Wormholes.portalManager == null)
		{
			return null;
		}
		PortalStructure structure = new PortalStructure();
		structure.setBlocks(planeCells);
		LocalPortal portal = new LocalPortal(UUID.randomUUID(), type, structure);
		portal.setFrame(frame);
		if(name != null)
		{
			portal.setName(name);
		}
		portal.open();
		portal.save();
		Wormholes.portalManager.addLocalPortal(portal);
		return portal;
	}

	public static void linkBidirectional(ILocalPortal a, ILocalPortal b)
	{
		if(a == null || b == null)
		{
			return;
		}
		a.setDestination(b);
		b.setDestination(a);
	}
}
