package art.arcane.wormholes.portal.vanilla;

import java.util.HashSet;
import java.util.Set;

import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.BlockState;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.entity.EntityPortalEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerPortalEvent;
import org.bukkit.event.player.PlayerTeleportEvent;
import org.bukkit.event.world.PortalCreateEvent;

import art.arcane.volmlib.util.scheduling.FoliaScheduler;
import art.arcane.wormholes.Settings;
import art.arcane.wormholes.Wormholes;
import art.arcane.wormholes.portal.ILocalPortal;
import art.arcane.wormholes.portal.IPortal;
import art.arcane.wormholes.portal.PortalFrame;
import art.arcane.wormholes.portal.PortalStructure;
import art.arcane.wormholes.portal.PortalType;
import art.arcane.wormholes.util.Axis;
import art.arcane.wormholes.util.AxisAlignedBB;
import art.arcane.wormholes.util.Direction;

public final class VanillaPortalReplacer implements Listener
{
	private static final String NETHER_TAG = "Nether Portal";
	private static final String END_TAG = "End Portal";
	private static final int REUSE_RADIUS = 16;
	private static final int END_WINDOW_HALF = 1;
	private static final int END_WINDOW_HEIGHT = 2;
	private static final int END_ISLAND_X = 0;
	private static final int END_ISLAND_Z = 0;
	private static final int END_COUNTERPART_RISE = 20;
	private static final int END_CANCEL_RADIUS = 6;

	@EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
	public void onPortalCreate(PortalCreateEvent event)
	{
		if(!Settings.REPLACE_NETHER_AND_END_PORTALS)
		{
			return;
		}
		if(event.getReason() != PortalCreateEvent.CreateReason.FIRE)
		{
			return;
		}
		Set<Block> cells = new HashSet<Block>();
		World world = null;
		for(BlockState state : event.getBlocks())
		{
			if(state.getType() == Material.NETHER_PORTAL)
			{
				cells.add(state.getBlock());
				world = state.getWorld();
			}
		}
		if(cells.isEmpty() || world == null)
		{
			return;
		}
		World source = world;
		Block anchor = cells.iterator().next();
		Wormholes.w("[vanilla-portal] FIRE create: " + cells.size() + " nether cells in " + world.getName() + " @ " + anchor.getX() + "," + anchor.getY() + "," + anchor.getZ());
		FoliaScheduler.runRegion(Wormholes.instance, anchor.getLocation(), () -> buildNetherPair(source, cells), 2L);
	}

	private void buildNetherPair(World sourceWorld, Set<Block> cells)
	{
		try
		{
			if(alreadyCovered(cells))
			{
				Wormholes.w("[vanilla-portal] skipped: cells already covered by an existing Wormholes portal");
				return;
			}
			Direction normal = deriveNormal(cells);
			boolean alongX = normal.getAxis() == Axis.Z;
			int interiorWidth = interiorWidth(cells, alongX);
			int interiorHeight = interiorHeight(cells);
			ILocalPortal sourcePortal = PortalFactory.createFromCells(cells, PortalFrame.canonical(normal), PortalType.PORTAL, NETHER_TAG);
			if(sourcePortal == null)
			{
				Wormholes.w("[vanilla-portal] source portal creation returned null");
				return;
			}
			for(Block cell : cells)
			{
				cell.setType(Material.AIR, false);
			}
			World target = sourceWorld.getEnvironment() == World.Environment.NETHER ? WorldPairing.pairedOverworld(sourceWorld) : WorldPairing.pairedNether(sourceWorld);
			if(target == null)
			{
				Wormholes.w("[vanilla-portal] No paired " + (sourceWorld.getEnvironment() == World.Environment.NETHER ? "overworld" : "nether") + " world for " + sourceWorld.getName() + "; nether portal left one-sided.");
				return;
			}
			Location center = sourcePortal.getCenter();
			int tcx = WorldPairing.scaleHorizontal(sourceWorld, target, center.getBlockX());
			int tcz = WorldPairing.scaleHorizontal(sourceWorld, target, center.getBlockZ());
			int tcy = clampY(target, center.getBlockY());
			Wormholes.w("[vanilla-portal] source built (" + interiorWidth + "x" + interiorHeight + "); target=" + target.getName() + " @ " + tcx + "," + tcy + "," + tcz);

			ILocalPortal existing = findNearbyAuto(target, NETHER_TAG, tcx, tcz);
			if(existing != null)
			{
				PortalFactory.linkBidirectional(sourcePortal, existing);
				Wormholes.w("[vanilla-portal] reused existing counterpart, linked both ways");
				return;
			}

			target.getChunkAtAsync(tcx >> 4, tcz >> 4).whenComplete((chunk, error) ->
			{
				if(error != null || chunk == null)
				{
					Wormholes.w("[vanilla-portal] destination chunk load failed: " + error);
					return;
				}
				FoliaScheduler.runRegion(Wormholes.instance, target, tcx >> 4, tcz >> 4, () ->
				{
					try
					{
						ILocalPortal reuse = findNearbyAuto(target, NETHER_TAG, tcx, tcz);
						ILocalPortal counterpart = reuse;
						if(counterpart == null)
						{
							Set<Block> built = PortalSiteBuilder.buildNetherFrame(target, tcx, tcy, tcz, alongX, interiorWidth, interiorHeight);
							if(built.isEmpty())
							{
								Wormholes.w("[vanilla-portal] counterpart frame build produced no cells");
								return;
							}
							counterpart = PortalFactory.createFromCells(built, PortalFrame.canonical(normal), PortalType.PORTAL, NETHER_TAG);
						}
						if(counterpart != null)
						{
							PortalFactory.linkBidirectional(sourcePortal, counterpart);
							Wormholes.w("[vanilla-portal] counterpart built + linked both ways");
						}
					}
					catch(Throwable ex)
					{
						Wormholes.instance.getLogger().log(java.util.logging.Level.WARNING, "[vanilla-portal] counterpart build failed", ex);
					}
				});
			});
		}
		catch(Throwable ex)
		{
			Wormholes.instance.getLogger().log(java.util.logging.Level.WARNING, "[vanilla-portal] nether pair build failed", ex);
		}
	}

	@EventHandler(ignoreCancelled = true)
	public void onPlayerPortal(PlayerPortalEvent event)
	{
		if(!Settings.REPLACE_NETHER_AND_END_PORTALS)
		{
			return;
		}
		if(isWormholesCovered(event.getFrom()) || (isEndCause(event.getCause()) && isNearEndWindow(event.getFrom())))
		{
			event.setCancelled(true);
		}
	}

	@EventHandler(ignoreCancelled = true)
	public void onEntityPortal(EntityPortalEvent event)
	{
		if(!Settings.REPLACE_NETHER_AND_END_PORTALS)
		{
			return;
		}
		if(isWormholesCovered(event.getFrom()) || isNearEndWindow(event.getFrom()))
		{
			event.setCancelled(true);
		}
	}

	@EventHandler(ignoreCancelled = true)
	public void onPlayerInteract(PlayerInteractEvent event)
	{
		if(!Settings.REPLACE_NETHER_AND_END_PORTALS)
		{
			return;
		}
		if(event.getAction() != Action.RIGHT_CLICK_BLOCK || event.getClickedBlock() == null)
		{
			return;
		}
		if(event.getClickedBlock().getType() != Material.END_PORTAL_FRAME)
		{
			return;
		}
		if(event.getItem() == null || event.getItem().getType() != Material.ENDER_EYE)
		{
			return;
		}
		Location frame = event.getClickedBlock().getLocation();
		FoliaScheduler.runRegion(Wormholes.instance, frame, () -> detectAndBuildEnd(frame), 2L);
	}

	private void detectAndBuildEnd(Location frame)
	{
		try
		{
			World world = frame.getWorld();
			if(world == null || world.getEnvironment() == World.Environment.THE_END)
			{
				return;
			}
			int fy = frame.getBlockY();
			Set<Block> endPortalBlocks = new HashSet<Block>();
			int sumX = 0;
			int sumZ = 0;
			for(int dx = -4; dx <= 4; dx++)
			{
				for(int dz = -4; dz <= 4; dz++)
				{
					Block b = world.getBlockAt(frame.getBlockX() + dx, fy, frame.getBlockZ() + dz);
					if(b.getType() == Material.END_PORTAL)
					{
						endPortalBlocks.add(b);
						sumX += b.getX();
						sumZ += b.getZ();
					}
				}
			}
			int count = endPortalBlocks.size();
			if(count < 9)
			{
				return;
			}
			int ccx = Math.round((float) sumX / count);
			int ccz = Math.round((float) sumZ / count);
			Set<Block> window = PortalSiteBuilder.buildHorizontalWindow(world, ccx, fy, ccz, END_WINDOW_HALF);
			if(alreadyCovered(window))
			{
				return;
			}
			ILocalPortal sourcePortal = PortalFactory.createFromCells(window, PortalFrame.canonical(Direction.U), PortalType.PORTAL, END_TAG);
			if(sourcePortal == null)
			{
				return;
			}
			for(Block portalBlock : endPortalBlocks)
			{
				portalBlock.setType(Material.AIR, false);
			}
			Wormholes.w("[vanilla-portal] end portal formed @ " + ccx + "," + fy + "," + ccz + "; window built, starfield cleared");
			World end = WorldPairing.pairedEnd(world);
			if(end == null)
			{
				Wormholes.w("[vanilla-portal] No paired End world for " + world.getName() + "; end portal left one-sided.");
				return;
			}
			ILocalPortal existing = findNearbyAuto(end, END_TAG, END_ISLAND_X, END_ISLAND_Z);
			if(existing != null)
			{
				PortalFactory.linkBidirectional(sourcePortal, existing);
				Wormholes.w("[vanilla-portal] reused existing End counterpart, linked both ways");
				return;
			}
			end.getChunkAtAsync(END_ISLAND_X >> 4, END_ISLAND_Z >> 4).whenComplete((chunk, error) ->
			{
				if(error != null || chunk == null)
				{
					Wormholes.w("[vanilla-portal] End chunk load failed: " + error);
					return;
				}
				FoliaScheduler.runRegion(Wormholes.instance, end, END_ISLAND_X >> 4, END_ISLAND_Z >> 4, () ->
				{
					try
					{
						ILocalPortal reuse = findNearbyAuto(end, END_TAG, END_ISLAND_X, END_ISLAND_Z);
						ILocalPortal counterpart = reuse;
						if(counterpart == null)
						{
							int surfaceY = scanEndSurface(end, END_ISLAND_X, END_ISLAND_Z);
							int platformY = surfaceY + END_COUNTERPART_RISE;
							Set<Block> built = PortalSiteBuilder.buildEndCounterpart(end, END_ISLAND_X, END_ISLAND_Z, platformY, END_WINDOW_HALF, END_WINDOW_HEIGHT);
							if(built.isEmpty())
							{
								return;
							}
							counterpart = PortalFactory.createFromCells(built, PortalFrame.canonical(Direction.U), PortalType.PORTAL, END_TAG);
						}
						if(counterpart != null)
						{
							PortalFactory.linkBidirectional(sourcePortal, counterpart);
							Wormholes.w("[vanilla-portal] End counterpart built over island center + linked both ways");
						}
					}
					catch(Throwable ex)
					{
						Wormholes.instance.getLogger().log(java.util.logging.Level.WARNING, "[vanilla-portal] End counterpart build failed", ex);
					}
				});
			});
		}
		catch(Throwable ex)
		{
			Wormholes.instance.getLogger().log(java.util.logging.Level.WARNING, "[vanilla-portal] end pair build failed", ex);
		}
	}

	private static int scanEndSurface(World world, int x, int z)
	{
		int top = Math.min(world.getMaxHeight() - 1, 180);
		for(int y = top; y > world.getMinHeight(); y--)
		{
			if(!world.getBlockAt(x, y, z).getType().isAir())
			{
				return y;
			}
		}
		return world.getMinHeight() + 50;
	}

	private static boolean isEndCause(PlayerTeleportEvent.TeleportCause cause)
	{
		return cause == PlayerTeleportEvent.TeleportCause.END_PORTAL || cause == PlayerTeleportEvent.TeleportCause.END_GATEWAY;
	}

	private static boolean isNearEndWindow(Location loc)
	{
		if(loc == null || loc.getWorld() == null || Wormholes.portalManager == null)
		{
			return false;
		}
		for(ILocalPortal portal : Wormholes.portalManager.getLocalPortals())
		{
			if(!END_TAG.equals(portal.getName()))
			{
				continue;
			}
			PortalStructure structure = portal.getStructure();
			if(structure == null || !loc.getWorld().equals(structure.getWorld()))
			{
				continue;
			}
			Location c = portal.getCenter();
			if(c == null)
			{
				continue;
			}
			double dx = c.getX() - loc.getX();
			double dy = c.getY() - loc.getY();
			double dz = c.getZ() - loc.getZ();
			if(dx * dx + dy * dy + dz * dz <= END_CANCEL_RADIUS * END_CANCEL_RADIUS)
			{
				return true;
			}
		}
		return false;
	}

	@EventHandler(ignoreCancelled = true)
	public void onBlockBreak(BlockBreakEvent event)
	{
		if(!Settings.REPLACE_NETHER_AND_END_PORTALS)
		{
			return;
		}
		if(event.getBlock().getType() != Material.OBSIDIAN)
		{
			return;
		}
		ILocalPortal portal = findFramedPortal(event.getBlock());
		if(portal != null)
		{
			breakPortalPair(portal);
		}
	}

	private static ILocalPortal findFramedPortal(Block broken)
	{
		if(Wormholes.portalManager == null)
		{
			return null;
		}
		World world = broken.getWorld();
		for(ILocalPortal portal : Wormholes.portalManager.getLocalPortals())
		{
			PortalStructure structure = portal.getStructure();
			if(structure == null || structure.getWorld() == null || !world.equals(structure.getWorld()))
			{
				continue;
			}
			if(isOpenFrameBlock(broken, structure))
			{
				return portal;
			}
		}
		return null;
	}

	private static boolean isOpenFrameBlock(Block broken, PortalStructure structure)
	{
		int x = broken.getX();
		int y = broken.getY();
		int z = broken.getZ();
		if(structure.containsBlock(x, y, z))
		{
			return false;
		}
		AxisAlignedBB area = structure.getArea();
		if(area == null)
		{
			return false;
		}
		double sx = area.sizeX();
		double sy = area.sizeY();
		double sz = area.sizeZ();
		World world = broken.getWorld();
		if(sz <= sx && sz <= sy)
		{
			return adjacentAirCell(world, structure, x, y, z, true, true, false);
		}
		if(sy <= sx && sy <= sz)
		{
			return adjacentAirCell(world, structure, x, y, z, true, false, true);
		}
		return adjacentAirCell(world, structure, x, y, z, false, true, true);
	}

	private static boolean adjacentAirCell(World world, PortalStructure structure, int x, int y, int z, boolean varyX, boolean varyY, boolean varyZ)
	{
		for(int a = -1; a <= 1; a++)
		{
			for(int b = -1; b <= 1; b++)
			{
				if(a == 0 && b == 0)
				{
					continue;
				}
				int nx = x;
				int ny = y;
				int nz = z;
				if(varyX && varyY)
				{
					nx = x + a;
					ny = y + b;
				}
				else if(varyX && varyZ)
				{
					nx = x + a;
					nz = z + b;
				}
				else
				{
					ny = y + a;
					nz = z + b;
				}
				if(structure.containsBlock(nx, ny, nz) && world.getBlockAt(nx, ny, nz).getType().isAir())
				{
					return true;
				}
			}
		}
		return false;
	}

	private void breakPortalPair(ILocalPortal portal)
	{
		ILocalPortal counterpart = null;
		if(portal.hasTunnel())
		{
			IPortal destination = portal.getTunnel().getDestination();
			if(destination instanceof ILocalPortal && destination != portal)
			{
				counterpart = (ILocalPortal) destination;
			}
		}
		portal.destroy();
		if(counterpart != null)
		{
			counterpart.destroy();
		}
		Wormholes.w("[vanilla-portal] frame broken -> portal pair destroyed");
	}

	private static Direction deriveNormal(Set<Block> cells)
	{
		int minX = Integer.MAX_VALUE;
		int maxX = Integer.MIN_VALUE;
		int minZ = Integer.MAX_VALUE;
		int maxZ = Integer.MIN_VALUE;
		for(Block cell : cells)
		{
			minX = Math.min(minX, cell.getX());
			maxX = Math.max(maxX, cell.getX());
			minZ = Math.min(minZ, cell.getZ());
			maxZ = Math.max(maxZ, cell.getZ());
		}
		boolean flatX = minX == maxX;
		return flatX ? Direction.E : Direction.N;
	}

	private static int interiorWidth(Set<Block> cells, boolean alongX)
	{
		int min = Integer.MAX_VALUE;
		int max = Integer.MIN_VALUE;
		for(Block cell : cells)
		{
			int value = alongX ? cell.getX() : cell.getZ();
			min = Math.min(min, value);
			max = Math.max(max, value);
		}
		return max - min + 1;
	}

	private static int interiorHeight(Set<Block> cells)
	{
		int min = Integer.MAX_VALUE;
		int max = Integer.MIN_VALUE;
		for(Block cell : cells)
		{
			min = Math.min(min, cell.getY());
			max = Math.max(max, cell.getY());
		}
		return max - min + 1;
	}

	private static int clampY(World world, int desired)
	{
		int min = world.getMinHeight() + 5;
		int max = world.getEnvironment() == World.Environment.NETHER ? 118 : world.getMaxHeight() - 6;
		return Math.max(min, Math.min(max, desired));
	}

	private static ILocalPortal findNearbyAuto(World world, String tag, int x, int z)
	{
		if(Wormholes.portalManager == null)
		{
			return null;
		}
		for(ILocalPortal portal : Wormholes.portalManager.getLocalPortals())
		{
			PortalStructure structure = portal.getStructure();
			if(structure == null || !world.equals(structure.getWorld()))
			{
				continue;
			}
			if(!tag.equals(portal.getName()))
			{
				continue;
			}
			Location c = portal.getCenter();
			if(c == null)
			{
				continue;
			}
			double dx = c.getX() - x;
			double dz = c.getZ() - z;
			if(dx * dx + dz * dz <= REUSE_RADIUS * REUSE_RADIUS)
			{
				return portal;
			}
		}
		return null;
	}

	private static boolean alreadyCovered(Set<Block> cells)
	{
		if(Wormholes.portalManager == null || cells.isEmpty())
		{
			return false;
		}
		Location probe = cells.iterator().next().getLocation();
		for(ILocalPortal portal : Wormholes.portalManager.getLocalPortals())
		{
			PortalStructure structure = portal.getStructure();
			if(structure == null || !probe.getWorld().equals(structure.getWorld()))
			{
				continue;
			}
			if(structure.contains(probe))
			{
				return true;
			}
		}
		return false;
	}

	private static boolean isWormholesCovered(Location location)
	{
		if(location == null || location.getWorld() == null || Wormholes.portalManager == null)
		{
			return false;
		}
		for(ILocalPortal portal : Wormholes.portalManager.getLocalPortals())
		{
			PortalStructure structure = portal.getStructure();
			if(structure == null || !location.getWorld().equals(structure.getWorld()))
			{
				continue;
			}
			if(structure.contains(location))
			{
				return true;
			}
		}
		return false;
	}
}
