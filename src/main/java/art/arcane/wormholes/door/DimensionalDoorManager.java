package art.arcane.wormholes.door;

import art.arcane.volmlib.util.bukkit.WorldIdentity;
import art.arcane.volmlib.util.localization.MessageArgument;
import art.arcane.volmlib.util.localization.TextKey;
import art.arcane.volmlib.util.scheduling.FoliaScheduler;
import art.arcane.wormholes.Wormholes;
import art.arcane.wormholes.localization.WormholesLocalization;
import art.arcane.wormholes.localization.WormholesMessages;
import art.arcane.wormholes.platform.WormholesPlatform;
import art.arcane.wormholes.service.WormholesAudience;
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
import org.bukkit.entity.Boss;
import org.bukkit.entity.ComplexLivingEntity;
import org.bukkit.entity.Entity;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Mob;
import org.bukkit.entity.Player;
import org.bukkit.entity.Vehicle;
import org.bukkit.event.Event;
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
import org.bukkit.event.vehicle.VehicleMoveEvent;
import org.bukkit.event.world.ChunkLoadEvent;
import org.bukkit.event.world.ChunkUnloadEvent;
import org.bukkit.event.world.WorldLoadEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.Plugin;
import org.bukkit.util.Vector;

import java.io.IOException;
import java.lang.reflect.Constructor;
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
import java.util.function.Predicate;
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
	private static final double PLAYER_HEIGHT = 1.8D;
	private static final double COLLISION_EPSILON = 1.0E-7D;
	private static final int[] DOOR_ARRIVAL_Y_OFFSETS = {0, -1, 1, -2, 2};
	private static final long TRANSIT_COOLDOWN_NANOS = TimeUnit.SECONDS.toNanos(1L);

	private final Wormholes plugin;
	private final PocketWorldService pocketWorldService;
	private final AtomicBoolean started;
	private final AtomicBoolean closed;
	private final AtomicBoolean acceptingEntries;
	private final Object lifecycleLock;
	private final DoorSpatialIndex<RuntimeDoor> spatialIndex;
	private final ConcurrentHashMap<UUID, RuntimeDoor> runtimes;
	private final ConcurrentHashMap<Long, PocketSpace> pocketSpacesByChunk;
	private final ConcurrentHashMap<UUID, Entity> travelersInTransit;
	private final ConcurrentHashMap<UUID, Long> transitCooldowns;
	private final ConcurrentHashMap<UUID, Player> pocketRescues;
	private final PocketStructureService pocketStructures;
	private final DoorPortalVisualService visuals;

	private volatile DoorStateService state;
	private volatile DoorItemService items;
	private volatile Listener livingEntityMoveListener;

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
		travelersInTransit = new ConcurrentHashMap<>();
		transitCooldowns = new ConcurrentHashMap<>();
		pocketRescues = new ConcurrentHashMap<>();
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
		registerLivingEntityMovement();
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

	private void registerLivingEntityMovement()
	{
		try
		{
			Class<?> listenerType = Class.forName("art.arcane.wormholes.door.PaperLivingEntityMoveListener");
			Constructor<?> constructor = listenerType.getDeclaredConstructor(LivingEntityMoveCallback.class);
			constructor.setAccessible(true);
			LivingEntityMoveCallback callback = this::onLivingEntityMove;
			Listener listener = (Listener) constructor.newInstance(callback);
			plugin.getServer().getPluginManager().registerEvents(listener, plugin);
			livingEntityMoveListener = listener;
		}
		catch(ClassNotFoundException | NoClassDefFoundError unavailable)
		{
			plugin.getLogger().warning("Living mobs require Paper's entity movement event to use dimensional doors.");
		}
		catch(ReflectiveOperationException | LinkageError | ClassCastException ex)
		{
			plugin.getLogger().log(Level.WARNING, "Could not register dimensional-door mob movement", ex);
		}
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

	public void onLanguageReload()
	{
		DoorItemService activeItems = items();
		activeItems.acceptWormholeRune(plugin.getBlockManager().getWormholeRune(1));
		if(!activeItems.registerRecipes())
		{
			plugin.getLogger().warning("One or more dimensional-door recipes could not be re-registered after a language reload.");
		}
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
		return !travelersInTransit.isEmpty();
	}

	@EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
	public void onCraft(CraftItemEvent event)
	{
		DoorItemService.CraftHookResult result = items().handleCraft(event);
		if(result == DoorItemService.CraftHookResult.SHIFT_CRAFT_BLOCKED
			&& event.getWhoClicked() instanceof Player player)
		{
			WormholesAudience.sendMessage(player, Wormholes.text().component(WormholesMessages.DOOR_CRAFT_ONE));
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

	@EventHandler(priority = EventPriority.HIGHEST)
	public void onPairKitUse(PlayerInteractEvent event)
	{
		if(event.getHand() == null
			|| !shouldUnpackPairKit(event.getAction(), event.useInteractedBlock(), event.useItemInHand()))
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
			WormholesAudience.sendMessage(event.getPlayer(), Wormholes.text().component(WormholesMessages.DOOR_PAIR_UNPACK_FAILED));
			return;
		}

		event.setCancelled(true);
		consumeHeldItem(event.getPlayer(), event.getHand());
		giveOrDrop(event.getPlayer(), contents.endpointA(), contents.endpointB());
		WormholesAudience.sendMessage(event.getPlayer(), Wormholes.text().component(WormholesMessages.DOOR_PAIR_UNPACKED));
	}

	@EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
	public void onDoorPlace(BlockPlaceEvent event)
	{
		Optional<DoorItemIdentity> carriedIdentity = items().decodeDoorIdentity(event.getItemInHand());
		if(carriedIdentity.isPresent() && !DoorSkin.isPlayerOperable(event.getItemInHand().getType()))
		{
			event.setCancelled(true);
			WormholesAudience.sendMessage(event.getPlayer(), Wormholes.text().component(WormholesMessages.DOOR_LEGACY_COMBINE));
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
			WormholesAudience.sendMessage(event.getPlayer(), Wormholes.text().component(WormholesMessages.DOOR_PAIR_MISSING));
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
			WormholesAudience.sendMessage(event.getPlayer(), Wormholes.text().component(WormholesMessages.DOOR_ALREADY_PLACED));
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
			return;
		}
		if(consumesPlacedDoorItem(event.getPlayer().getGameMode()))
		{
			consumeHeldItem(event.getPlayer(), event.getHand());
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
				WormholesAudience.sendMessage(event.getPlayer(), Wormholes.text().component(WormholesMessages.DOOR_EXIT_ANCHORED));
			}
			return;
		}

		Optional<PlacedDoorEndpoint> supported = endpointSupportedBy(event.getBlock());
		if(supported.isPresent())
		{
			event.setCancelled(true);
			WormholesAudience.sendMessage(event.getPlayer(), Wormholes.text().component(WormholesMessages.DOOR_BREAK_FIRST));
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
		event.getBlock().getWorld().dropItemNaturally(
			event.getBlock().getLocation().add(0.5D, 0.5D, 0.5D),
			items().createDoor(endpoint.identity(), droppedMaterial));
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
		travelersInTransit.remove(playerId, event.getPlayer());
		transitCooldowns.remove(playerId);
		pocketRescues.remove(playerId, event.getPlayer());
	}

	@EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
	public void onPocketDamage(EntityDamageEvent event)
	{
		if(!(event.getEntity() instanceof Player player))
		{
			return;
		}
		if(!pocketWorldService.isPocketWorld(player.getWorld()))
		{
			return;
		}
		UUID playerId = player.getUniqueId();
		AttributeInstance maximumHealthAttribute = player.getAttribute(Attribute.MAX_HEALTH);
		double maximumHealth = maximumHealthAttribute == null
			? player.getHealth()
			: maximumHealthAttribute.getValue();
		PocketRescuePolicy.Decision decision = PocketRescuePolicy.evaluate(
			player.getHealth(),
			maximumHealth,
			event.getFinalDamage(),
			pocketRescues.containsKey(playerId));
		if(!decision.preventsDamage())
		{
			return;
		}

		event.setCancelled(true);
		player.setHealth(decision.retainedHealth());
		player.setFallDistance(0.0F);
		player.setFireTicks(0);
		player.setNoDamageTicks(Math.max(player.getNoDamageTicks(), 40));
		if(!decision.startsEjection() || travelersInTransit.putIfAbsent(playerId, player) != null)
		{
			return;
		}
		if(pocketRescues.putIfAbsent(playerId, player) != null)
		{
			travelersInTransit.remove(playerId, player);
			return;
		}
		beginPocketRescue(player);
	}

	@EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
	public void onMove(PlayerMoveEvent event)
	{
		if(!WormholesPlatform.hasChangedPosition(event) || event.getTo() == null)
		{
			return;
		}
		handleMovement(event.getPlayer(), event.getFrom(), event.getTo());
	}

	@EventHandler(priority = EventPriority.MONITOR)
	public void onVehicleMove(VehicleMoveEvent event)
	{
		handleMovement(event.getVehicle(), event.getFrom(), event.getTo());
	}

	private void onLivingEntityMove(LivingEntity entity, Location from, Location to)
	{
		handleMovement(entity, from, to);
	}

	private void handleMovement(Entity traveler, Location fromLocation, Location toLocation)
	{
		if(closed.get() || traveler.isDead() || !traveler.isValid()
			|| !hasChangedPosition(fromLocation, toLocation)
			|| fromLocation.getWorld() == null || toLocation.getWorld() == null
			|| !fromLocation.getWorld().getUID().equals(toLocation.getWorld().getUID())
			|| travelersInTransit.containsKey(traveler.getUniqueId())
			|| hasTransitCooldown(traveler.getUniqueId(), System.nanoTime()))
		{
			return;
		}
		DoorVec3 from = vector(fromLocation);
		DoorVec3 to = vector(toLocation);
		for(DoorSpatialIndex.Entry<RuntimeDoor> indexed : spatialIndex.nearby(
			toLocation.getWorld().getUID(), toLocation.getBlockX(), toLocation.getBlockZ(), 1))
		{
			RuntimeDoor runtime = indexed.value();
			Optional<DoorwayCrossing> crossing = DoorTransitGate.detect(runtime.plane(), from, to);
			if(crossing.isEmpty())
			{
				continue;
			}
			PlacedDoorEndpoint endpoint = runtime.endpoint();
			if(!canTravelerEnter(endpoint.identity().kind(), traveler))
			{
				continue;
			}
			World sourceWorld = toLocation.getWorld();
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
				traveler,
				runtime,
				toLocation.clone(),
				liveCrossing.get().direction(),
				crossingSnapshot);
			return;
		}
	}

	private void beginTransit(
		Entity traveler,
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
		UUID travelerId = traveler.getUniqueId();
		if(travelersInTransit.putIfAbsent(travelerId, traveler) != null)
		{
			return;
		}
		PlacedDoorEndpoint endpoint = runtime.endpoint();
		World world = world(endpoint.position());
		if(world == null || !FoliaScheduler.runRegion(plugin, world,
			endpoint.position().x() >> 4, endpoint.position().z() >> 4,
			() -> claimTransit(traveler, runtime, sourceLocation, direction, crossingSnapshot)))
		{
			travelersInTransit.remove(travelerId, traveler);
		}
	}

	private void claimTransit(
		Entity traveler,
		RuntimeDoor runtime,
		Location sourceLocation,
		DoorwayCrossing.Direction direction,
		VanillaDoorSnapshot crossingSnapshot)
	{
		if(closed.get())
		{
			abortUnclaimed(traveler);
			return;
		}
		PlacedDoorEndpoint endpoint = runtime.endpoint();
		if(!acceptingEntries.get() && endpoint.identity().kind() != DoorKind.RETURN)
		{
			abortUnclaimed(traveler);
			return;
		}
		World sourceWorld = world(endpoint.position());
		if(sourceWorld == null)
		{
			abortUnclaimed(traveler);
			return;
		}
		Optional<VanillaDoorSnapshot> captured = capture(endpoint, sourceWorld);
		if(captured.isEmpty())
		{
			reconcile(runtime);
			abortUnclaimed(traveler);
			return;
		}
		VanillaDoorSnapshot sourceSnapshot = captured.get();
		if(!crossingSnapshot.worldId().equals(sourceSnapshot.worldId())
			|| !crossingSnapshot.plane().equals(sourceSnapshot.plane())
			|| !DoorTransitGate.claim(
				runtime.cycle(), crossingSnapshot.open(), sourceSnapshot.open()))
		{
			reconcile(runtime);
			abortUnclaimed(traveler);
			return;
		}
		runtime.update(sourceSnapshot);
		DoorTransit transit = new DoorTransit(
			sourceSnapshot.plane(),
			direction,
			sourceLocation.getYaw(),
			sourceLocation.getPitch(),
			traveler.getWidth() / 2.0D,
			traveler.getHeight());

		DoorDestination destination = state().resolveDestination(endpoint.identity(), traveler.getUniqueId());
		switch(destination)
		{
			case PairedDoorDestination paired -> beginPairedTransit(traveler, runtime, paired, transit);
			case PocketDoorDestination pocket -> beginPocketTransit(traveler, runtime, pocket, sourceWorld, transit);
			case ReturnDoorDestination ignored -> beginReturnTransit(traveler, runtime, transit);
		}
	}

	private void beginPairedTransit(
		Entity traveler,
		RuntimeDoor source,
		PairedDoorDestination destination,
		DoorTransit transit)
	{
		Optional<PlacedDoorEndpoint> target = state().findPairedEndpoint(
			destination.pairId(), destination.endpoint());
		if(target.isEmpty())
		{
			abortTransit(traveler, source, WormholesMessages.DOOR_LINK_NOT_PLACED, TicketContext.NONE);
			return;
		}
		loadEndpointArrival(target.get(), transit, arrival ->
			closeAndTeleport(traveler, source, arrival, TicketContext.NONE),
			() -> abortTransit(traveler, source, WormholesMessages.DOOR_LINK_UNAVAILABLE, TicketContext.NONE));
	}

	private void beginPocketTransit(
		Entity traveler,
		RuntimeDoor source,
		PocketDoorDestination destination,
		World sourceWorld,
		DoorTransit transit)
	{
		if(pocketWorldService.isPocketWorld(sourceWorld))
		{
			abortTransit(traveler, source, WormholesMessages.DOOR_NESTED_POCKET, TicketContext.NONE);
			return;
		}
		World pocketWorld = pocketWorldService.world().orElse(null);
		if(pocketWorld == null)
		{
			abortTransit(traveler, source, WormholesMessages.DOOR_POCKET_NOT_READY, TicketContext.NONE);
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
			abortTransit(traveler, source, WormholesMessages.DOOR_POCKET_ALLOCATION_FAILED, TicketContext.NONE);
			return;
		}
		Optional<Location> safeReturn = safeSourceDoorReturn(sourceWorld, transit);
		if(safeReturn.isEmpty())
		{
			abortTransit(traveler, source, WormholesMessages.DOOR_SAFE_RETURN_NOT_FOUND, TicketContext.NONE);
			return;
		}
		Location savedReturnLocation = safeReturn.get();
		UUID travelerId = traveler.getUniqueId();
		UUID sourceEndpointId = source.endpoint().identity().itemId();
		World savedReturnWorld = savedReturnLocation.getWorld();
		UUID savedReturnWorldId = savedReturnWorld.getUID();
		String savedReturnWorldKey = WorldIdentity.serialize(savedReturnWorld);
		double savedReturnX = savedReturnLocation.getX();
		double savedReturnY = savedReturnLocation.getY();
		double savedReturnZ = savedReturnLocation.getZ();
		float savedReturnYaw = savedReturnLocation.getYaw();
		float savedReturnPitch = savedReturnLocation.getPitch();

		loadPocket(pocketWorld, space, () ->
		{
			try
			{
				PocketLayout layout = pocketStructures.layout(space);
				Optional<PlacedDoorEndpoint> existingReturn = state().findEndpointByItem(
					layout.returnDoorIdentity().itemId());
				boolean initialize = existingReturn.isEmpty()
					|| !pocketStructures.isInitialized(pocketWorld, space);
				PlacedDoorEndpoint returnEndpoint;
				returnEndpoint = pocketStructures.provision(pocketWorld, space, initialize);
				PlacedDoorEndpoint previous = existingReturn
					.filter(endpoint -> !endpoint.equals(returnEndpoint))
					.orElse(null);
				if(previous != null)
				{
					mutateState(() -> state().relocateEndpoint(previous, returnEndpoint));
					removeRuntime(previous);
				}
				else
				{
					mutateState(() -> state().registerEndpoint(returnEndpoint));
				}
				installRuntime(returnEndpoint);
				reconcile(runtimes.get(returnEndpoint.identity().itemId()));
				retirePreviousReturnDoor(pocketWorld, space, previous);
			}
			catch(IOException | RuntimeException ex)
			{
				plugin.getLogger().log(Level.SEVERE, "Could not provision pocket " + space.spaceId(), ex);
				abortTransit(traveler, source, WormholesMessages.DOOR_POCKET_PREPARE_FAILED, TicketContext.NONE);
				return;
			}

			ReturnTicket ticket = new ReturnTicket(
				travelerId,
				sourceEndpointId,
				savedReturnWorldId,
				savedReturnWorldKey,
				savedReturnX,
				savedReturnY,
				savedReturnZ,
				savedReturnYaw,
				savedReturnPitch);
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
				abortTransit(traveler, source, WormholesMessages.DOOR_RETURN_TICKET_SAVE_FAILED, TicketContext.NONE);
				return;
			}

			Location arrival = pocketStructures.entryLocation(pocketWorld, space);
			arrival.setYaw(transit.yaw());
			arrival.setPitch(transit.pitch());
			if(!isSafeStanding(arrival))
			{
				removeTicketQuietly(travelerId, ticket);
				abortTransit(traveler, source, WormholesMessages.DOOR_POCKET_ENTRY_UNSAFE, TicketContext.NONE);
				return;
			}
			closeAndTeleport(traveler, source, arrival, TicketContext.keep(ticket));
		}, () -> abortTransit(traveler, source, WormholesMessages.DOOR_POCKET_ENTRY_CHUNK_FAILED, TicketContext.NONE));
	}

	private void retirePreviousReturnDoor(World world, PocketSpace space, PlacedDoorEndpoint previous)
	{
		if(previous == null)
		{
			return;
		}
		try
		{
			pocketStructures.retireReturnDoor(world, previous);
		}
		catch(RuntimeException cleanupFailure)
		{
			plugin.getLogger().log(Level.WARNING,
				"Could not remove the previous return door for pocket " + space.spaceId(), cleanupFailure);
		}
	}

	private void beginReturnTransit(Entity traveler, RuntimeDoor source, DoorTransit transit)
	{
		Optional<ReturnTicket> found = state().getReturnTicket(traveler.getUniqueId());
		if(found.isEmpty())
		{
			abortTransit(traveler, source, WormholesMessages.DOOR_NO_RETURN_ROUTE, TicketContext.NONE);
			return;
		}
		ReturnTicket ticket = found.get();
		Optional<PlacedDoorEndpoint> currentSource = state().findEndpointByItem(ticket.sourceEndpointId());
		if(currentSource.isPresent() && currentSource.get().identity().kind() != DoorKind.RETURN)
		{
			loadEndpointArrival(currentSource.get(), transit, arrival ->
				closeAndTeleport(traveler, source, arrival, TicketContext.remove(ticket)),
				() -> abortTransit(
					traveler,
					source,
						WormholesMessages.DOOR_RETURN_UNAVAILABLE,
					TicketContext.NONE));
			return;
		}
		loadTicketFallback(traveler, source, ticket);
	}

	private void loadTicketFallback(Entity traveler, RuntimeDoor source, ReturnTicket ticket)
	{
		World world = plugin.getServer().getWorld(ticket.sourceWorldId());
		if(world == null)
		{
			world = WorldIdentity.resolve(ticket.sourceWorldKey()).orElse(null);
		}
		if(world == null)
		{
			abortTransit(traveler, source, WormholesMessages.DOOR_RETURN_WORLD_UNLOADED, TicketContext.NONE);
			return;
		}
		World targetWorld = world;
		loadChunk(targetWorld, floor(ticket.x()), floor(ticket.z()), () ->
		{
			Location stored = new Location(targetWorld, ticket.x(), ticket.y(), ticket.z(), ticket.yaw(), ticket.pitch());
			Optional<Location> safe = findSafeNear(stored, 3);
			if(safe.isEmpty())
			{
				abortTransit(traveler, source, WormholesMessages.DOOR_RETURN_POINT_OBSTRUCTED, TicketContext.NONE);
				return;
			}
			closeAndTeleport(traveler, source, safe.get(), TicketContext.remove(ticket));
		}, () -> abortTransit(traveler, source, WormholesMessages.DOOR_RETURN_CHUNK_FAILED, TicketContext.NONE));
	}

	private void beginPocketRescue(Player player)
	{
		Optional<ReturnTicket> found = state().getReturnTicket(player.getUniqueId());
		if(found.isEmpty())
		{
			loadPocketRescueFallback(player, null, WormholesMessages.DOOR_RESCUE_NO_ROUTE);
			return;
		}
		ReturnTicket ticket = found.get();
		World world = plugin.getServer().getWorld(ticket.sourceWorldId());
		if(world == null)
		{
			world = WorldIdentity.resolve(ticket.sourceWorldKey()).orElse(null);
		}
		if(world == null || pocketWorldService.isPocketWorld(world))
		{
			loadPocketRescueFallback(player, ticket, WormholesMessages.DOOR_RESCUE_RETURN_WORLD_UNAVAILABLE);
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
				loadPocketRescueFallback(player, ticket, WormholesMessages.DOOR_RESCUE_RETURN_POINT_OBSTRUCTED);
				return;
			}
			beginPocketRescueTeleport(player, safe.get(), ticket);
		}, () -> loadPocketRescueFallback(
			player, ticket, WormholesMessages.DOOR_RESCUE_RETURN_CHUNK_FAILED));
	}

	private void loadPocketRescueFallback(Player player, ReturnTicket ticket, TextKey routeFailure)
	{
		if(!FoliaScheduler.runGlobal(plugin, () ->
		{
			World fallbackWorld = pocketRescueFallbackWorld();
			if(fallbackWorld == null)
			{
				failPocketRescue(player, routeFailure, WormholesMessages.DOOR_RESCUE_NO_FALLBACK_WORLD);
				return;
			}
			Location spawn = fallbackWorld.getSpawnLocation();
			loadChunk(fallbackWorld, spawn.getBlockX(), spawn.getBlockZ(), () ->
			{
				Location currentSpawn = fallbackWorld.getSpawnLocation();
				Optional<Location> safe = findSafeNear(currentSpawn, 3);
				if(safe.isEmpty())
				{
					failPocketRescue(player, routeFailure, WormholesMessages.DOOR_RESCUE_FALLBACK_OBSTRUCTED);
					return;
				}
				beginPocketRescueTeleport(player, safe.get(), ticket);
			}, () -> failPocketRescue(player, routeFailure, WormholesMessages.DOOR_RESCUE_FALLBACK_CHUNK_FAILED));
		}))
		{
			failPocketRescue(player, routeFailure, WormholesMessages.DOOR_RESCUE_FALLBACK_SCHEDULE_FAILED);
		}
	}

	private World pocketRescueFallbackWorld()
	{
		World firstAvailable = null;
		for(World candidate : plugin.getServer().getWorlds())
		{
			if(pocketWorldService.isPocketWorld(candidate))
			{
				continue;
			}
			if(candidate.getEnvironment() == World.Environment.NORMAL)
			{
				return candidate;
			}
			if(firstAvailable == null)
			{
				firstAvailable = candidate;
			}
		}
		return firstAvailable;
	}

	private void beginPocketRescueTeleport(Player player, Location target, ReturnTicket ticket)
	{
		Runnable retired = () -> releasePocketRescue(player);
		if(!scheduleEntityWithRetirement(
			plugin,
			player,
			() -> teleportPocketRescue(player, target, ticket),
			retired))
		{
			retired.run();
		}
	}

	private void teleportPocketRescue(Player player, Location target, ReturnTicket ticket)
	{
		if(closed.get() || !isActivePocketRescue(player))
		{
			return;
		}
		if(!pocketWorldService.isPocketWorld(player.getWorld()))
		{
			releasePocketRescue(player);
			return;
		}

		CompletableFuture<Boolean> future;
		try
		{
			future = WormholesPlatform.teleport(plugin, player, target, PlayerTeleportEvent.TeleportCause.PLUGIN);
		}
		catch(Throwable ex)
		{
			plugin.getLogger().log(Level.WARNING, "Could not initiate pocket rescue teleport", ex);
			failPocketRescue(player, WormholesMessages.DOOR_RESCUE_START_FAILED, null);
			return;
		}
		future.whenComplete((success, error) ->
		{
			if(closed.get())
			{
				return;
			}
			boolean moved = error == null && Boolean.TRUE.equals(success);
			Runnable retired = () -> finishRetiredPocketRescue(player, moved, ticket);
			boolean scheduled = scheduleEntityWithRetirement(plugin, player, () ->
			{
				if(!isActivePocketRescue(player))
				{
					return;
				}
				if(moved)
				{
					finishSuccessfulTeleport(player);
					removeTicketQuietly(player.getUniqueId(), ticket);
				}
				releasePocketRescue(player);
				if(!moved)
				{
					WormholesAudience.sendMessage(player, Wormholes.text().component(WormholesMessages.DOOR_RESCUE_CANCELLED));
				}
			}, retired);
			if(!scheduled)
			{
				retired.run();
			}
		});
	}

	private void finishRetiredPocketRescue(Player player, boolean moved, ReturnTicket ticket)
	{
		if(!isActivePocketRescue(player))
		{
			return;
		}
		if(moved)
		{
			removeTicketAfterRetirement(player.getUniqueId(), ticket);
		}
		releasePocketRescue(player);
	}

	private void failPocketRescue(Player player, TextKey routeFailure, TextKey fallbackFailure)
	{
		Runnable retired = () -> releasePocketRescue(player);
		if(!scheduleEntityWithRetirement(plugin, player, () ->
		{
			releasePocketRescue(player);
			WormholesAudience.sendMessage(player, Wormholes.text().component(
				WormholesMessages.DOOR_RESCUE_FAILED,
				WormholesLocalization.args(
					MessageArgument.untrusted("route", Wormholes.text().plain(routeFailure)),
					MessageArgument.untrusted("fallback", fallbackFailure == null ? "" : Wormholes.text().plain(fallbackFailure)))));
		}, retired))
		{
			retired.run();
		}
	}

	private void releasePocketRescue(Player player)
	{
		UUID playerId = player.getUniqueId();
		pocketRescues.remove(playerId, player);
		travelersInTransit.remove(playerId, player);
	}

	private boolean isActivePocketRescue(Player player)
	{
		UUID playerId = player.getUniqueId();
		return pocketRescues.get(playerId) == player && travelersInTransit.get(playerId) == player;
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

	private void closeAndTeleport(Entity traveler, RuntimeDoor source, Location target, TicketContext ticketContext)
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
					travelersInTransit.remove(traveler.getUniqueId(), traveler);
					if(ticketContext.action() == TicketAction.KEEP_ON_SUCCESS)
					{
						removeTicketQuietly(traveler.getUniqueId(), ticketContext.expected());
					}
					message(traveler, WormholesMessages.DOOR_CLOSED_DURING_TRANSIT);
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
					travelersInTransit.remove(traveler.getUniqueId(), traveler);
					if(ticketContext.action() == TicketAction.KEEP_ON_SUCCESS)
					{
						removeTicketQuietly(traveler.getUniqueId(), ticketContext.expected());
					}
					message(traveler, WormholesMessages.DOOR_SOURCE_CLOSE_FAILED);
					return;
				}
				hideTransitVisual(endpoint.identity().itemId());
				Runnable retired = () -> retireScheduledTransit(traveler, source, ticketContext);
				if(!scheduleEntityWithRetirement(
					plugin,
					traveler,
					() -> teleport(traveler, source, target, ticketContext),
					retired))
				{
					retired.run();
				}
		}))
		{
			abortTransit(traveler, source, WormholesMessages.DOOR_SOURCE_REGION_UNAVAILABLE, ticketContext);
		}
	}

	private void teleport(Entity traveler, RuntimeDoor source, Location target, TicketContext ticketContext)
	{
		if(closed.get())
		{
			return;
		}
		CompletableFuture<Boolean> teleportFuture;
		try
		{
			teleportFuture = WormholesPlatform.teleport(plugin, traveler, target, PlayerTeleportEvent.TeleportCause.PLUGIN);
		}
		catch(Throwable ex)
		{
			plugin.getLogger().log(Level.WARNING, "Could not initiate dimensional-door teleport", ex);
			completeCycle(source, false, false);
			travelersInTransit.remove(traveler.getUniqueId(), traveler);
			if(ticketContext.action() == TicketAction.KEEP_ON_SUCCESS)
			{
				removeTicketQuietly(traveler.getUniqueId(), ticketContext.expected());
			}
			message(traveler, WormholesMessages.DOOR_TRANSIT_START_FAILED);
			return;
		}
		teleportFuture.whenComplete((success, error) ->
		{
			if(closed.get())
			{
				return;
			}
			boolean moved = error == null && Boolean.TRUE.equals(success);
			Runnable retired = () -> finishRetiredTransit(traveler, source, moved, ticketContext);
			boolean scheduled = scheduleEntityWithRetirement(plugin, traveler, () ->
			{
				if(closed.get())
				{
					return;
				}
				if(moved)
				{
					startTransitCooldown(traveler);
					finishSuccessfulTeleport(traveler);
				}
				completeCycle(source, moved, false);
				travelersInTransit.remove(traveler.getUniqueId(), traveler);
				if(moved && ticketContext.action() == TicketAction.REMOVE_ON_SUCCESS)
				{
					removeTicketQuietly(traveler.getUniqueId(), ticketContext.expected());
				}
				else if(!moved && ticketContext.action() == TicketAction.KEEP_ON_SUCCESS)
				{
					removeTicketQuietly(traveler.getUniqueId(), ticketContext.expected());
				}
				if(!moved)
				{
					message(traveler, WormholesMessages.DOOR_TRANSIT_CANCELLED);
				}
			}, retired);
			if(!scheduled)
			{
				retired.run();
			}
		});
	}

	private void retireScheduledTransit(Entity traveler, RuntimeDoor source, TicketContext ticketContext)
	{
		completeCycle(source, false, false);
		travelersInTransit.remove(traveler.getUniqueId(), traveler);
		if(ticketContext.action() == TicketAction.KEEP_ON_SUCCESS)
		{
			removeTicketAfterRetirement(traveler.getUniqueId(), ticketContext.expected());
		}
	}

	private void finishRetiredTransit(
		Entity traveler,
		RuntimeDoor source,
		boolean moved,
		TicketContext ticketContext)
	{
		completeCycle(source, moved, false);
		travelersInTransit.remove(traveler.getUniqueId(), traveler);
		if((moved && ticketContext.action() == TicketAction.REMOVE_ON_SUCCESS)
			|| (!moved && ticketContext.action() == TicketAction.KEEP_ON_SUCCESS))
		{
			removeTicketAfterRetirement(traveler.getUniqueId(), ticketContext.expected());
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
		Entity traveler,
		Runnable task,
		Runnable retired)
	{
		if(closed.get() || !owner.isEnabled())
		{
			return false;
		}
		try
		{
			return WormholesPlatform.scheduleEntity(owner, traveler, task, retired, 0L);
		}
		catch(Throwable ex)
		{
			plugin.getLogger().log(Level.WARNING, "Could not schedule dimensional-door entity work", ex);
			return false;
		}
	}

	private void abortTransit(Entity traveler, RuntimeDoor source, TextKey reason, TicketContext ticketContext)
	{
		completeCycle(source, false, source.cycle().physicallyOpen());
		travelersInTransit.remove(traveler.getUniqueId(), traveler);
		if(ticketContext.action() == TicketAction.KEEP_ON_SUCCESS)
		{
			removeTicketQuietly(traveler.getUniqueId(), ticketContext.expected());
		}
		message(traveler, reason);
	}

	private void abortUnclaimed(Entity traveler)
	{
		travelersInTransit.remove(traveler.getUniqueId(), traveler);
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
		DoorVec3 point = arrivalPoint(transit.sourcePlane(), transit);
		float yaw = arrivalYaw(transit.sourcePlane(), transit.sourcePlane(), transit);
		return safeVerticalDoorStandingLocation(
			world, point, yaw, transit.pitch(), transit.halfWidth(), transit.height());
	}

	private Optional<Location> safeDestinationDoorArrival(
		World world,
		DoorwayPlane destinationPlane,
		DoorTransit transit)
	{
		DoorVec3 point = arrivalPoint(destinationPlane, transit);
		float yaw = arrivalYaw(transit.sourcePlane(), destinationPlane, transit);
		return safeVerticalDoorStandingLocation(
			world, point, yaw, transit.pitch(), transit.halfWidth(), transit.height());
	}

	static DoorVec3 arrivalPoint(DoorwayPlane plane, DoorTransit transit)
	{
		Objects.requireNonNull(plane, "plane");
		Objects.requireNonNull(transit, "transit");
		return plane.entrySidePoint(transit.direction(), arrivalOffset(transit));
	}

	static float arrivalYaw(DoorwayPlane source, DoorwayPlane destination, DoorTransit transit)
	{
		Objects.requireNonNull(source, "source");
		Objects.requireNonNull(destination, "destination");
		Objects.requireNonNull(transit, "transit");
		return source.rotateYawToMatchingSide(destination, transit.yaw());
	}

	private static double arrivalOffset(DoorTransit transit)
	{
		return Math.max(ARRIVAL_OFFSET, 0.5D + transit.halfWidth() + DoorwayPlane.PORTAL_RECESS);
	}

	private Optional<Location> safeVerticalDoorStandingLocation(
		World world,
		DoorVec3 nominal,
		float yaw,
		float pitch,
		double halfWidth,
		double height)
	{
		return findSafeVerticalDoorStanding(nominal, candidate -> isSafeStanding(new Location(
			world, candidate.x(), candidate.y(), candidate.z(), yaw, pitch), halfWidth, height))
			.map(candidate -> new Location(world, candidate.x(), candidate.y(), candidate.z(), yaw, pitch));
	}

	static Optional<DoorVec3> findSafeVerticalDoorStanding(
		DoorVec3 nominal,
		Predicate<DoorVec3> isSafe)
	{
		Objects.requireNonNull(nominal, "nominal");
		Objects.requireNonNull(isSafe, "isSafe");
		for(int yOffset : DOOR_ARRIVAL_Y_OFFSETS)
		{
			DoorVec3 candidate = new DoorVec3(nominal.x(), nominal.y() + yOffset, nominal.z());
			if(isSafe.test(candidate))
			{
				return Optional.of(candidate);
			}
		}
		return Optional.empty();
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
		return isSafeStanding(location, PLAYER_HALF_WIDTH, PLAYER_HEIGHT);
	}

	private static boolean isSafeStanding(Location location, double halfWidth, double height)
	{
		World world = location.getWorld();
		if(world == null || location.getBlockY() <= world.getMinHeight()
			|| location.getY() + height >= world.getMaxHeight())
		{
			return false;
		}
		int minX = floor(location.getX() - halfWidth + COLLISION_EPSILON);
		int maxX = floor(location.getX() + halfWidth - COLLISION_EPSILON);
		int minZ = floor(location.getZ() - halfWidth + COLLISION_EPSILON);
		int maxZ = floor(location.getZ() + halfWidth - COLLISION_EPSILON);
		if(!WormholesPlatform.isOwnedByCurrentRegion(world, minX >> 4, minZ >> 4, maxX >> 4, maxZ >> 4))
		{
			return false;
		}
		int feetY = location.getBlockY();
		int highestY = floor(location.getY() + height - COLLISION_EPSILON);
		for(int x = minX; x <= maxX; x++)
		{
			for(int z = minZ; z <= maxZ; z++)
			{
				Block feet = world.getBlockAt(x, feetY, z);
				Block floor = feet.getRelative(BlockFace.DOWN);
				if(!floor.getType().isSolid() || isHazard(floor.getType()))
				{
					return false;
				}
				for(int y = feetY; y <= highestY; y++)
				{
					Block occupied = world.getBlockAt(x, y, z);
					if(!occupied.isPassable() || isHazard(occupied.getType()))
					{
						return false;
					}
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

	private void finishSuccessfulTeleport(Entity traveler)
	{
		try
		{
			traveler.setVelocity(new Vector());
			traveler.setFallDistance(0.0F);
		}
		catch(Throwable ex)
		{
			plugin.getLogger().log(Level.WARNING, "Could not settle a dimensional-door traveler after teleport", ex);
		}
		try
		{
			if(traveler instanceof Player player)
			{
				player.playSound(
					player.getLocation(),
					DimensionalDoorSounds.teleportSound(),
					SoundCategory.PLAYERS,
					1.0F,
					1.0F);
			}
			else
			{
				traveler.getWorld().playSound(
					traveler.getLocation(),
					DimensionalDoorSounds.teleportSound(),
					SoundCategory.NEUTRAL,
					1.0F,
					1.0F);
			}
		}
		catch(Throwable ex)
		{
			plugin.getLogger().log(Level.WARNING, "Could not play a dimensional-door teleport sound", ex);
		}
	}

	private void message(Entity traveler, TextKey key)
	{
		if(traveler instanceof Player player)
		{
			FoliaScheduler.runEntity(plugin, player, () -> WormholesAudience.sendMessage(player, Wormholes.text().component(
				WormholesMessages.DOOR_TRANSIT_MESSAGE,
				WormholesLocalization.args(MessageArgument.untrusted("message", Wormholes.text().plain(key))))));
		}
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

	private static boolean hasChangedPosition(Location from, Location to)
	{
		return from.getX() != to.getX()
			|| from.getY() != to.getY()
			|| from.getZ() != to.getZ()
			|| !Objects.equals(from.getWorld(), to.getWorld());
	}

	private boolean hasTransitCooldown(UUID travelerId, long now)
	{
		Long expiresAt = transitCooldowns.get(travelerId);
		if(expiresAt == null)
		{
			return false;
		}
		if(now < expiresAt)
		{
			return true;
		}
		transitCooldowns.remove(travelerId, expiresAt);
		return false;
	}

	private void startTransitCooldown(Entity traveler)
	{
		UUID travelerId = traveler.getUniqueId();
		long expiresAt = System.nanoTime() + TRANSIT_COOLDOWN_NANOS;
		transitCooldowns.put(travelerId, expiresAt);
		Runnable cleanup = () -> transitCooldowns.remove(travelerId, expiresAt);
		try
		{
			WormholesPlatform.scheduleEntity(plugin, traveler, cleanup, cleanup, 20L);
		}
		catch(Throwable ex)
		{
			plugin.getLogger().log(Level.FINE, "Could not schedule dimensional-door cooldown cleanup", ex);
		}
	}

	private static boolean canTravelerEnter(DoorKind kind, Entity traveler)
	{
		boolean constrained = traveler.isInsideVehicle()
			|| !traveler.getPassengers().isEmpty()
			|| (traveler instanceof LivingEntity living && WormholesPlatform.isLeashed(living));
		return DoorTravelerPolicy.canEnter(
			kind,
			traveler instanceof Player,
			traveler instanceof Mob || traveler instanceof Vehicle,
			traveler instanceof Boss,
			traveler instanceof ComplexLivingEntity,
			constrained,
			traveler.getWidth(),
			traveler.getHeight());
	}

	static boolean shouldUnpackPairKit(
		Action action,
		Event.Result blockUse,
		Event.Result itemUse)
	{
		if(itemUse == Event.Result.DENY)
		{
			return false;
		}
		return action == Action.RIGHT_CLICK_AIR
			|| (action == Action.RIGHT_CLICK_BLOCK && blockUse != Event.Result.DENY);
	}

	static boolean consumesPlacedDoorItem(GameMode gameMode)
	{
		return gameMode == GameMode.CREATIVE;
	}

	private static int floor(double value)
	{
		return (int) Math.floor(value);
	}

	static void consumeHeldItem(Player player, EquipmentSlot slot)
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
		Listener entityMoveListener = livingEntityMoveListener;
		if(entityMoveListener != null)
		{
			HandlerList.unregisterAll(entityMoveListener);
			livingEntityMoveListener = null;
		}
		acceptingEntries.set(false);
		DoorItemService activeItems = items;
		if(activeItems != null)
		{
			activeItems.unregisterRecipes();
		}
		visuals.close();
		travelersInTransit.clear();
		transitCooldowns.clear();
		pocketRescues.clear();
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
