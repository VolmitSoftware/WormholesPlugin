package art.arcane.wormholes.portal.vanilla;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Predicate;
import java.util.logging.Level;

import org.bukkit.Axis;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.BlockState;
import org.bukkit.block.data.Orientable;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockExplodeEvent;
import org.bukkit.event.entity.EntityExplodeEvent;
import org.bukkit.event.entity.EntityPortalEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerPortalEvent;
import org.bukkit.event.player.PlayerTeleportEvent;
import org.bukkit.event.world.PortalCreateEvent;
import org.bukkit.util.Vector;

import art.arcane.volmlib.util.scheduling.FoliaScheduler;
import art.arcane.wormholes.Settings;
import art.arcane.wormholes.Wormholes;
import art.arcane.wormholes.platform.WormholesPlatform;
import art.arcane.wormholes.portal.DimensionalPortalKind;
import art.arcane.wormholes.portal.ILocalPortal;
import art.arcane.wormholes.portal.PortalFrame;
import art.arcane.wormholes.portal.PortalStructure;
import art.arcane.wormholes.portal.PortalType;
import art.arcane.wormholes.util.AxisAlignedBB;
import art.arcane.wormholes.util.Direction;

public final class VanillaPortalReplacer implements Listener
{
	private static final String NETHER_TAG = "Nether Portal";
	private static final String END_TAG = "End Portal";
	private static final int END_REUSE_RADIUS = 8;
	private static final int END_WINDOW_HALF = 1;
	private static final int END_DESTINATION_X = 12;
	private static final int END_DESTINATION_Z = 9;
	private static final int[][] END_DESTINATIONS = new int[][] {
			{ 12, 9 }, { 9, 12 }, { 13, 7 }, { 7, 13 },
			{ -12, 9 }, { -9, 12 }, { -13, 7 }, { -7, 13 },
			{ -12, -9 }, { -9, -12 }, { -13, -7 }, { -7, -13 },
			{ 12, -9 }, { 9, -12 }, { 13, -7 }, { 7, -13 }
	};
	private static final int END_COUNTERPART_RISE = 10;
	private static final int END_CANCEL_RADIUS = 6;
	private static final int NETHER_EXISTING_FOOTPRINT_MARGIN = 2;
	private static final Object NETHER_TARGET_LOCK = new Object();
	private static final Set<NetherBuildTarget> PENDING_NETHER_TARGETS = new HashSet<NetherBuildTarget>();
	private static final Object END_TARGET_LOCK = new Object();
	private static final Set<EndBuildTarget> PENDING_END_TARGETS = new HashSet<EndBuildTarget>();
	private static final int[][] NETHER_BUILD_OFFSETS = new int[][] {
			{ 0, 0 }, { 1, 0 }, { -1, 0 }, { 0, 1 }, { 0, -1 },
			{ 1, 1 }, { 1, -1 }, { -1, 1 }, { -1, -1 },
			{ 2, 0 }, { -2, 0 }, { 0, 2 }, { 0, -2 },
			{ 2, 1 }, { 2, -1 }, { -2, 1 }, { -2, -1 },
			{ 1, 2 }, { -1, 2 }, { 1, -2 }, { -1, -2 }
	};
	private final Map<UUID, CachedFramePositions> framePositionCache = new ConcurrentHashMap<UUID, CachedFramePositions>();

	@EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
	public void onPortalCreate(PortalCreateEvent event)
	{
		if(!Settings.REPLACE_NETHER_AND_END_PORTALS)
		{
			return;
		}
		if(event.getReason() != PortalCreateEvent.CreateReason.FIRE
				&& event.getReason() != PortalCreateEvent.CreateReason.NETHER_PAIR)
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
		Wormholes.w("[vanilla-portal] " + event.getReason() + " create: " + cells.size() + " nether cells in " + world.getName() + " @ " + anchor.getX() + "," + anchor.getY() + "," + anchor.getZ());
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
			boolean alongX = normal.z() != 0;
			int interiorWidth = interiorWidth(cells, alongX);
			int interiorHeight = interiorHeight(cells);
			ILocalPortal sourcePortal = PortalFactory.createFromCells(cells, PortalFrame.canonical(normal), PortalType.PORTAL, NETHER_TAG, DimensionalPortalKind.NETHER);
			if(sourcePortal == null)
			{
				Wormholes.w("[vanilla-portal] source portal creation returned null");
				return;
			}
			World target = sourceWorld.getEnvironment() == World.Environment.NETHER ? WorldPairing.pairedOverworld(sourceWorld) : WorldPairing.pairedNether(sourceWorld);
			if(target == null)
			{
				destroyIfUnlinked(sourcePortal);
				Wormholes.w("[vanilla-portal] No paired " + (sourceWorld.getEnvironment() == World.Environment.NETHER ? "overworld" : "nether") + " world for " + sourceWorld.getName() + "; nether portal left one-sided.");
				return;
			}
			Location center = sourcePortal.getCenter();
			int tcx = WorldPairing.scaleHorizontal(sourceWorld, target, center.getBlockX());
			int tcz = WorldPairing.scaleHorizontal(sourceWorld, target, center.getBlockZ());
			int tcy = clampY(target, center.getBlockY());
			int reuseRadius = target.getEnvironment() == World.Environment.NETHER ? 16 : 128;
			Wormholes.w("[vanilla-portal] source built (" + interiorWidth + "x" + interiorHeight + "); target=" + target.getName() + " @ " + tcx + "," + tcy + "," + tcz);

			ILocalPortal existing = findNearbyAuto(target, NETHER_TAG, DimensionalPortalKind.NETHER, tcx, tcy, tcz, reuseRadius, true);
			if(existing != null)
			{
				if(PortalFactory.linkBidirectional(sourcePortal, existing))
				{
					clearPortalBlocks(cells, Material.NETHER_PORTAL);
					Wormholes.w("[vanilla-portal] reused existing counterpart, linked both ways");
					return;
				}
			}
			findPhysicalNetherPortalAsync(target, tcx, tcy, tcz, reuseRadius).whenComplete((physicalPortal, lookupError) ->
			{
				if(sourcePortal.isDestroyed())
				{
					return;
				}
				if(lookupError != null)
				{
					Wormholes.instance.getLogger().log(Level.WARNING, "[vanilla-portal] nearby physical portal lookup failed", lookupError);
				}
				Set<Block> reusable = physicalPortal == null ? Set.of() : physicalPortal;
				if(!reusable.isEmpty() && reusePhysicalNetherPortal(sourcePortal, cells, reusable))
				{
					return;
				}
				buildGeneratedNetherCounterpart(target, sourcePortal, cells, normal, alongX, interiorWidth, interiorHeight, tcx, tcy, tcz);
			});
		}
		catch(Throwable ex)
		{
			Wormholes.instance.getLogger().log(Level.WARNING, "[vanilla-portal] nether pair build failed", ex);
		}
	}

	private static boolean reusePhysicalNetherPortal(ILocalPortal sourcePortal, Set<Block> sourceCells, Set<Block> physicalPortal)
	{
		ILocalPortal counterpart = PortalFactory.createFromCells(physicalPortal, PortalFrame.canonical(deriveNormal(physicalPortal)), PortalType.PORTAL,
				NETHER_TAG, DimensionalPortalKind.NETHER);
		if(counterpart != null && PortalFactory.linkBidirectional(sourcePortal, counterpart))
		{
			clearPortalBlocks(physicalPortal, Material.NETHER_PORTAL);
			clearPortalBlocks(sourceCells, Material.NETHER_PORTAL);
			Wormholes.w("[vanilla-portal] reused physical vanilla counterpart, linked both ways");
			return true;
		}
		destroyIfUnlinked(counterpart);
		return false;
	}

	private static void buildGeneratedNetherCounterpart(World target, ILocalPortal sourcePortal, Set<Block> sourceCells, Direction normal,
			boolean alongX, int interiorWidth, int interiorHeight, int targetX, int targetY, int targetZ)
	{
		NetherBuildTarget buildTarget = reserveNetherBuildTarget(target, targetX, targetZ, interiorWidth);
		PortalSiteBuilder.buildNetherFrameAsync(target, buildTarget.x(), targetY, buildTarget.z(), alongX, interiorWidth, interiorHeight).whenComplete((built, buildError) ->
		{
			if(buildError != null || built == null || built.isEmpty())
			{
				releaseNetherBuildTarget(buildTarget);
				destroyIfUnlinked(sourcePortal);
				Wormholes.w("[vanilla-portal] counterpart frame build failed: " + buildError);
				return;
			}
			boolean registrationScheduled = FoliaScheduler.runRegion(Wormholes.instance, target, buildTarget.x() >> 4, buildTarget.z() >> 4, () ->
			{
				try
				{
					ILocalPortal counterpart = PortalFactory.createFromCells(built, PortalFrame.canonical(normal), PortalType.PORTAL, NETHER_TAG, DimensionalPortalKind.NETHER);
					if(counterpart == null)
					{
						destroyIfUnlinked(sourcePortal);
						return;
					}
					if(PortalFactory.linkBidirectional(sourcePortal, counterpart))
					{
						clearPortalBlocks(sourceCells, Material.NETHER_PORTAL);
						Wormholes.w("[vanilla-portal] counterpart frame built + linked both ways at " + buildTarget.x() + "," + buildTarget.z());
					}
					else
					{
						destroyIfUnlinked(counterpart);
						destroyIfUnlinked(sourcePortal);
					}
				}
				catch(Throwable ex)
				{
					destroyIfUnlinked(sourcePortal);
					Wormholes.instance.getLogger().log(Level.WARNING, "[vanilla-portal] counterpart build failed", ex);
				}
				finally
				{
					releaseNetherBuildTarget(buildTarget);
				}
			});
			if(!registrationScheduled)
			{
				releaseNetherBuildTarget(buildTarget);
				destroyIfUnlinked(sourcePortal);
			}
		});
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
			Set<Block> window = PortalSiteBuilder.horizontalWindowCells(world, ccx, fy, ccz, END_WINDOW_HALF);
			if(alreadyCovered(window))
			{
				return;
			}
			ILocalPortal sourcePortal = PortalFactory.createFromCells(window, PortalFrame.canonical(Direction.U), PortalType.PORTAL, END_TAG, DimensionalPortalKind.END_SOURCE);
			if(sourcePortal == null)
			{
				return;
			}
			Wormholes.w("[vanilla-portal] end portal formed @ " + ccx + "," + fy + "," + ccz + "; resolving one-way arrival");
			World end = WorldPairing.pairedEnd(world);
			if(end == null)
			{
				destroyIfUnlinked(sourcePortal);
				Wormholes.w("[vanilla-portal] No paired End world for " + world.getName() + "; end portal left one-sided.");
				return;
			}
			ILocalPortal existing = findReusableEndArrival(end);
			if(existing != null)
			{
				if(PortalFactory.linkOneWay(sourcePortal, existing))
				{
					clearPortalBlocks(endPortalBlocks, Material.END_PORTAL);
					Wormholes.w("[vanilla-portal] reused existing End arrival, linked one way");
					return;
				}
			}
			EndBuildTarget reservation = reserveEndBuildTarget(end);
			EndTarget target = reservation.target();
			WormholesPlatform.loadChunk(Wormholes.instance, end, target.x() >> 4, target.z() >> 4).whenComplete((chunk, error) ->
			{
				if(error != null || chunk == null)
				{
					releaseEndBuildTarget(reservation);
					destroyIfUnlinked(sourcePortal);
					Wormholes.w("[vanilla-portal] End chunk load failed: " + error);
					return;
				}
				boolean targetScheduled = FoliaScheduler.runRegion(Wormholes.instance, end, target.x() >> 4, target.z() >> 4, () ->
				{
					try
					{
						if(sourcePortal.isDestroyed())
						{
							return;
						}
						ILocalPortal reuse = findReusableEndArrival(end);
						ILocalPortal counterpart = reuse;
						if(counterpart == null)
						{
							int surfaceY = scanEndSurface(end, target.x(), target.z());
							EndDestinationPlan plan = endDestinationPlan(target.x(), target.z(), surfaceY, end.getMinHeight(), end.getMaxHeight());
							Set<Block> built = PortalSiteBuilder.buildHorizontalWindow(end, plan.x(), plan.y(), plan.z(), END_WINDOW_HALF);
							if(built.isEmpty())
							{
								destroyIfUnlinked(sourcePortal);
								return;
							}
							counterpart = PortalFactory.createReceiverFromCells(built, PortalFrame.canonical(Direction.U), PortalType.PORTAL, END_TAG, DimensionalPortalKind.END_ARRIVAL);
						}
						if(counterpart != null && PortalFactory.linkOneWay(sourcePortal, counterpart))
						{
							clearPortalBlocks(endPortalBlocks, Material.END_PORTAL);
							Wormholes.w("[vanilla-portal] End arrival placed " + END_COUNTERPART_RISE + " blocks above safe ground at " + target.x() + "," + target.z() + " + linked one way");
						}
						else
						{
							if(counterpart != null && counterpart != reuse)
							{
								destroyIfUnlinked(counterpart);
							}
							destroyIfUnlinked(sourcePortal);
						}
					}
					catch(Throwable ex)
					{
						destroyIfUnlinked(sourcePortal);
						Wormholes.instance.getLogger().log(Level.WARNING, "[vanilla-portal] End counterpart build failed", ex);
					}
					finally
					{
						releaseEndBuildTarget(reservation);
					}
				});
				if(!targetScheduled)
				{
					releaseEndBuildTarget(reservation);
					destroyIfUnlinked(sourcePortal);
				}
			});
		}
		catch(Throwable ex)
		{
			Wormholes.instance.getLogger().log(Level.WARNING, "[vanilla-portal] end pair build failed", ex);
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

	static EndDestinationPlan endDestinationPlan(int surfaceY, int minHeight, int maxHeight)
	{
		return endDestinationPlan(END_DESTINATION_X, END_DESTINATION_Z, surfaceY, minHeight, maxHeight);
	}

	static EndDestinationPlan endDestinationPlan(int x, int z, int surfaceY, int minHeight, int maxHeight)
	{
		int y = Math.max(minHeight + 5, Math.min(maxHeight - 4, surfaceY + END_COUNTERPART_RISE));
		return new EndDestinationPlan(x, y, z);
	}

	record EndDestinationPlan(int x, int y, int z)
	{
	}

	private static EndTarget selectEndTarget(World world)
	{
		return selectEndTarget(target -> !hasEndArrivalNear(world, target.x(), target.z()));
	}

	private static EndBuildTarget reserveEndBuildTarget(World world)
	{
		synchronized(END_TARGET_LOCK)
		{
			UUID worldId = world.getUID();
			for(EndTarget target : primaryEndTargets())
			{
				if(!hasEndArrivalNear(world, target.x(), target.z()) && !isEndTargetPending(worldId, target))
				{
					EndBuildTarget reservation = new EndBuildTarget(worldId, target);
					PENDING_END_TARGETS.add(reservation);
					return reservation;
				}
			}
			EndTarget fallback = new EndTarget(20, 20);
			while(!endWindowFitsSingleChunk(fallback) || hasEndArrivalNear(world, fallback.x(), fallback.z()) || isEndTargetPending(worldId, fallback))
			{
				fallback = nextEndFallbackTarget(fallback);
			}
			EndBuildTarget reservation = new EndBuildTarget(worldId, fallback);
			PENDING_END_TARGETS.add(reservation);
			return reservation;
		}
	}

	private static boolean isEndTargetPending(UUID worldId, EndTarget target)
	{
		for(EndBuildTarget pending : PENDING_END_TARGETS)
		{
			if(pending.worldId().equals(worldId) && endWindowsOverlap(pending.target(), target))
			{
				return true;
			}
		}
		return false;
	}

	static boolean endWindowsOverlap(EndTarget a, EndTarget b)
	{
		int diameter = END_WINDOW_HALF * 2;
		return Math.abs(a.x() - b.x()) <= diameter && Math.abs(a.z() - b.z()) <= diameter;
	}

	static boolean endWindowFitsSingleChunk(EndTarget target)
	{
		return ((target.x() - END_WINDOW_HALF) >> 4) == ((target.x() + END_WINDOW_HALF) >> 4)
				&& ((target.z() - END_WINDOW_HALF) >> 4) == ((target.z() + END_WINDOW_HALF) >> 4);
	}

	static EndTarget nextEndFallbackTarget(EndTarget target)
	{
		int step = END_WINDOW_HALF * 2 + 2;
		EndTarget next = new EndTarget(target.x() + step, target.z());
		while(!endWindowFitsSingleChunk(next))
		{
			next = new EndTarget(next.x() + step, next.z());
		}
		return next;
	}

	private static void releaseEndBuildTarget(EndBuildTarget target)
	{
		synchronized(END_TARGET_LOCK)
		{
			PENDING_END_TARGETS.remove(target);
		}
	}

	static EndTarget selectEndTarget(Predicate<EndTarget> available)
	{
		for(EndTarget target : primaryEndTargets())
		{
			if(available.test(target))
			{
				return target;
			}
		}
		return new EndTarget(20, 20);
	}

	static List<EndTarget> primaryEndTargets()
	{
		List<EndTarget> targets = new ArrayList<EndTarget>(END_DESTINATIONS.length);
		for(int[] coordinates : END_DESTINATIONS)
		{
			targets.add(new EndTarget(coordinates[0], coordinates[1]));
		}
		return List.copyOf(targets);
	}

	private static boolean hasEndArrivalNear(World world, int x, int z)
	{
		if(Wormholes.portalManager == null)
		{
			return false;
		}
		for(ILocalPortal portal : Wormholes.portalManager.getLocalPortals())
		{
			if(!isManagedKind(portal, END_TAG, DimensionalPortalKind.END_ARRIVAL) || portal.getWorld() == null || !world.equals(portal.getWorld()))
			{
				continue;
			}
			Location center = portal.getCenter();
			if(center != null && Math.abs(center.getX() - x) <= 3.0D && Math.abs(center.getZ() - z) <= 3.0D)
			{
				return true;
			}
		}
		return false;
	}

	record EndTarget(int x, int z)
	{
	}

	private record EndBuildTarget(UUID worldId, EndTarget target)
	{
	}

	private static boolean isEndCause(PlayerTeleportEvent.TeleportCause cause)
	{
		return cause == PlayerTeleportEvent.TeleportCause.END_PORTAL || cause == PlayerTeleportEvent.TeleportCause.END_GATEWAY;
	}

	private static void clearPortalBlocks(Set<Block> portalBlocks, Material expected)
	{
		if(portalBlocks == null || portalBlocks.isEmpty())
		{
			return;
		}
		Map<Long, List<Block>> byChunk = new HashMap<Long, List<Block>>();
		for(Block block : portalBlocks)
		{
			long key = (((long) block.getX() >> 4) << 32) ^ (((long) block.getZ() >> 4) & 0xffffffffL);
			byChunk.computeIfAbsent(Long.valueOf(key), ignored -> new ArrayList<Block>()).add(block);
		}
		World world = portalBlocks.iterator().next().getWorld();
		for(List<Block> chunkBlocks : byChunk.values())
		{
			Block anchor = chunkBlocks.get(0);
			FoliaScheduler.runRegion(Wormholes.instance, world, anchor.getX() >> 4, anchor.getZ() >> 4, () ->
			{
				for(Block block : chunkBlocks)
				{
					if(block.getType() == expected)
					{
						block.setType(Material.AIR, false);
					}
				}
			});
		}
	}

	private static void destroyIfUnlinked(ILocalPortal portal)
	{
		if(portal != null && portal.getTunnel() == null && portal.getDimensionalCounterpartId() == null)
		{
			portal.destroy();
		}
	}

	private static boolean isNearEndWindow(Location loc)
	{
		if(loc == null || loc.getWorld() == null || Wormholes.portalManager == null)
		{
			return false;
		}
		for(ILocalPortal portal : Wormholes.portalManager.getLocalPortals())
		{
			if(!isManagedKind(portal, END_TAG, DimensionalPortalKind.END_SOURCE)
					&& !isManagedKind(portal, END_TAG, DimensionalPortalKind.END_ARRIVAL))
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

	@EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
	public void onBlockBreak(BlockBreakEvent event)
	{
		if(!Settings.REPLACE_NETHER_AND_END_PORTALS)
		{
			return;
		}
		Material brokenType = event.getBlock().getType();
		if(brokenType != Material.OBSIDIAN && brokenType != Material.END_PORTAL_FRAME)
		{
			return;
		}
		ILocalPortal portal = findFramedPortal(event.getBlock());
		if(portal != null)
		{
			Block broken = event.getBlock();
			FoliaScheduler.runRegion(Wormholes.instance, broken.getLocation(), () ->
			{
				if(!event.isCancelled())
				{
					breakPortalPair(portal);
				}
			}, 1L);
		}
	}

	@EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
	public void onBlockExplode(BlockExplodeEvent event)
	{
		breakExplodedFrames(event.blockList());
	}

	@EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
	public void onEntityExplode(EntityExplodeEvent event)
	{
		breakExplodedFrames(event.blockList());
	}

	private static void breakExplodedFrames(List<Block> blocks)
	{
		if(!Settings.REPLACE_NETHER_AND_END_PORTALS)
		{
			return;
		}
		Set<ILocalPortal> brokenPortals = new HashSet<ILocalPortal>();
		for(Block block : blocks)
		{
			Material material = block.getType();
			if(material != Material.OBSIDIAN && material != Material.END_PORTAL_FRAME)
			{
				continue;
			}
			ILocalPortal portal = findFramedPortal(block);
			if(portal != null)
			{
				brokenPortals.add(portal);
			}
		}
		for(ILocalPortal portal : brokenPortals)
		{
			portal.destroy();
		}
	}

	public void validateDimensionalFrames()
	{
		if(!Settings.REPLACE_NETHER_AND_END_PORTALS || Wormholes.portalManager == null)
		{
			return;
		}
		Map<FrameChunk, List<FrameCheck>> byChunk = new HashMap<FrameChunk, List<FrameCheck>>();
		Set<UUID> activePortals = new HashSet<UUID>();
		for(ILocalPortal portal : Wormholes.portalManager.getLocalPortals())
		{
			DimensionalPortalKind kind = portal.getDimensionalPortalKind();
			Material expected = kind == DimensionalPortalKind.NETHER ? Material.OBSIDIAN
					: kind == DimensionalPortalKind.END_SOURCE ? Material.END_PORTAL_FRAME : null;
			PortalStructure structure = portal.getStructure();
			if(expected == null || structure == null || structure.getWorld() == null || portal.isDestroyed())
			{
				continue;
			}
			activePortals.add(portal.getId());
			World world = structure.getWorld();
			CachedFramePositions cached = framePositionCache.get(portal.getId());
			if(cached == null || cached.structure() != structure)
			{
				cached = new CachedFramePositions(structure, expectedFramePositions(structure));
				framePositionCache.put(portal.getId(), cached);
			}
			for(FramePosition position : cached.positions())
			{
				FrameChunk chunk = new FrameChunk(world, position.x() >> 4, position.z() >> 4);
				byChunk.computeIfAbsent(chunk, ignored -> new ArrayList<FrameCheck>()).add(new FrameCheck(portal, position, expected));
			}
		}
		framePositionCache.keySet().retainAll(activePortals);
		for(Map.Entry<FrameChunk, List<FrameCheck>> entry : byChunk.entrySet())
		{
			FrameChunk chunk = entry.getKey();
			List<FrameCheck> checks = List.copyOf(entry.getValue());
			FoliaScheduler.runRegion(Wormholes.instance, chunk.world(), chunk.chunkX(), chunk.chunkZ(), () -> validateFrameChunk(chunk, checks));
		}
	}

	private static void validateFrameChunk(FrameChunk chunk, List<FrameCheck> checks)
	{
		if(!chunk.world().isChunkLoaded(chunk.chunkX(), chunk.chunkZ()))
		{
			return;
		}
		for(FrameCheck check : checks)
		{
			ILocalPortal portal = check.portal();
			FramePosition position = check.position();
			if(!portal.isDestroyed() && chunk.world().getBlockAt(position.x(), position.y(), position.z()).getType() != check.expected())
			{
				portal.destroy();
			}
		}
	}

	private static Set<FramePosition> expectedFramePositions(PortalStructure structure)
	{
		Set<FramePosition> cells = new HashSet<FramePosition>();
		for(Vector vector : structure.getBlockPositions())
		{
			cells.add(new FramePosition(vector.getBlockX(), vector.getBlockY(), vector.getBlockZ()));
		}
		return expectedFramePositions(cells);
	}

	static Set<FramePosition> expectedFramePositions(Set<FramePosition> cells)
	{
		if(cells.isEmpty())
		{
			return Set.of();
		}
		int minX = Integer.MAX_VALUE;
		int maxX = Integer.MIN_VALUE;
		int minY = Integer.MAX_VALUE;
		int maxY = Integer.MIN_VALUE;
		int minZ = Integer.MAX_VALUE;
		int maxZ = Integer.MIN_VALUE;
		for(FramePosition cell : cells)
		{
			minX = Math.min(minX, cell.x());
			maxX = Math.max(maxX, cell.x());
			minY = Math.min(minY, cell.y());
			maxY = Math.max(maxY, cell.y());
			minZ = Math.min(minZ, cell.z());
			maxZ = Math.max(maxZ, cell.z());
		}
		Set<FramePosition> frame = new HashSet<FramePosition>();
		for(FramePosition cell : cells)
		{
			if(minZ == maxZ)
			{
				addFrameNeighbor(cells, frame, cell.x() - 1, cell.y(), cell.z());
				addFrameNeighbor(cells, frame, cell.x() + 1, cell.y(), cell.z());
				addFrameNeighbor(cells, frame, cell.x(), cell.y() - 1, cell.z());
				addFrameNeighbor(cells, frame, cell.x(), cell.y() + 1, cell.z());
			}
			else if(minY == maxY)
			{
				addFrameNeighbor(cells, frame, cell.x() - 1, cell.y(), cell.z());
				addFrameNeighbor(cells, frame, cell.x() + 1, cell.y(), cell.z());
				addFrameNeighbor(cells, frame, cell.x(), cell.y(), cell.z() - 1);
				addFrameNeighbor(cells, frame, cell.x(), cell.y(), cell.z() + 1);
			}
			else if(minX == maxX)
			{
				addFrameNeighbor(cells, frame, cell.x(), cell.y() - 1, cell.z());
				addFrameNeighbor(cells, frame, cell.x(), cell.y() + 1, cell.z());
				addFrameNeighbor(cells, frame, cell.x(), cell.y(), cell.z() - 1);
				addFrameNeighbor(cells, frame, cell.x(), cell.y(), cell.z() + 1);
			}
		}
		return Set.copyOf(frame);
	}

	private static void addFrameNeighbor(Set<FramePosition> cells, Set<FramePosition> frame, int x, int y, int z)
	{
		FramePosition position = new FramePosition(x, y, z);
		if(!cells.contains(position))
		{
			frame.add(position);
		}
	}

	record FramePosition(int x, int y, int z)
	{
	}

	private record FrameCheck(ILocalPortal portal, FramePosition position, Material expected)
	{
	}

	private record FrameChunk(World world, int chunkX, int chunkZ)
	{
	}

	private record CachedFramePositions(PortalStructure structure, Set<FramePosition> positions)
	{
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
			if((broken.getType() == Material.OBSIDIAN && !isManagedKind(portal, NETHER_TAG, DimensionalPortalKind.NETHER))
					|| (broken.getType() == Material.END_PORTAL_FRAME && !isManagedKind(portal, END_TAG, DimensionalPortalKind.END_SOURCE)))
			{
				continue;
			}
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
		if(sz <= sx && sz <= sy)
		{
			return adjacentPortalCell(structure, x, y, z, true, true, false);
		}
		if(sy <= sx && sy <= sz)
		{
			return adjacentPortalCell(structure, x, y, z, true, false, true);
		}
		return adjacentPortalCell(structure, x, y, z, false, true, true);
	}

	private static boolean adjacentPortalCell(PortalStructure structure, int x, int y, int z, boolean varyX, boolean varyY, boolean varyZ)
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
				if(structure.containsBlock(nx, ny, nz))
				{
					return true;
				}
			}
		}
		return false;
	}

	private void breakPortalPair(ILocalPortal portal)
	{
		if(portal == null)
		{
			return;
		}
		portal.destroy();
		Wormholes.w("[vanilla-portal] frame broken -> portal pair destroyed");
	}

	private static CompletableFuture<Set<Block>> findPhysicalNetherPortalAsync(World world, int x, int y, int z, int radius)
	{
		if(!FoliaScheduler.isFoliaThreading(Bukkit.getServer()))
		{
			return CompletableFuture.completedFuture(findPhysicalNetherPortal(world, x, y, z, radius));
		}
		CompletableFuture<Set<Block>> result = new CompletableFuture<Set<Block>>();
		WormholesPlatform.loadChunk(Wormholes.instance, world, x >> 4, z >> 4).whenComplete((chunk, loadError) ->
		{
			if(loadError != null || chunk == null)
			{
				result.completeExceptionally(loadError == null ? new IllegalStateException("Physical portal search chunk did not load") : loadError);
				return;
			}
			boolean scheduled = FoliaScheduler.runRegion(Wormholes.instance, world, x >> 4, z >> 4, () ->
			{
				try
				{
					int ownedRadius = largestOwnedPoiRadius(world, x, z, radius);
					result.complete(ownedRadius <= 0 ? Set.of() : findPhysicalNetherPortal(world, x, y, z, ownedRadius));
				}
				catch(Throwable error)
				{
					result.completeExceptionally(error);
				}
			});
			if(!scheduled)
			{
				result.completeExceptionally(new IllegalStateException("Physical portal search region rejected lookup"));
			}
		});
		return result;
	}

	private static int largestOwnedPoiRadius(World world, int x, int z, int requestedRadius)
	{
		int radius = Math.max(1, requestedRadius);
		while(radius >= 1)
		{
			int minChunkX = (x - radius) >> 4;
			int minChunkZ = (z - radius) >> 4;
			int maxChunkX = (x + radius) >> 4;
			int maxChunkZ = (z + radius) >> 4;
			if(WormholesPlatform.isOwnedByCurrentRegion(world, minChunkX, minChunkZ, maxChunkX, maxChunkZ))
			{
				return radius;
			}
			if(radius == 1)
			{
				break;
			}
			radius = Math.max(1, radius / 2);
		}
		return 0;
	}

	private static Set<Block> findPhysicalNetherPortal(World world, int x, int y, int z, int radius)
	{
		Location nearest;
		try
		{
			nearest = PortalPoiLocator.locateNearestNetherPortal(world, new Location(world, x, y, z), radius);
		}
		catch(RuntimeException e)
		{
			Wormholes.instance.getLogger().log(Level.WARNING, "[vanilla-portal] nearby physical portal lookup failed", e);
			return Set.of();
		}
		if(nearest == null)
		{
			return Set.of();
		}
		Block anchor = nearest.getBlock();
		if(anchor.getType() != Material.NETHER_PORTAL || !(anchor.getBlockData() instanceof Orientable orientable))
		{
			return Set.of();
		}
		boolean alongX = orientable.getAxis() == Axis.X;
		int[][] offsets = alongX
				? new int[][] { { 1, 0, 0 }, { -1, 0, 0 }, { 0, 1, 0 }, { 0, -1, 0 } }
				: new int[][] { { 0, 0, 1 }, { 0, 0, -1 }, { 0, 1, 0 }, { 0, -1, 0 } };
		ArrayDeque<Block> search = new ArrayDeque<Block>();
		Set<Long> visited = new HashSet<Long>();
		Set<Block> cells = new HashSet<Block>();
		search.add(anchor);
		while(!search.isEmpty() && cells.size() < 512)
		{
			Block block = search.removeFirst();
			long key = packBlock(block.getX(), block.getY(), block.getZ());
			if(!visited.add(Long.valueOf(key)) || Math.abs(block.getX() - x) > radius || Math.abs(block.getZ() - z) > radius)
			{
				continue;
			}
			if(block.getType() != Material.NETHER_PORTAL || !(block.getBlockData() instanceof Orientable blockOrientable)
					|| (blockOrientable.getAxis() == Axis.X) != alongX)
			{
				continue;
			}
			cells.add(block);
			for(int[] offset : offsets)
			{
				search.addLast(block.getRelative(offset[0], offset[1], offset[2]));
			}
		}
		if(cells.isEmpty() || alreadyCovered(cells))
		{
			return Set.of();
		}
		return Set.copyOf(cells);
	}

	private static long packBlock(int x, int y, int z)
	{
		return ((long) (x & 0x3ffffff) << 38) | ((long) (z & 0x3ffffff) << 12) | (y & 0xfff);
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

	private static NetherBuildTarget reserveNetherBuildTarget(World world, int desiredX, int desiredZ, int interiorWidth)
	{
		int normalizedWidth = PortalSiteBuilder.netherInteriorWidth(interiorWidth);
		int halfExtent = normalizedWidth / 2 + 2;
		int spacing = Math.max(6, normalizedWidth + 4);
		synchronized(NETHER_TARGET_LOCK)
		{
			UUID worldId = world.getUID();
			for(int[] offset : NETHER_BUILD_OFFSETS)
			{
				int x = desiredX + offset[0] * spacing;
				int z = desiredZ + offset[1] * spacing;
				NetherBuildTarget candidate = new NetherBuildTarget(worldId, x, z, halfExtent);
				if(!hasManagedNetherPortalConflict(world, candidate) && reserveNetherFootprint(candidate))
				{
					return candidate;
				}
			}
			int x = desiredX + spacing * 3;
			NetherBuildTarget fallback = new NetherBuildTarget(worldId, x, desiredZ, halfExtent);
			while(hasManagedNetherPortalConflict(world, fallback) || !reserveNetherFootprint(fallback))
			{
				x += spacing;
				fallback = new NetherBuildTarget(worldId, x, desiredZ, halfExtent);
			}
			return fallback;
		}
	}

	private static boolean reserveNetherFootprint(NetherBuildTarget candidate)
	{
		for(NetherBuildTarget pending : PENDING_NETHER_TARGETS)
		{
			if(netherFootprintsOverlap(candidate, pending))
			{
				return false;
			}
		}
		PENDING_NETHER_TARGETS.add(candidate);
		return true;
	}

	private static boolean netherFootprintsOverlap(NetherBuildTarget a, NetherBuildTarget b)
	{
		if(!a.worldId().equals(b.worldId()))
		{
			return false;
		}
		return netherFootprintsOverlap(a.x(), a.z(), a.halfExtent(), b.x(), b.z(), b.halfExtent());
	}

	static boolean netherFootprintsOverlap(int xA, int zA, int halfExtentA, int xB, int zB, int halfExtentB)
	{
		int separation = halfExtentA + halfExtentB + 1;
		return Math.abs(xA - xB) <= separation && Math.abs(zA - zB) <= separation;
	}

	private static boolean hasManagedNetherPortalConflict(World world, NetherBuildTarget candidate)
	{
		if(Wormholes.portalManager == null)
		{
			return false;
		}
		for(ILocalPortal portal : Wormholes.portalManager.getLocalPortals())
		{
			if(!isManagedKind(portal, NETHER_TAG, DimensionalPortalKind.NETHER) || !world.equals(portal.getWorld()))
			{
				continue;
			}
			PortalStructure structure = portal.getStructure();
			AxisAlignedBB area = structure == null ? null : structure.getArea();
			if(area == null)
			{
				continue;
			}
			if(netherFootprintOverlapsStructureBounds(candidate.x(), candidate.z(), candidate.halfExtent(),
					area.getXa(), area.getXb(), area.getZa(), area.getZb()))
			{
				return true;
			}
		}
		return false;
	}

	static boolean netherFootprintOverlapsStructureBounds(int x, int z, int halfExtent, double xa, double xb, double za, double zb)
	{
		int existingMinX = (int) Math.floor(Math.min(xa, xb)) - NETHER_EXISTING_FOOTPRINT_MARGIN;
		int existingMaxX = (int) Math.floor(Math.max(xa, xb)) + NETHER_EXISTING_FOOTPRINT_MARGIN;
		int existingMinZ = (int) Math.floor(Math.min(za, zb)) - NETHER_EXISTING_FOOTPRINT_MARGIN;
		int existingMaxZ = (int) Math.floor(Math.max(za, zb)) + NETHER_EXISTING_FOOTPRINT_MARGIN;
		return x - halfExtent <= existingMaxX && x + halfExtent >= existingMinX
				&& z - halfExtent <= existingMaxZ && z + halfExtent >= existingMinZ;
	}

	private static void releaseNetherBuildTarget(NetherBuildTarget target)
	{
		synchronized(NETHER_TARGET_LOCK)
		{
			PENDING_NETHER_TARGETS.remove(target);
		}
	}

	private record NetherBuildTarget(UUID worldId, int x, int z, int halfExtent)
	{
	}

	private static ILocalPortal findReusableEndArrival(World world)
	{
		return findNearbyAuto(world, END_TAG, DimensionalPortalKind.END_ARRIVAL, 0, 0, 0, 32, false);
	}

	private static ILocalPortal findNearbyAuto(World world, String tag, DimensionalPortalKind kind, int x, int y, int z, int radius, boolean includeY)
	{
		if(Wormholes.portalManager == null)
		{
			return null;
		}
		ILocalPortal nearest = null;
		double nearestDistance = Double.MAX_VALUE;
		double radiusSquared = (double) radius * radius;
		for(ILocalPortal portal : Wormholes.portalManager.getLocalPortals())
		{
			PortalStructure structure = portal.getStructure();
			if(structure == null || !world.equals(structure.getWorld()))
			{
				continue;
			}
			if(!isManagedKind(portal, tag, kind) || portal.getDimensionalCounterpartId() != null || portal.getTunnel() != null)
			{
				continue;
			}
			Location c = portal.getCenter();
			if(c == null)
			{
				continue;
			}
			double dx = c.getX() - x;
			double dy = includeY ? c.getY() - y : 0.0D;
			double dz = c.getZ() - z;
			double horizontalDistance = dx * dx + dz * dz;
			if(horizontalDistance > radiusSquared)
			{
				continue;
			}
			double distance = horizontalDistance + dy * dy;
			if(distance < nearestDistance || (distance == nearestDistance && nearest != null && portal.getId().compareTo(nearest.getId()) < 0))
			{
				nearest = portal;
				nearestDistance = distance;
			}
		}
		return nearest;
	}

	private static boolean isManagedKind(ILocalPortal portal, String legacyTag, DimensionalPortalKind kind)
	{
		DimensionalPortalKind savedKind = portal.getDimensionalPortalKind();
		return savedKind == kind || (savedKind == DimensionalPortalKind.NONE && legacyTag.equals(portal.getName()));
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
