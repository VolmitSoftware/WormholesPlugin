package art.arcane.wormholes.door;

import art.arcane.volmlib.util.bukkit.WorldIdentity;
import art.arcane.volmlib.util.scheduling.FoliaScheduler;
import art.arcane.wormholes.Wormholes;
import art.arcane.wormholes.platform.WormholesPlatform;
import art.arcane.wormholes.survival.doors.dimension.PocketWorldService;
import org.bukkit.Chunk;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.SoundCategory;
import org.bukkit.World;
import org.bukkit.attribute.Attribute;
import org.bukkit.attribute.AttributeInstance;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.block.data.Bisected;
import org.bukkit.block.data.BlockData;
import org.bukkit.block.data.type.Door;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.HandlerList;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockBurnEvent;
import org.bukkit.event.block.CrafterCraftEvent;
import org.bukkit.event.block.BlockExplodeEvent;
import org.bukkit.event.block.BlockPhysicsEvent;
import org.bukkit.event.block.BlockPistonExtendEvent;
import org.bukkit.event.block.BlockPistonRetractEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.block.BlockRedstoneEvent;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.entity.EntityExplodeEvent;
import org.bukkit.event.inventory.CraftItemEvent;
import org.bukkit.event.inventory.PrepareItemCraftEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerMoveEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerTeleportEvent;
import org.bukkit.event.world.ChunkLoadEvent;
import org.bukkit.event.world.ChunkUnloadEvent;
import org.bukkit.event.world.WorldLoadEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.Plugin;
import org.bukkit.util.Vector;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.logging.Level;

/**
 * Survival runtime for physical Dimensional Doors.
 *
 * <p>The live vanilla {@link Door#isOpen()} value is the sole traversal
 * authority. There are intentionally no ownership lists, access menus, portal
 * toggles, or bypass rules in this manager.</p>
 */
public final class DimensionalDoorManager implements Listener, AutoCloseable
{
	private static final double ARRIVAL_OFFSET = 1.0D;
	private static final double PLAYER_HALF_WIDTH = 0.3D;
	private static final double COLLISION_EPSILON = 1.0E-7D;

	private final Wormholes plugin;
	private final PocketWorldService pocketWorldService;
	private final AtomicBoolean started;
	private final AtomicBoolean closed;
	private final AtomicBoolean acceptingEntries;
	private final Object lifecycleLock;
	private final DoorSpatialIndex<RuntimeDoor> spatialIndex;
	private final ConcurrentHashMap<UUID, RuntimeDoor> runtimes;
	private final ConcurrentHashMap<Long, PocketSpace> pocketSpacesByChunk;
	private final ConcurrentHashMap<UUID, Player> playersInTransit;
	private final ConcurrentHashMap<UUID, Player> personalRescues;
	private final PocketStructureService pocketStructures;
	private final DoorPortalVisualService visuals;

	private volatile DoorStateService state;
	private volatile DoorItemService items;

	public DimensionalDoorManager(Wormholes plugin, PocketWorldService pocketWorldService)
	{
		this.plugin = Objects.requireNonNull(plugin, "plugin");
		this.pocketWorldService = Objects.requireNonNull(pocketWorldService, "pocketWorldService");
		started = new AtomicBoolean();
		closed = new AtomicBoolean();
		acceptingEntries = new AtomicBoolean(true);
		lifecycleLock = new Object();
		spatialIndex = new DoorSpatialIndex<>();
		runtimes = new ConcurrentHashMap<>();
		pocketSpacesByChunk = new ConcurrentHashMap<>();
		playersInTransit = new ConcurrentHashMap<>();
		personalRescues = new ConcurrentHashMap<>();
		pocketStructures = new PocketStructureService();
		visuals = new DoorPortalVisualService(plugin);
	}

	public void start() throws IOException
	{
		if(!started.compareAndSet(false, true))
		{
			return;
		}
		state = DoorStateService.under(plugin.getDataFolder().toPath());
		items = new DoorItemService(plugin, plugin.getBlockManager().getWormholeRune(1));
		if(!items.registerRecipes())
		{
			plugin.getLogger().warning("One or more dimensional-door recipes could not be registered.");
		}
		plugin.getServer().getPluginManager().registerEvents(this, plugin);
		for(PlacedDoorEndpoint endpoint : state.endpoints())
		{
			installRuntime(endpoint);
			scheduleReconcile(endpoint, 1L);
		}
		for(PocketSpace space : state.spaces())
		{
			indexPocket(space);
		}
		pocketWorldService.whenReady().thenAccept(world ->
			FoliaScheduler.runGlobal(plugin, () -> reconcileWorld(world)));
		plugin.getLogger().info("Dimensional Doors ready: " + state.endpoints().size()
			+ " placed doors, " + state.spaces().size() + " pocket spaces.");
	}

	public DoorItemService items()
	{
		DoorItemService active = items;
		if(active == null)
		{
			throw new IllegalStateException("Dimensional Doors are not started");
		}
		return active;
	}

	public DoorStateService state()
	{
		DoorStateService active = state;
		if(active == null)
		{
			throw new IllegalStateException("Dimensional Doors are not started");
		}
		return active;
	}

	public boolean beginDrain()
	{
		return acceptingEntries.getAndSet(false);
	}

	public void resumeEntries()
	{
		if(!closed.get())
		{
			acceptingEntries.set(true);
		}
	}

	public boolean hasActiveTransits()
	{
		return !playersInTransit.isEmpty();
	}

	@EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
	public void onCraft(CraftItemEvent event)
	{
		DoorItemService.CraftHookResult result = items().handleCraft(event);
		if(result == DoorItemService.CraftHookResult.SHIFT_CRAFT_BLOCKED
			&& event.getWhoClicked() instanceof Player player)
		{
			player.sendMessage(Wormholes.tag + "Craft dimensional doors one at a time so each receives a unique identity.");
		}
	}

	@EventHandler(priority = EventPriority.HIGHEST)
	public void onPrepareCraft(PrepareItemCraftEvent event)
	{
		if(items().isDoorSkinRecipe(event.getRecipe()))
		{
			event.getInventory().setResult(items().skinCraftResult(event.getInventory().getMatrix()).orElse(null));
		}
	}

	@EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
	public void onCrafterCraft(CrafterCraftEvent event)
	{
		if(items().isDoorSkinRecipe(event.getRecipe()))
		{
			event.setCancelled(true);
			return;
		}
		items().productFor(event.getRecipe()).ifPresent(product -> event.setResult(switch(product)
		{
			case PAIR_KIT -> items().createPairKit();
			case PERSONAL_DOOR -> items().createPersonalDoor();
			case PUBLIC_DOOR -> items().createPublicDoor();
		}));
	}

	@EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
	public void onPairKitUse(PlayerInteractEvent event)
	{
		if(event.getHand() == null
			|| (event.getAction() != Action.RIGHT_CLICK_AIR && event.getAction() != Action.RIGHT_CLICK_BLOCK))
		{
			return;
		}
		ItemStack held = event.getItem();
		Optional<DoorItemService.PairKitContents> unpacked = items().unpackPairKit(held);
		if(unpacked.isEmpty())
		{
			return;
		}

		DoorItemService.PairKitContents contents = unpacked.get();
		try
		{
			mutateState(() -> state().registerPair(contents.pairIdentity()));
		}
		catch(IOException | RuntimeException ex)
		{
			plugin.getLogger().log(Level.SEVERE, "Could not persist dimensional-door pair "
				+ contents.pairIdentity().pairId(), ex);
			event.getPlayer().sendMessage(Wormholes.tag + "The door pair could not be unpacked; the kit was not consumed.");
			return;
		}

		event.setCancelled(true);
		consumeHeldItem(event.getPlayer(), event.getHand());
		giveOrDrop(event.getPlayer(), contents.endpointA(), contents.endpointB());
		event.getPlayer().sendMessage(Wormholes.tag + "The entangled pair separated into linked Wormhole Doors A and B.");
	}

	@EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
	public void onDoorPlace(BlockPlaceEvent event)
	{
		Optional<DoorItemIdentity> carriedIdentity = items().decodeDoorIdentity(event.getItemInHand());
		if(carriedIdentity.isPresent() && !DoorSkin.isPlayerOperable(event.getItemInHand().getType()))
		{
			event.setCancelled(true);
			event.getPlayer().sendMessage(Wormholes.tag
				+ "Combine this legacy dimensional door with a wooden door before placing it.");
			return;
		}
		Optional<DoorItemIdentity> decoded = items().decodeDoor(event.getItemInHand());
		if(decoded.isEmpty())
		{
			return;
		}
		DoorItemIdentity identity = decoded.get();
		if(identity.kind() == DoorKind.RETURN)
		{
			event.setCancelled(true);
			return;
		}
		if(identity.kind() == DoorKind.PAIR && state().findPair(identity.pairId()).isEmpty())
		{
			event.setCancelled(true);
			event.getPlayer().sendMessage(Wormholes.tag + "That paired door has no registered partner identity.");
			return;
		}

		Optional<VanillaDoorSnapshot> captured = VanillaDoorSnapshot.capture(event.getBlockPlaced());
		if(captured.isEmpty())
		{
			event.setCancelled(true);
			return;
		}
		DoorPosition position = position(event.getBlockPlaced(), captured.get().plane().blockY());
		PlacedDoorEndpoint endpoint = new PlacedDoorEndpoint(position, identity);
		try
		{
			if(!mutateState(() -> state().registerEndpoint(endpoint)))
			{
				return;
			}
		}
		catch(IOException | RuntimeException ex)
		{
			event.setCancelled(true);
			plugin.getLogger().log(Level.WARNING, "Rejected dimensional-door placement for " + identity.itemId(), ex);
			event.getPlayer().sendMessage(Wormholes.tag + "That dimensional door is already placed, or its state could not be saved.");
			return;
		}
		RuntimeDoor runtime = installRuntime(endpoint);
		runtime.update(captured.get());
		if(!schedulePlacementConfirmation(endpoint))
		{
			event.setCancelled(true);
			try
			{
				mutateState(() -> state().removeEndpoint(endpoint.position()));
			}
			catch(IOException ex)
			{
				plugin.getLogger().log(Level.SEVERE, "Could not roll back an unscheduled door placement", ex);
			}
			removeRuntime(endpoint);
		}
	}

	@EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
	public void onDoorBreakProtection(BlockBreakEvent event)
	{
		Optional<PlacedDoorEndpoint> direct = endpointForDoorBlock(event.getBlock());
		if(direct.isPresent())
		{
			PlacedDoorEndpoint endpoint = direct.get();
			if(endpoint.identity().kind() == DoorKind.RETURN)
			{
				event.setCancelled(true);
				event.getPlayer().sendMessage(Wormholes.tag + "The dimensional exit is anchored to this pocket.");
			}
			return;
		}

		Optional<PlacedDoorEndpoint> supported = endpointSupportedBy(event.getBlock());
		if(supported.isPresent())
		{
			event.setCancelled(true);
			event.getPlayer().sendMessage(Wormholes.tag + "Break the dimensional door before removing its support block.");
			return;
		}
		if(isPocketCoreBlock(event.getBlock()))
		{
			event.setCancelled(true);
		}
	}

	@EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
	public void onDoorBreakCommit(BlockBreakEvent event)
	{
		Optional<PlacedDoorEndpoint> direct = endpointForDoorBlock(event.getBlock());
		if(direct.isEmpty() || direct.get().identity().kind() == DoorKind.RETURN)
		{
			return;
		}
		PlacedDoorEndpoint endpoint = direct.get();
		Optional<PlacedDoorEndpoint> mate = state().findMate(endpoint.identity());
		Material liveMaterial = event.getBlock().getWorld()
			.getBlockAt(endpoint.position().x(), endpoint.position().y(), endpoint.position().z())
			.getType();
		Material droppedMaterial = DoorSkin.isPlayerOperable(liveMaterial)
			? liveMaterial
			: DoorItemService.defaultMaterial(endpoint.identity().kind());
		event.setDropItems(false);
		try
		{
			Optional<PlacedDoorEndpoint> removed = mutateState(() -> state().removeEndpoint(endpoint.position()));
			if(removed.isEmpty())
			{
				return;
			}
		}
		catch(IOException ex)
		{
			event.setCancelled(true);
			plugin.getLogger().log(Level.SEVERE, "Could not save dimensional-door break", ex);
			return;
		}
		removeRuntime(endpoint);
		mate.ifPresent(placedMate -> scheduleReconcile(placedMate, 1L));
		if(event.getPlayer().getGameMode() != GameMode.CREATIVE)
		{
			event.getBlock().getWorld().dropItemNaturally(
				event.getBlock().getLocation().add(0.5D, 0.5D, 0.5D),
				items().createDoor(endpoint.identity(), droppedMaterial));
		}
	}

	@EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
	public void onDoorInteract(PlayerInteractEvent event)
	{
		Block clicked = event.getClickedBlock();
		if(clicked != null && endpointForDoorBlock(clicked).isPresent())
		{
			scheduleNearby(clicked, 1L);
		}
	}

	@EventHandler(priority = EventPriority.MONITOR)
	public void onRedstone(BlockRedstoneEvent event)
	{
		scheduleNearby(event.getBlock(), 1L);
	}

	@EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
	public void onPhysics(BlockPhysicsEvent event)
	{
		scheduleNearby(event.getBlock(), 1L);
	}

	@EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
	public void onBurn(BlockBurnEvent event)
	{
		if(isProtectedDoorBlock(event.getBlock()))
		{
			event.setCancelled(true);
		}
	}

	@EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
	public void onPistonExtend(BlockPistonExtendEvent event)
	{
		if(event.getBlocks().stream().anyMatch(this::isProtectedDoorBlock)
			|| event.getBlocks().stream().map(block -> block.getRelative(event.getDirection())).anyMatch(this::isProtectedDoorBlock))
		{
			event.setCancelled(true);
		}
	}

	@EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
	public void onPistonRetract(BlockPistonRetractEvent event)
	{
		if(event.getBlocks().stream().anyMatch(this::isProtectedDoorBlock))
		{
			event.setCancelled(true);
		}
	}

	@EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
	public void onEntityExplode(EntityExplodeEvent event)
	{
		event.blockList().removeIf(this::isProtectedDoorBlock);
	}

	@EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
	public void onBlockExplode(BlockExplodeEvent event)
	{
		event.blockList().removeIf(this::isProtectedDoorBlock);
	}

	@EventHandler(priority = EventPriority.MONITOR)
	public void onChunkLoad(ChunkLoadEvent event)
	{
		visuals.cleanChunk(event.getChunk());
		reconcileChunk(event.getChunk());
	}

	@EventHandler(priority = EventPriority.MONITOR)
	public void onChunkUnload(ChunkUnloadEvent event)
	{
		visuals.unloadChunk(event.getChunk());
	}

	@EventHandler(priority = EventPriority.MONITOR)
	public void onWorldLoad(WorldLoadEvent event)
	{
		reconcileWorld(event.getWorld());
	}

	@EventHandler(priority = EventPriority.MONITOR)
	public void onQuit(PlayerQuitEvent event)
	{
		UUID playerId = event.getPlayer().getUniqueId();
		playersInTransit.remove(playerId, event.getPlayer());
		personalRescues.remove(playerId, event.getPlayer());
	}

	@EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
	public void onPersonalPocketDamage(EntityDamageEvent event)
	{
		if(!(event.getEntity() instanceof Player player))
		{
			return;
		}
		Optional<PocketSpace> pocket = personalPocketAt(player);
		if(pocket.isEmpty())
		{
			return;
		}
		UUID playerId = player.getUniqueId();
		AttributeInstance maximumHealthAttribute = player.getAttribute(Attribute.MAX_HEALTH);
		double maximumHealth = maximumHealthAttribute == null
			? player.getHealth()
			: maximumHealthAttribute.getValue();
		PersonalPocketRescuePolicy.Decision decision = PersonalPocketRescuePolicy.evaluate(
			pocket.get().binding().kind(),
			player.getHealth(),
			maximumHealth,
			event.getFinalDamage(),
			personalRescues.containsKey(playerId));
		if(!decision.preventsDamage())
		{
			return;
		}

		event.setCancelled(true);
		player.setHealth(decision.retainedHealth());
		player.setFallDistance(0.0F);
		player.setFireTicks(0);
		player.setNoDamageTicks(Math.max(player.getNoDamageTicks(), 40));
		if(!decision.startsEjection() || playersInTransit.putIfAbsent(playerId, player) != null)
		{
			return;
		}
		if(personalRescues.putIfAbsent(playerId, player) != null)
		{
			playersInTransit.remove(playerId, player);
			return;
		}
		beginPersonalPocketRescue(player);
	}

	@EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
	public void onMove(PlayerMoveEvent event)
	{
		if(!WormholesPlatform.hasChangedPosition(event) || event.getTo().getWorld() == null
			|| event.getFrom().getWorld() == null
			|| !event.getFrom().getWorld().getUID().equals(event.getTo().getWorld().getUID()))
		{
			return;
		}
		Player player = event.getPlayer();
		if(playersInTransit.containsKey(player.getUniqueId()))
		{
			return;
		}
		DoorVec3 from = vector(event.getFrom());
		DoorVec3 to = vector(event.getTo());
		for(DoorSpatialIndex.Entry<RuntimeDoor> indexed : spatialIndex.nearby(
			event.getTo().getWorld().getUID(), event.getTo().getBlockX(), event.getTo().getBlockZ(), 1))
		{
			RuntimeDoor runtime = indexed.value();
			Optional<DoorwayCrossing> crossing = DoorTransitGate.detect(runtime.plane(), from, to);
			if(crossing.isEmpty())
			{
				continue;
			}
			PlacedDoorEndpoint endpoint = runtime.endpoint();
			World sourceWorld = event.getTo().getWorld();
			if(!WormholesPlatform.isOwnedByCurrentRegion(
				sourceWorld, endpoint.position().x() >> 4, endpoint.position().z() >> 4))
			{
				continue;
			}
			Optional<VanillaDoorSnapshot> captured = capture(endpoint, sourceWorld);
			if(captured.isEmpty())
			{
				reconcile(runtime);
				continue;
			}
			VanillaDoorSnapshot crossingSnapshot = captured.get();
			runtime.update(crossingSnapshot);
			Optional<DoorwayCrossing> liveCrossing = DoorTransitGate.detect(crossingSnapshot.plane(), from, to);
			if(liveCrossing.isEmpty() || !crossingSnapshot.open())
			{
				continue;
			}
			beginTransit(
				player,
				runtime,
				event.getTo().clone(),
				liveCrossing.get().direction(),
				crossingSnapshot);
			return;
		}
	}

	private void beginTransit(
		Player player,
		RuntimeDoor runtime,
		Location sourceLocation,
		DoorwayCrossing.Direction direction,
		VanillaDoorSnapshot crossingSnapshot)
	{
		if(closed.get()
			|| (!acceptingEntries.get() && runtime.endpoint().identity().kind() != DoorKind.RETURN))
		{
			return;
		}
		UUID playerId = player.getUniqueId();
		if(playersInTransit.putIfAbsent(playerId, player) != null)
		{
			return;
		}
		PlacedDoorEndpoint endpoint = runtime.endpoint();
		World world = world(endpoint.position());
		if(world == null || !FoliaScheduler.runRegion(plugin, world,
			endpoint.position().x() >> 4, endpoint.position().z() >> 4,
			() -> claimTransit(player, runtime, sourceLocation, direction, crossingSnapshot)))
		{
			playersInTransit.remove(playerId, player);
		}
	}

	private void claimTransit(
		Player player,
		RuntimeDoor runtime,
		Location sourceLocation,
		DoorwayCrossing.Direction direction,
		VanillaDoorSnapshot crossingSnapshot)
	{
		if(closed.get())
		{
			abortUnclaimed(player);
			return;
		}
		PlacedDoorEndpoint endpoint = runtime.endpoint();
		if(!acceptingEntries.get() && endpoint.identity().kind() != DoorKind.RETURN)
		{
			abortUnclaimed(player);
			return;
		}
		World sourceWorld = world(endpoint.position());
		if(sourceWorld == null)
		{
			abortUnclaimed(player);
			return;
		}
		Optional<VanillaDoorSnapshot> captured = capture(endpoint, sourceWorld);
		if(captured.isEmpty())
		{
			reconcile(runtime);
			abortUnclaimed(player);
			return;
		}
		VanillaDoorSnapshot sourceSnapshot = captured.get();
		if(!crossingSnapshot.worldId().equals(sourceSnapshot.worldId())
			|| !crossingSnapshot.plane().equals(sourceSnapshot.plane())
			|| !DoorTransitGate.claim(
				runtime.cycle(), crossingSnapshot.open(), sourceSnapshot.open()))
		{
			reconcile(runtime);
			abortUnclaimed(player);
			return;
		}
		runtime.update(sourceSnapshot);
		DoorTransit transit = new DoorTransit(
			sourceSnapshot.plane(), direction, sourceLocation.getYaw(), sourceLocation.getPitch());

		DoorDestination destination = state().resolveDestination(endpoint.identity(), player.getUniqueId());
		switch(destination)
		{
			case PairedDoorDestination paired -> beginPairedTransit(player, runtime, paired, transit);
			case PocketDoorDestination pocket -> beginPocketTransit(player, runtime, pocket, sourceWorld, transit);
			case ReturnDoorDestination ignored -> beginReturnTransit(player, runtime, transit);
		}
	}

	private void beginPairedTransit(
		Player player,
		RuntimeDoor source,
		PairedDoorDestination destination,
		DoorTransit transit)
	{
		Optional<PlacedDoorEndpoint> target = state().findPairedEndpoint(
			destination.pairId(), destination.endpoint());
		if(target.isEmpty())
		{
			abortTransit(player, source, "The linked Wormhole Door has not been placed yet.", TicketContext.NONE);
			return;
		}
		loadEndpointArrival(target.get(), transit, arrival ->
			closeAndTeleport(player, source, arrival, TicketContext.NONE),
			() -> abortTransit(player, source, "The linked Wormhole Door is unavailable or obstructed.", TicketContext.NONE));
	}

	private void beginPocketTransit(
		Player player,
		RuntimeDoor source,
		PocketDoorDestination destination,
		World sourceWorld,
		DoorTransit transit)
	{
		if(pocketWorldService.isPocketWorld(sourceWorld))
		{
			abortTransit(player, source,
				"A pocket door cannot open another pocket from inside the shared void dimension.", TicketContext.NONE);
			return;
		}
		World pocketWorld = pocketWorldService.world().orElse(null);
		if(pocketWorld == null)
		{
			abortTransit(player, source, "The pocket dimension is not ready.", TicketContext.NONE);
			return;
		}

		PocketSpace space;
		try
		{
			space = mutateState(() -> state().getOrAllocatePocket(destination.binding()));
			indexPocket(space);
		}
		catch(IOException | RuntimeException ex)
		{
			plugin.getLogger().log(Level.SEVERE, "Could not allocate dimensional pocket", ex);
			abortTransit(player, source, "The pocket dimension could not be allocated.", TicketContext.NONE);
			return;
		}
		Optional<Location> safeReturn = safeSourceDoorReturn(sourceWorld, transit);
		if(safeReturn.isEmpty())
		{
			abortTransit(player, source, "A safe return route could not be found on this side of the door.", TicketContext.NONE);
			return;
		}
		Location savedReturnLocation = safeReturn.get();

		loadPocket(pocketWorld, space, () ->
		{
			PocketLayout layout = pocketStructures.layout(space);
			boolean initialize = state().findEndpointByItem(layout.returnDoorIdentity().itemId()).isEmpty()
				|| !pocketStructures.isInitialized(pocketWorld, space);
			PlacedDoorEndpoint returnEndpoint;
			try
			{
				returnEndpoint = pocketStructures.provision(pocketWorld, space, initialize);
				mutateState(() -> state().registerEndpoint(returnEndpoint));
				installRuntime(returnEndpoint);
				reconcile(runtimes.get(returnEndpoint.identity().itemId()));
			}
			catch(IOException | RuntimeException ex)
			{
				plugin.getLogger().log(Level.SEVERE, "Could not provision pocket " + space.spaceId(), ex);
				abortTransit(player, source, "The pocket could not be prepared safely.", TicketContext.NONE);
				return;
			}

			ReturnTicket ticket = new ReturnTicket(
				player.getUniqueId(),
				source.endpoint().identity().itemId(),
				savedReturnLocation.getWorld().getUID(),
				WorldIdentity.serialize(savedReturnLocation.getWorld()),
				savedReturnLocation.getX(),
				savedReturnLocation.getY(),
				savedReturnLocation.getZ(),
				savedReturnLocation.getYaw(),
				savedReturnLocation.getPitch());
			try
			{
				mutateState(() ->
				{
					state().putReturnTicket(ticket);
					return null;
				});
			}
			catch(IOException ex)
			{
				plugin.getLogger().log(Level.SEVERE, "Could not save a pocket return ticket", ex);
				abortTransit(player, source, "A safe return route could not be saved.", TicketContext.NONE);
				return;
			}

			Location arrival = pocketStructures.entryLocation(pocketWorld, space);
			arrival.setYaw(transit.yaw());
			arrival.setPitch(transit.pitch());
			if(!isSafeStanding(arrival))
			{
				removeTicketQuietly(player.getUniqueId(), ticket);
				abortTransit(player, source, "The pocket entry is not safe.", TicketContext.NONE);
				return;
			}
			closeAndTeleport(player, source, arrival, TicketContext.keep(ticket));
		}, () -> abortTransit(player, source, "The pocket dimension could not load its entry chunk.", TicketContext.NONE));
	}

	private void beginReturnTransit(Player player, RuntimeDoor source, DoorTransit transit)
	{
		Optional<ReturnTicket> found = state().getReturnTicket(player.getUniqueId());
		if(found.isEmpty())
		{
			abortTransit(player, source, "You do not have a return route stored for this pocket.", TicketContext.NONE);
			return;
		}
		ReturnTicket ticket = found.get();
		Optional<PlacedDoorEndpoint> currentSource = state().findEndpointByItem(ticket.sourceEndpointId());
		if(currentSource.isPresent() && currentSource.get().identity().kind() != DoorKind.RETURN)
		{
			loadEndpointArrival(currentSource.get(), transit, arrival ->
				closeAndTeleport(player, source, arrival, TicketContext.remove(ticket)),
				() -> loadTicketFallback(player, source, ticket));
			return;
		}
		loadTicketFallback(player, source, ticket);
	}

	private void loadTicketFallback(Player player, RuntimeDoor source, ReturnTicket ticket)
	{
		World world = plugin.getServer().getWorld(ticket.sourceWorldId());
		if(world == null)
		{
			world = WorldIdentity.resolve(ticket.sourceWorldKey()).orElse(null);
		}
		if(world == null)
		{
			abortTransit(player, source, "Your return world is not loaded.", TicketContext.NONE);
			return;
		}
		World targetWorld = world;
		loadChunk(targetWorld, floor(ticket.x()), floor(ticket.z()), () ->
		{
			Location stored = new Location(targetWorld, ticket.x(), ticket.y(), ticket.z(), ticket.yaw(), ticket.pitch());
			Optional<Location> safe = findSafeNear(stored, 3);
			if(safe.isEmpty())
			{
				abortTransit(player, source, "Your saved return point is obstructed.", TicketContext.NONE);
				return;
			}
			closeAndTeleport(player, source, safe.get(), TicketContext.remove(ticket));
		}, () -> abortTransit(player, source, "Your saved return chunk could not be loaded.", TicketContext.NONE));
	}

	private void beginPersonalPocketRescue(Player player)
	{
		Optional<ReturnTicket> found = state().getReturnTicket(player.getUniqueId());
		if(found.isEmpty())
		{
			failPersonalPocketRescue(player, "Your personal dimension has no saved return route.");
			return;
		}
		ReturnTicket ticket = found.get();
		World world = plugin.getServer().getWorld(ticket.sourceWorldId());
		if(world == null)
		{
			world = WorldIdentity.resolve(ticket.sourceWorldKey()).orElse(null);
		}
		if(world == null)
		{
			failPersonalPocketRescue(player, "Your saved return world is not loaded.");
			return;
		}
		World targetWorld = world;
		loadChunk(targetWorld, floor(ticket.x()), floor(ticket.z()), () ->
		{
			Location stored = new Location(
				targetWorld, ticket.x(), ticket.y(), ticket.z(), ticket.yaw(), ticket.pitch());
			Optional<Location> safe = findSafeNear(stored, 3);
			if(safe.isEmpty())
			{
				failPersonalPocketRescue(player, "Your saved return point is obstructed.");
				return;
			}
			Runnable retired = () -> releasePersonalPocketRescue(player);
			if(!scheduleEntityWithRetirement(
				plugin,
				player,
				() -> teleportPersonalPocketRescue(player, safe.get(), ticket),
				retired))
			{
				retired.run();
			}
		}, () -> failPersonalPocketRescue(player, "Your saved return chunk could not be loaded."));
	}

	private void teleportPersonalPocketRescue(Player player, Location target, ReturnTicket ticket)
	{
		if(closed.get() || !isActivePersonalRescue(player))
		{
			return;
		}
		if(personalPocketAt(player).isEmpty())
		{
			releasePersonalPocketRescue(player);
			return;
		}

		CompletableFuture<Boolean> future;
		try
		{
			future = WormholesPlatform.teleport(plugin, player, target, PlayerTeleportEvent.TeleportCause.PLUGIN);
		}
		catch(Throwable ex)
		{
			plugin.getLogger().log(Level.WARNING, "Could not initiate personal-pocket rescue teleport", ex);
			failPersonalPocketRescue(player, "Your emergency return could not start.");
			return;
		}
		future.whenComplete((success, error) ->
		{
			if(closed.get())
			{
				return;
			}
			boolean moved = error == null && Boolean.TRUE.equals(success);
			Runnable retired = () -> finishRetiredPersonalPocketRescue(player, moved, ticket);
			boolean scheduled = scheduleEntityWithRetirement(plugin, player, () ->
			{
				if(!isActivePersonalRescue(player))
				{
					return;
				}
				if(moved)
				{
					finishSuccessfulTeleport(player);
					removeTicketQuietly(player.getUniqueId(), ticket);
				}
				releasePersonalPocketRescue(player);
				if(!moved)
				{
					player.sendMessage(Wormholes.tag + "Your emergency return was cancelled; the route was kept.");
				}
			}, retired);
			if(!scheduled)
			{
				retired.run();
			}
		});
	}

	private void finishRetiredPersonalPocketRescue(Player player, boolean moved, ReturnTicket ticket)
	{
		if(!isActivePersonalRescue(player))
		{
			return;
		}
		if(moved)
		{
			removeTicketAfterRetirement(player.getUniqueId(), ticket);
		}
		releasePersonalPocketRescue(player);
	}

	private void failPersonalPocketRescue(Player player, String reason)
	{
		releasePersonalPocketRescue(player);
		message(player, reason + " You remain protected at one heart.");
	}

	private void releasePersonalPocketRescue(Player player)
	{
		UUID playerId = player.getUniqueId();
		personalRescues.remove(playerId, player);
		playersInTransit.remove(playerId, player);
	}

	private boolean isActivePersonalRescue(Player player)
	{
		UUID playerId = player.getUniqueId();
		return personalRescues.get(playerId) == player && playersInTransit.get(playerId) == player;
	}

	private void loadEndpointArrival(
		PlacedDoorEndpoint endpoint,
		DoorTransit transit,
		java.util.function.Consumer<Location> success,
		Runnable failure)
	{
		World world = world(endpoint.position());
		if(world == null)
		{
			failure.run();
			return;
		}
		loadChunk(world, endpoint.position().x(), endpoint.position().z(), () ->
		{
			Optional<VanillaDoorSnapshot> captured = capture(endpoint, world);
			if(captured.isEmpty())
			{
				failure.run();
				return;
			}
			Optional<Location> safe = safeDestinationDoorArrival(world, captured.get().plane(), transit);
			if(safe.isEmpty())
			{
				failure.run();
				return;
			}
			success.accept(safe.get());
		}, failure);
	}

	private void loadChunk(World world, int blockX, int blockZ, Runnable success, Runnable failure)
	{
		if(closed.get())
		{
			return;
		}
		CompletableFuture<Chunk> future;
		try
		{
			future = WormholesPlatform.loadChunk(plugin, world, Math.floorDiv(blockX, 16), Math.floorDiv(blockZ, 16), true);
		}
		catch(Throwable ex)
		{
			plugin.getLogger().log(Level.WARNING, "Could not request dimensional-door chunk", ex);
			failure.run();
			return;
		}
		future.orTimeout(10L, TimeUnit.SECONDS).whenComplete((chunk, error) ->
		{
			if(closed.get())
			{
				return;
			}
			if(error != null || chunk == null)
			{
				if(error != null)
				{
					plugin.getLogger().log(Level.WARNING, "Could not load dimensional-door chunk", error);
				}
				failure.run();
				return;
			}
			if(!FoliaScheduler.runRegion(plugin, world, chunk.getX(), chunk.getZ(), () ->
			{
				if(!closed.get())
				{
					success.run();
				}
			}))
			{
				failure.run();
			}
		});
	}

	private void loadPocket(World world, PocketSpace space, Runnable success, Runnable failure)
	{
		if(closed.get())
		{
			return;
		}
		PocketLayout layout = pocketStructures.layout(space);
		int minChunkX = layout.minX() >> 4;
		int maxChunkX = layout.maxX() >> 4;
		int minChunkZ = layout.minZ() >> 4;
		int maxChunkZ = layout.maxZ() >> 4;
		List<CompletableFuture<Chunk>> loads = new ArrayList<>();
		try
		{
			for(int chunkX = minChunkX; chunkX <= maxChunkX; chunkX++)
			{
				for(int chunkZ = minChunkZ; chunkZ <= maxChunkZ; chunkZ++)
				{
					loads.add(WormholesPlatform.loadChunk(plugin, world, chunkX, chunkZ, true));
				}
			}
		}
		catch(Throwable ex)
		{
			plugin.getLogger().log(Level.WARNING, "Could not request pocket chunks", ex);
			failure.run();
			return;
		}
		CompletableFuture.allOf(loads.toArray(CompletableFuture[]::new))
			.orTimeout(10L, TimeUnit.SECONDS).whenComplete((ignored, error) ->
		{
			if(closed.get())
			{
				return;
			}
			if(error != null)
			{
				plugin.getLogger().log(Level.WARNING, "Could not load pocket chunks", error);
				failure.run();
				return;
			}
			if(!FoliaScheduler.runRegion(plugin, world, space.centerX() >> 4, space.centerZ() >> 4, () ->
			{
				if(!closed.get())
				{
					success.run();
				}
			}))
			{
				failure.run();
			}
		});
	}

	private void closeAndTeleport(Player player, RuntimeDoor source, Location target, TicketContext ticketContext)
	{
		if(closed.get())
		{
			return;
		}
		PlacedDoorEndpoint endpoint = source.endpoint();
		World sourceWorld = world(endpoint.position());
		if(sourceWorld == null || !FoliaScheduler.runRegion(plugin, sourceWorld,
			endpoint.position().x() >> 4, endpoint.position().z() >> 4, () ->
			{
				if(closed.get())
				{
					return;
				}
				Optional<VanillaDoorSnapshot> fresh = capture(endpoint, sourceWorld);
				if(fresh.isEmpty() || !fresh.get().open())
				{
					completeCycle(source, false, false);
					playersInTransit.remove(player.getUniqueId(), player);
					if(ticketContext.action() == TicketAction.KEEP_ON_SUCCESS)
					{
						removeTicketQuietly(player.getUniqueId(), ticketContext.expected());
					}
					message(player, "The dimensional door closed before transit completed.");
					return;
				}
				try
				{
					closePhysicalDoor(sourceWorld, fresh.get().plane());
				}
				catch(Throwable ex)
				{
					plugin.getLogger().log(Level.WARNING, "Could not close the source dimensional door", ex);
					completeCycle(source, false, false);
					playersInTransit.remove(player.getUniqueId(), player);
					if(ticketContext.action() == TicketAction.KEEP_ON_SUCCESS)
					{
						removeTicketQuietly(player.getUniqueId(), ticketContext.expected());
					}
					message(player, "The source door could not close safely.");
					return;
				}
				hideTransitVisual(endpoint.identity().itemId());
				Runnable retired = () -> retireScheduledTransit(player, source, ticketContext);
				if(!scheduleEntityWithRetirement(
					plugin,
					player,
					() -> teleport(player, source, target, ticketContext),
					retired))
				{
					retired.run();
				}
			}))
		{
			abortTransit(player, source, "The source door region is unavailable.", ticketContext);
		}
	}

	private void teleport(Player player, RuntimeDoor source, Location target, TicketContext ticketContext)
	{
		if(closed.get())
		{
			return;
		}
		CompletableFuture<Boolean> teleportFuture;
		try
		{
			teleportFuture = WormholesPlatform.teleport(plugin, player, target, PlayerTeleportEvent.TeleportCause.PLUGIN);
		}
		catch(Throwable ex)
		{
			plugin.getLogger().log(Level.WARNING, "Could not initiate dimensional-door teleport", ex);
			completeCycle(source, false, false);
			playersInTransit.remove(player.getUniqueId(), player);
			if(ticketContext.action() == TicketAction.KEEP_ON_SUCCESS)
			{
				removeTicketQuietly(player.getUniqueId(), ticketContext.expected());
			}
			player.sendMessage(Wormholes.tag + "The dimensional transit could not start.");
			return;
		}
		teleportFuture.whenComplete((success, error) ->
		{
			if(closed.get())
			{
				return;
			}
			boolean moved = error == null && Boolean.TRUE.equals(success);
			Runnable retired = () -> finishRetiredTransit(player, source, moved, ticketContext);
			boolean scheduled = scheduleEntityWithRetirement(plugin, player, () ->
			{
				if(closed.get())
				{
					return;
				}
				if(moved)
				{
					finishSuccessfulTeleport(player);
				}
				completeCycle(source, moved, false);
				playersInTransit.remove(player.getUniqueId(), player);
				if(moved && ticketContext.action() == TicketAction.REMOVE_ON_SUCCESS)
				{
					removeTicketQuietly(player.getUniqueId(), ticketContext.expected());
				}
				else if(!moved && ticketContext.action() == TicketAction.KEEP_ON_SUCCESS)
				{
					removeTicketQuietly(player.getUniqueId(), ticketContext.expected());
				}
				if(!moved)
				{
					player.sendMessage(Wormholes.tag + "The dimensional transit was cancelled.");
				}
			}, retired);
			if(!scheduled)
			{
				retired.run();
			}
		});
	}

	private void retireScheduledTransit(Player player, RuntimeDoor source, TicketContext ticketContext)
	{
		completeCycle(source, false, false);
		playersInTransit.remove(player.getUniqueId(), player);
		if(ticketContext.action() == TicketAction.KEEP_ON_SUCCESS)
		{
			removeTicketAfterRetirement(player.getUniqueId(), ticketContext.expected());
		}
	}

	private void finishRetiredTransit(
		Player player,
		RuntimeDoor source,
		boolean moved,
		TicketContext ticketContext)
	{
		completeCycle(source, moved, false);
		playersInTransit.remove(player.getUniqueId(), player);
		if((moved && ticketContext.action() == TicketAction.REMOVE_ON_SUCCESS)
			|| (!moved && ticketContext.action() == TicketAction.KEEP_ON_SUCCESS))
		{
			removeTicketAfterRetirement(player.getUniqueId(), ticketContext.expected());
		}
	}

	private void removeTicketAfterRetirement(UUID playerId, ReturnTicket ticket)
	{
		if(ticket == null)
		{
			return;
		}
		if(!FoliaScheduler.runAsync(plugin, () -> removeTicketQuietly(playerId, ticket)))
		{
			plugin.getLogger().warning("Could not schedule retired dimensional-door ticket cleanup for " + playerId);
		}
	}

	private boolean scheduleEntityWithRetirement(
		Plugin owner,
		Player player,
		Runnable task,
		Runnable retired)
	{
		if(closed.get() || !owner.isEnabled())
		{
			return false;
		}
		try
		{
			return WormholesPlatform.scheduleEntity(owner, player, task, retired, 0L);
		}
		catch(Throwable ex)
		{
			plugin.getLogger().log(Level.WARNING, "Could not schedule dimensional-door entity work", ex);
			return false;
		}
	}

	private void abortTransit(Player player, RuntimeDoor source, String reason, TicketContext ticketContext)
	{
		completeCycle(source, false, source.cycle().physicallyOpen());
		playersInTransit.remove(player.getUniqueId(), player);
		if(ticketContext.action() == TicketAction.KEEP_ON_SUCCESS)
		{
			removeTicketQuietly(player.getUniqueId(), ticketContext.expected());
		}
		message(player, reason);
	}

	private void abortUnclaimed(Player player)
	{
		playersInTransit.remove(player.getUniqueId(), player);
	}

	private void completeCycle(RuntimeDoor source, boolean success, boolean open)
	{
		try
		{
			source.cycle().complete(success, open);
		}
		catch(IllegalStateException ignored)
		{
		}
	}

	private void removeTicketQuietly(UUID playerId, ReturnTicket expected)
	{
		if(closed.get() || expected == null)
		{
			return;
		}
		try
		{
			mutateState(() ->
			{
				Optional<ReturnTicket> current = state().getReturnTicket(playerId);
				return current.filter(expected::equals).isPresent()
					? state().removeReturnTicket(playerId)
					: Optional.<ReturnTicket>empty();
			});
		}
		catch(IOException ex)
		{
			plugin.getLogger().log(Level.WARNING, "Could not remove dimensional return ticket for " + playerId, ex);
		}
	}

	private <T> T mutateState(IOCall<T> mutation) throws IOException
	{
		synchronized(lifecycleLock)
		{
			if(closed.get())
			{
				throw new IOException("Dimensional Doors are shutting down");
			}
			return mutation.call();
		}
	}

	private boolean schedulePlacementConfirmation(PlacedDoorEndpoint endpoint)
	{
		World world = world(endpoint.position());
		return world != null && FoliaScheduler.runRegion(plugin, world,
			endpoint.position().x() >> 4, endpoint.position().z() >> 4,
			() -> confirmPlacement(endpoint), 1L);
	}

	private void confirmPlacement(PlacedDoorEndpoint endpoint)
	{
		if(closed.get())
		{
			return;
		}
		RuntimeDoor runtime = runtimes.get(endpoint.identity().itemId());
		World world = world(endpoint.position());
		if(runtime != null && world != null && capture(endpoint, world).isPresent())
		{
			reconcile(runtime);
			state().findMate(endpoint.identity()).ifPresent(mate -> scheduleReconcile(mate, 1L));
			return;
		}
		try
		{
			mutateState(() -> state().removeEndpoint(endpoint.position()));
		}
		catch(IOException ex)
		{
			plugin.getLogger().log(Level.SEVERE, "Could not roll back a cancelled dimensional-door placement", ex);
		}
		removeRuntime(endpoint);
	}

	private RuntimeDoor installRuntime(PlacedDoorEndpoint endpoint)
	{
		RuntimeDoor runtime = runtimes.compute(endpoint.identity().itemId(), (ignored, current) ->
			current != null && current.endpoint().equals(endpoint) ? current : new RuntimeDoor(endpoint));
		spatialIndex.put(
			endpoint.identity().itemId(),
			endpoint.position().worldId(),
			endpoint.position().x(),
			endpoint.position().y(),
			endpoint.position().z(),
			runtime);
		return runtime;
	}

	private void removeRuntime(PlacedDoorEndpoint endpoint)
	{
		UUID doorId = endpoint.identity().itemId();
		runtimes.remove(doorId);
		spatialIndex.remove(doorId);
		visuals.hide(doorId);
	}

	private void reconcile(RuntimeDoor runtime)
	{
		if(runtime == null || closed.get())
		{
			return;
		}
		PlacedDoorEndpoint endpoint = runtime.endpoint();
		World world = world(endpoint.position());
		if(world == null)
		{
			runtime.invalidate();
			return;
		}
		if(!convertLegacyIronDoor(endpoint, world))
		{
			runtime.invalidate();
			return;
		}
		visuals.cleanChunk(world.getChunkAt(endpoint.position().x() >> 4, endpoint.position().z() >> 4));
		Optional<VanillaDoorSnapshot> captured = capture(endpoint, world);
		if(captured.isEmpty())
		{
			removeStaleEndpoint(runtime);
			return;
		}
		VanillaDoorSnapshot snapshot = captured.get();
		runtime.update(snapshot);
		if(snapshot.open() && destinationAvailable(endpoint.identity()))
		{
			visuals.show(endpoint, snapshot);
		}
		else
		{
			visuals.hide(endpoint.identity().itemId());
		}
	}

	private void removeStaleEndpoint(RuntimeDoor runtime)
	{
		PlacedDoorEndpoint endpoint = runtime.endpoint();
		runtime.invalidate();
		Optional<PlacedDoorEndpoint> mate = state().findMate(endpoint.identity());
		try
		{
			mutateState(() -> state().removeEndpoint(endpoint.position()));
		}
		catch(IOException ex)
		{
			plugin.getLogger().log(Level.WARNING, "Could not remove stale dimensional-door endpoint", ex);
			visuals.hide(endpoint.identity().itemId());
			return;
		}
		removeRuntime(endpoint);
		mate.ifPresent(placedMate -> scheduleReconcile(placedMate, 1L));
	}

	private boolean destinationAvailable(DoorItemIdentity identity)
	{
		return switch(identity.kind())
		{
			case PAIR -> state().findMate(identity).map(endpoint -> world(endpoint.position()) != null).orElse(false);
			case PERSONAL, PUBLIC -> pocketWorldService.world().isPresent();
			case RETURN -> true;
		};
	}

	private Optional<VanillaDoorSnapshot> capture(PlacedDoorEndpoint endpoint, World world)
	{
		DoorPosition position = endpoint.position();
		Block lower = world.getBlockAt(position.x(), position.y(), position.z());
		if(!DoorSkin.isPlayerOperable(lower.getType()))
		{
			return Optional.empty();
		}
		return VanillaDoorSnapshot.capture(lower)
			.filter(snapshot -> snapshot.worldId().equals(position.worldId()));
	}

	private boolean convertLegacyIronDoor(PlacedDoorEndpoint endpoint, World world)
	{
		if(endpoint.identity().kind() != DoorKind.PUBLIC)
		{
			return true;
		}
		DoorPosition position = endpoint.position();
		Block lower = world.getBlockAt(position.x(), position.y(), position.z());
		if(lower.getType() != Material.IRON_DOOR)
		{
			return true;
		}
		Block upper = lower.getRelative(BlockFace.UP);
		if(upper.getType() != Material.IRON_DOOR
			|| !(lower.getBlockData() instanceof Door)
			|| !(upper.getBlockData() instanceof Door))
		{
			return true;
		}

		BlockData previousLower = lower.getBlockData().clone();
		BlockData previousUpper = upper.getBlockData().clone();
		try
		{
			Material material = DoorItemService.defaultMaterial(DoorKind.PUBLIC);
			lower.setBlockData(retypeDoor(previousLower, material), false);
			upper.setBlockData(retypeDoor(previousUpper, material), false);
			plugin.getLogger().info("Converted legacy iron dimensional door "
				+ endpoint.identity().itemId() + " to " + material + ".");
			return true;
		}
		catch(RuntimeException exception)
		{
			try
			{
				lower.setBlockData(previousLower, false);
				upper.setBlockData(previousUpper, false);
			}
			catch(RuntimeException restoreFailure)
			{
				exception.addSuppressed(restoreFailure);
			}
			plugin.getLogger().log(Level.SEVERE,
				"Could not convert legacy iron dimensional door " + endpoint.identity().itemId(), exception);
			return false;
		}
	}

	private static Door retypeDoor(BlockData sourceData, Material material)
	{
		if(!(sourceData instanceof Door source) || !(material.createBlockData() instanceof Door target))
		{
			throw new IllegalArgumentException("Door material and block data are required");
		}
		target.setFacing(source.getFacing());
		target.setHalf(source.getHalf());
		target.setHinge(source.getHinge());
		target.setOpen(source.isOpen());
		target.setPowered(source.isPowered());
		return target;
	}

	private void scheduleReconcile(PlacedDoorEndpoint endpoint, long delay)
	{
		World world = world(endpoint.position());
		if(world == null || !world.isChunkLoaded(endpoint.position().x() >> 4, endpoint.position().z() >> 4))
		{
			return;
		}
		FoliaScheduler.runRegion(plugin, world,
			endpoint.position().x() >> 4, endpoint.position().z() >> 4,
			() -> reconcile(runtimes.get(endpoint.identity().itemId())), delay);
	}

	private void scheduleNearby(Block block, long delay)
	{
		for(DoorSpatialIndex.Entry<RuntimeDoor> nearby : spatialIndex.nearby(
			block.getWorld().getUID(), block.getX(), block.getZ(), 1))
		{
			if(Math.abs(nearby.blockX() - block.getX()) <= 2
				&& Math.abs(nearby.blockY() - block.getY()) <= 3
				&& Math.abs(nearby.blockZ() - block.getZ()) <= 2)
			{
				scheduleReconcile(nearby.value().endpoint(), delay);
			}
		}
	}

	private void reconcileChunk(Chunk chunk)
	{
		for(DoorSpatialIndex.Entry<RuntimeDoor> entry : spatialIndex.nearby(
			chunk.getWorld().getUID(), chunk.getX() << 4, chunk.getZ() << 4, 0))
		{
			reconcile(entry.value());
		}
	}

	private void reconcileWorld(World world)
	{
		for(PlacedDoorEndpoint endpoint : state().endpoints())
		{
			if(endpoint.position().worldId().equals(world.getUID()))
			{
				scheduleReconcile(endpoint, 1L);
			}
		}
	}

	private Optional<PlacedDoorEndpoint> endpointForDoorBlock(Block block)
	{
		Optional<PlacedDoorEndpoint> lower = state().findEndpoint(
			block.getWorld().getUID(), block.getX(), block.getY(), block.getZ());
		if(lower.isPresent())
		{
			return lower;
		}
		return state().findEndpoint(
			block.getWorld().getUID(), block.getX(), block.getY() - 1, block.getZ());
	}

	private Optional<PlacedDoorEndpoint> endpointSupportedBy(Block block)
	{
		return state().findEndpoint(
			block.getWorld().getUID(), block.getX(), block.getY() + 1, block.getZ());
	}

	private boolean isProtectedDoorBlock(Block block)
	{
		return endpointForDoorBlock(block).isPresent()
			|| endpointSupportedBy(block).isPresent()
			|| isPocketCoreBlock(block);
	}

	private boolean isPocketCoreBlock(Block block)
	{
		if(!pocketWorldService.isPocketWorld(block.getWorld()))
		{
			return false;
		}
		PocketSpace space = pocketSpacesByChunk.get(chunkKey(block.getX() >> 4, block.getZ() >> 4));
		return space != null && pocketStructures.isProtected(space, block.getX(), block.getY(), block.getZ());
	}

	private Optional<PocketSpace> personalPocketAt(Player player)
	{
		if(!pocketWorldService.isPocketWorld(player.getWorld()))
		{
			return Optional.empty();
		}
		Optional<PocketSpace> found = state().findPocket(PocketBinding.personal(player.getUniqueId()));
		if(found.isEmpty())
		{
			return Optional.empty();
		}
		PocketSpace space = found.get();
		Location location = player.getLocation();
		double halfStride = PocketAllocator.DEFAULT_STRIDE / 2.0D;
		return Math.abs(location.getX() - space.centerX()) < halfStride
			&& Math.abs(location.getZ() - space.centerZ()) < halfStride
			? Optional.of(space)
			: Optional.empty();
	}

	private void indexPocket(PocketSpace space)
	{
		PocketLayout layout = pocketStructures.layout(space);
		for(int chunkX = layout.minX() >> 4; chunkX <= layout.maxX() >> 4; chunkX++)
		{
			for(int chunkZ = layout.minZ() >> 4; chunkZ <= layout.maxZ() >> 4; chunkZ++)
			{
				pocketSpacesByChunk.put(chunkKey(chunkX, chunkZ), space);
			}
		}
	}

	private static long chunkKey(int chunkX, int chunkZ)
	{
		return ((long) chunkX << 32) ^ (chunkZ & 0xffffffffL);
	}

	private Optional<Location> safeSourceDoorReturn(World world, DoorTransit transit)
	{
		DoorVec3 point = transit.sourcePlane().entrySidePoint(transit.direction(), ARRIVAL_OFFSET);
		return safeStandingLocation(world, point, transit.yaw(), transit.pitch());
	}

	private Optional<Location> safeDestinationDoorArrival(
		World world,
		DoorwayPlane destinationPlane,
		DoorTransit transit)
	{
		DoorVec3 point = destinationPlane.exitSidePoint(transit.direction(), ARRIVAL_OFFSET);
		float yaw = transit.sourcePlane().rotateYawTo(destinationPlane, transit.yaw());
		return safeStandingLocation(world, point, yaw, transit.pitch());
	}

	private Optional<Location> safeStandingLocation(World world, DoorVec3 point, float yaw, float pitch)
	{
		Location candidate = new Location(world, point.x(), point.y(), point.z(), yaw, pitch);
		return isSafeStanding(candidate) ? Optional.of(candidate) : Optional.empty();
	}

	private Optional<Location> findSafeNear(Location stored, int radius)
	{
		if(isSafeStanding(stored))
		{
			return Optional.of(stored);
		}
		int originX = stored.getBlockX();
		int originY = stored.getBlockY();
		int originZ = stored.getBlockZ();
		for(int distance = 1; distance <= radius; distance++)
		{
			for(int x = -distance; x <= distance; x++)
			{
				for(int z = -distance; z <= distance; z++)
				{
					if(Math.max(Math.abs(x), Math.abs(z)) != distance)
					{
						continue;
					}
					for(int yOffset : new int[]{0, 1, -1, 2, -2})
					{
						Location candidate = new Location(stored.getWorld(),
							originX + x + 0.5D, originY + yOffset, originZ + z + 0.5D,
							stored.getYaw(), stored.getPitch());
						if(isSafeStanding(candidate))
						{
							return Optional.of(candidate);
						}
					}
				}
			}
		}
		return Optional.empty();
	}

	private static boolean isSafeStanding(Location location)
	{
		World world = location.getWorld();
		if(world == null || location.getBlockY() <= world.getMinHeight()
			|| location.getBlockY() + 1 >= world.getMaxHeight())
		{
			return false;
		}
		int minX = floor(location.getX() - PLAYER_HALF_WIDTH + COLLISION_EPSILON);
		int maxX = floor(location.getX() + PLAYER_HALF_WIDTH - COLLISION_EPSILON);
		int minZ = floor(location.getZ() - PLAYER_HALF_WIDTH + COLLISION_EPSILON);
		int maxZ = floor(location.getZ() + PLAYER_HALF_WIDTH - COLLISION_EPSILON);
		if(!WormholesPlatform.isOwnedByCurrentRegion(world, minX >> 4, minZ >> 4, maxX >> 4, maxZ >> 4))
		{
			return false;
		}
		int feetY = location.getBlockY();
		for(int x = minX; x <= maxX; x++)
		{
			for(int z = minZ; z <= maxZ; z++)
			{
				Block feet = world.getBlockAt(x, feetY, z);
				Block head = feet.getRelative(BlockFace.UP);
				Block floor = feet.getRelative(BlockFace.DOWN);
				if(!floor.getType().isSolid() || isHazard(floor.getType())
					|| !feet.isPassable() || !head.isPassable()
					|| isHazard(feet.getType()) || isHazard(head.getType()))
				{
					return false;
				}
			}
		}
		return true;
	}

	private static boolean isHazard(Material material)
	{
		return material == Material.LAVA
			|| material == Material.FIRE
			|| material == Material.SOUL_FIRE
			|| material == Material.POWDER_SNOW
			|| material == Material.MAGMA_BLOCK
			|| material == Material.CAMPFIRE
			|| material == Material.SOUL_CAMPFIRE
			|| material == Material.CACTUS
			|| material == Material.SWEET_BERRY_BUSH
			|| material == Material.WITHER_ROSE;
	}

	private void closePhysicalDoor(World world, DoorwayPlane plane)
	{
		Block lowerBlock = world.getBlockAt(plane.blockX(), plane.blockY(), plane.blockZ());
		Block upperBlock = lowerBlock.getRelative(BlockFace.UP);
		Material material = lowerBlock.getType();
		boolean wasOpen = lowerBlock.getBlockData() instanceof Door lower && lower.isOpen();
		if(lowerBlock.getBlockData() instanceof Door lower)
		{
			lower.setOpen(false);
			lowerBlock.setBlockData(lower, false);
		}
		if(upperBlock.getBlockData() instanceof Door upper)
		{
			upper.setOpen(false);
			upperBlock.setBlockData(upper, false);
		}
		if(wasOpen)
		{
			try
			{
				world.playSound(
					new Location(world, plane.blockX() + 0.5D, plane.blockY() + 1.0D, plane.blockZ() + 0.5D),
					DimensionalDoorSounds.closeSound(material),
					SoundCategory.BLOCKS,
					1.0F,
					1.0F);
			}
			catch(Throwable ex)
			{
				plugin.getLogger().log(Level.WARNING, "Could not play a dimensional-door close sound", ex);
			}
		}
	}

	private void hideTransitVisual(UUID doorId)
	{
		try
		{
			visuals.hide(doorId);
		}
		catch(Throwable ex)
		{
			plugin.getLogger().log(Level.WARNING, "Could not remove a dimensional-door transit visual", ex);
		}
	}

	private void finishSuccessfulTeleport(Player player)
	{
		try
		{
			player.setVelocity(new Vector());
			player.setFallDistance(0.0F);
		}
		catch(Throwable ex)
		{
			plugin.getLogger().log(Level.WARNING, "Could not settle a dimensional-door traveler after teleport", ex);
		}
		try
		{
			player.playSound(
				player.getLocation(),
				DimensionalDoorSounds.teleportSound(),
				SoundCategory.PLAYERS,
				1.0F,
				1.0F);
		}
		catch(Throwable ex)
		{
			plugin.getLogger().log(Level.WARNING, "Could not play a dimensional-door teleport sound", ex);
		}
	}

	private void message(Player player, String text)
	{
		FoliaScheduler.runEntity(plugin, player, () -> player.sendMessage(Wormholes.tag + text));
	}

	private World world(DoorPosition position)
	{
		World byId = plugin.getServer().getWorld(position.worldId());
		return byId == null ? WorldIdentity.resolve(position.worldKey()).orElse(null) : byId;
	}

	private static DoorPosition position(Block block, int lowerY)
	{
		return new DoorPosition(
			block.getWorld().getUID(), WorldIdentity.serialize(block.getWorld()), block.getX(), lowerY, block.getZ());
	}

	private static DoorVec3 vector(Location location)
	{
		return new DoorVec3(location.getX(), location.getY(), location.getZ());
	}

	private static int floor(double value)
	{
		return (int) Math.floor(value);
	}

	private static void consumeHeldItem(Player player, EquipmentSlot slot)
	{
		if(slot == EquipmentSlot.OFF_HAND)
		{
			player.getInventory().setItemInOffHand(null);
		}
		else
		{
			player.getInventory().setItemInMainHand(null);
		}
	}

	private static void giveOrDrop(Player player, ItemStack... stacks)
	{
		Map<Integer, ItemStack> overflow = player.getInventory().addItem(stacks);
		for(ItemStack stack : overflow.values())
		{
			player.getWorld().dropItemNaturally(player.getLocation(), stack);
		}
	}

	@Override
	public void close()
	{
		synchronized(lifecycleLock)
		{
			if(!closed.compareAndSet(false, true))
			{
				return;
			}
		}
		HandlerList.unregisterAll(this);
		acceptingEntries.set(false);
		DoorItemService activeItems = items;
		if(activeItems != null)
		{
			activeItems.unregisterRecipes();
		}
		visuals.close();
		playersInTransit.clear();
		personalRescues.clear();
		spatialIndex.clear();
		runtimes.clear();
		pocketSpacesByChunk.clear();
	}

	private enum TicketAction
	{
		NONE,
		KEEP_ON_SUCCESS,
		REMOVE_ON_SUCCESS
	}

	private record TicketContext(TicketAction action, ReturnTicket expected)
	{
		private static final TicketContext NONE = new TicketContext(TicketAction.NONE, null);

		private TicketContext
		{
			Objects.requireNonNull(action, "action");
			if(action != TicketAction.NONE)
			{
				Objects.requireNonNull(expected, "expected");
			}
		}

		private static TicketContext keep(ReturnTicket ticket)
		{
			return new TicketContext(TicketAction.KEEP_ON_SUCCESS, ticket);
		}

		private static TicketContext remove(ReturnTicket ticket)
		{
			return new TicketContext(TicketAction.REMOVE_ON_SUCCESS, ticket);
		}
	}

	@FunctionalInterface
	private interface IOCall<T>
	{
		T call() throws IOException;
	}

	private static final class RuntimeDoor
	{
		private final PlacedDoorEndpoint endpoint;
		private final DoorOpenCycle cycle;
		private volatile DoorwayPlane plane;

		private RuntimeDoor(PlacedDoorEndpoint endpoint)
		{
			this.endpoint = Objects.requireNonNull(endpoint, "endpoint");
			cycle = new DoorOpenCycle();
		}

		private PlacedDoorEndpoint endpoint()
		{
			return endpoint;
		}

		private DoorOpenCycle cycle()
		{
			return cycle;
		}

		private DoorwayPlane plane()
		{
			return plane;
		}

		private void update(VanillaDoorSnapshot snapshot)
		{
			plane = snapshot.plane();
			cycle.observe(snapshot.open());
		}

		private void invalidate()
		{
			plane = null;
			cycle.observe(false);
		}
	}
}
