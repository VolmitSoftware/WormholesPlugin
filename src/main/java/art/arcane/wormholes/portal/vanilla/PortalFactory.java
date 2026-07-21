package art.arcane.wormholes.portal.vanilla;

import java.util.Set;
import java.util.UUID;

import org.bukkit.block.Block;

import art.arcane.wormholes.Wormholes;
import art.arcane.wormholes.portal.ILocalPortal;
import art.arcane.wormholes.portal.LocalPortal;
import art.arcane.wormholes.portal.DimensionalPortalKind;
import art.arcane.wormholes.portal.PortalFrame;
import art.arcane.wormholes.portal.ProjectionMode;
import art.arcane.wormholes.portal.PortalStructure;
import art.arcane.wormholes.portal.PortalType;

public final class PortalFactory
{
	private PortalFactory()
	{
	}

	public static ILocalPortal createFromCells(Set<Block> planeCells, PortalFrame frame, PortalType type, String name)
	{
		return createFromCells(planeCells, frame, type, name, DimensionalPortalKind.NONE);
	}

	public static ILocalPortal createFromCells(Set<Block> planeCells, PortalFrame frame, PortalType type, String name, DimensionalPortalKind kind)
	{
		return create(planeCells, frame, type, name, kind, true);
	}

	public static ILocalPortal createReceiverFromCells(Set<Block> planeCells, PortalFrame frame, PortalType type, String name)
	{
		return createReceiverFromCells(planeCells, frame, type, name, DimensionalPortalKind.NONE);
	}

	public static ILocalPortal createReceiverFromCells(Set<Block> planeCells, PortalFrame frame, PortalType type, String name, DimensionalPortalKind kind)
	{
		return create(planeCells, frame, type, name, kind, false);
	}

	private static ILocalPortal create(Set<Block> planeCells, PortalFrame frame, PortalType type, String name, DimensionalPortalKind kind, boolean active)
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
		portal.setDimensionalPortalKind(kind);
		if(active)
		{
			portal.open();
		}
		else
		{
			portal.setProjectionMode(ProjectionMode.OFF);
			portal.setOutgoingTraversalsEnabled(false);
			portal.setIncomingTraversalsEnabled(true);
		}
		portal.save();
		Wormholes.portalManager.addLocalPortal(portal);
		return portal;
	}

	public static synchronized boolean linkBidirectional(ILocalPortal a, ILocalPortal b)
	{
		if(a == null || b == null || a == b || a.getId().equals(b.getId()) || !canLinkEndpoint(a) || !canLinkEndpoint(b))
		{
			return false;
		}
		if(a.getDimensionalCounterpartId() != null || a.getTunnel() != null
				|| b.getDimensionalCounterpartId() != null || b.getTunnel() != null)
		{
			return false;
		}
		a.setDestination(b);
		b.setDestination(a);
		if(!canLinkEndpoint(a) || !canLinkEndpoint(b))
		{
			a.unlink();
			b.unlink();
			return false;
		}
		a.setDimensionalCounterpartId(b.getId());
		b.setDimensionalCounterpartId(a.getId());
		if(canLinkEndpoint(a) && canLinkEndpoint(b))
		{
			return true;
		}
		a.unlink();
		b.unlink();
		return false;
	}

	public static synchronized boolean linkOneWay(ILocalPortal source, ILocalPortal destination)
	{
		if(source == null || destination == null || source == destination || source.getId().equals(destination.getId())
				|| !canLinkEndpoint(source) || !canLinkEndpoint(destination))
		{
			return false;
		}
		if(source.getDimensionalCounterpartId() != null || source.getTunnel() != null
				|| destination.getDimensionalCounterpartId() != null || destination.getTunnel() != null)
		{
			return false;
		}
		source.setDestination(destination);
		source.setOutgoingTraversalsEnabled(true);
		source.setIncomingTraversalsEnabled(false);
		destination.unlink();
		destination.setProjectionMode(ProjectionMode.OFF);
		destination.setOutgoingTraversalsEnabled(false);
		destination.setIncomingTraversalsEnabled(true);
		source.setDimensionalCounterpartId(destination.getId());
		destination.setDimensionalCounterpartId(source.getId());
		if(canLinkEndpoint(source) && canLinkEndpoint(destination))
		{
			return true;
		}
		source.unlink();
		destination.unlink();
		return false;
	}

	private static boolean canLinkEndpoint(ILocalPortal portal)
	{
		if(portal.getType() == PortalType.RTP || portal.isDestroyed())
		{
			return false;
		}
		return Wormholes.portalManager == null || Wormholes.portalManager.getLocalPortal(portal.getId()) == portal;
	}
}
