package art.arcane.wormholes;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.GameMode;
import org.bukkit.Keyed;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.data.BlockData;
import org.bukkit.enchantments.Enchantment;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.world.ChunkLoadEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.Recipe;
import org.bukkit.inventory.ShapedRecipe;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.util.Vector;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;

import art.arcane.wormholes.portal.PortalBlock;
import art.arcane.wormholes.portal.PortalType;
import art.arcane.wormholes.service.WormholesAudience;
import art.arcane.wormholes.util.GChunk;
import art.arcane.volmlib.util.collection.KList;
import art.arcane.wormholes.util.J;
import art.arcane.wormholes.util.M;
import art.arcane.volmlib.util.scheduling.FoliaScheduler;

public class BlockManager implements Listener
{
	private static final int[][] ADJACENT_OFFSETS = new int[][] {
			{ 1, 0, 0 }, { -1, 0, 0 }, { 0, 1, 0 }, { 0, -1, 0 }, { 0, 0, 1 }, { 0, 0, -1 }
	};
	private final Map<GChunk, Set<PortalBlock>> blocks;
	private final Object runeMutationLock = new Object();
	private final ItemStack wandTemplate;
	private final ItemStack portalRuneTemplate;
	private final ItemStack wormholeRuneTemplate;
	private final ItemStack gatewayRuneTemplate;

	public BlockManager()
	{
		Wormholes.v("Starting Block Manager");
		wandTemplate = buildTemplate(Material.BLAZE_ROD, ChatColor.GOLD + "" + ChatColor.BOLD + "Portal Wand");
		portalRuneTemplate = buildTemplate(Material.PRISMARINE, ChatColor.LIGHT_PURPLE + "" + ChatColor.BOLD + "Portal Rune");
		wormholeRuneTemplate = buildTemplate(Material.DARK_PRISMARINE, ChatColor.GOLD + "" + ChatColor.BOLD + "Wormhole Rune");
		gatewayRuneTemplate = buildTemplate(Material.BLACK_STAINED_GLASS, ChatColor.RED + "" + ChatColor.BOLD + "Gateway Rune");
		registerRecipes();
		blocks = new ConcurrentHashMap<GChunk, Set<PortalBlock>>();
		J.ar(() -> updatePlacedBlocks(), 9);
	}

	private static ItemStack buildTemplate(Material material, String displayName)
	{
		ItemStack is = new ItemStack(material);
		ItemMeta meta = is.getItemMeta();
		meta.addEnchant(Enchantment.INFINITY, 1, true);
		meta.setDisplayName(displayName);
		is.setItemMeta(meta);

		return is;
	}

	public void destroyAll()
	{
		Wormholes.v("Releasing tracked portal blocks (" + blocks.size() + " chunks)");
		blocks.clear();
		unregisterAllRecipes();
	}

	private void destroyAllInChunk(GChunk c)
	{
		Wormholes.v("Destroying " + blocks.get(c).size() + " portal blocks in chunk " + c.getX() + ", " + c.getZ());

		for(PortalBlock i : blocks.get(c))
		{
			i.getLocation().getBlock().setType(Material.AIR);
			ItemStack is = get(i.getType(), 1);
			i.getLocation().getWorld().dropItemNaturally(i.getLocation().clone().add(0.5, 0.5, 0.5), is);
		}

		blocks.remove(c);
	}

	@EventHandler
	public void on(ChunkLoadEvent e)
	{
		try
		{
			if(blocks.containsKey(new GChunk(e.getChunk())))
			{
				destroyAllInChunk(new GChunk(e.getChunk()));
				J.s(() -> e.getChunk().unload());
			}
		}

		catch(Throwable ex)
		{
			ex.printStackTrace();
		}
	}

	private void updatePlacedBlocks()
	{
		for(Player i : Bukkit.getOnlinePlayers())
		{
			FoliaScheduler.runEntity(Wormholes.instance, i, () -> animatePlacedBlocksFor(i));
		}
	}

	private void animatePlacedBlocksFor(Player i)
	{
		try
		{
			Location at = i.getLocation();
			if(at == null || at.getWorld() == null)
			{
				return;
			}

			String world = at.getWorld().getName();
			int cx = at.getBlockX() >> 4;
			int cz = at.getBlockZ() >> 4;

			for(int dx = -1; dx <= 1; dx++)
			{
				for(int dz = -1; dz <= 1; dz++)
				{
					Set<PortalBlock> set = blocks.get(new GChunk(cx + dx, cz + dz, world));
					if(set == null)
					{
						continue;
					}

					for(PortalBlock k : set)
					{
						if(M.r(0.35))
						{
							k.animate(i);
						}
					}
				}
			}
		}

		catch(Throwable e)
		{

		}
	}

	@EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
	public void on(PlayerInteractEvent e)
	{
		if(!isWand(e.getPlayer().getInventory().getItemInMainHand()))
		{
			return;
		}
		if(!e.getAction().equals(Action.LEFT_CLICK_BLOCK))
		{
			return;
		}

		PortalBlock b = getBlock(e.getClickedBlock());

		if(b == null)
		{
			WormholesAudience.sendActionBar(e.getPlayer(),Component.text("That is not a placed rune. Place runes (Portal/Wormhole/Gateway) in any connected shape on one flat surface, then left-click one with the wand.", NamedTextColor.GOLD));
			return;
		}

		WormholesAudience.sendActionBar(e.getPlayer(),Component.text("Forming portal... " + b.getType().name().toLowerCase() + " runes must connect on one flat wall, floor, or ceiling.", NamedTextColor.AQUA));
		construct(e.getPlayer(), e.getClickedBlock());
	}

	private void construct(Player player, Block clickedBlock)
	{
		RuneReservation reservation = reserveConnectedRunes(clickedBlock);
		if(reservation == null)
		{
			return;
		}
		if(!reservation.coplanar())
		{
			Wormholes.effectManager.playNotificationFail(ChatColor.RED + "Portal must lie flat on one wall, floor, or ceiling.", clickedBlock.getLocation());
			return;
		}
		Vector look = player.getLocation().getDirection();
		consumeConnectedRunes(player, player.getUniqueId(), clickedBlock.getWorld(), reservation.blocks(), reservation.type(), look);
	}

	private RuneReservation reserveConnectedRunes(Block clickedBlock)
	{
		synchronized(runeMutationLock)
		{
			PortalBlock init = findTrackedBlock(clickedBlock.getWorld().getName(), clickedBlock.getX(), clickedBlock.getY(), clickedBlock.getZ());
			if(init == null)
			{
				return null;
			}
			PortalType type = init.getType();
			Set<PortalBlock> connected = connectedRunes(clickedBlock.getWorld().getName(), RuneCoordinate.from(init.getLocation()), type);
			if(connected.isEmpty())
			{
				return null;
			}
			if(!isCoplanar(connected))
			{
				return new RuneReservation(type, Set.copyOf(connected), false);
			}
			for(PortalBlock portalBlock : connected)
			{
				Set<PortalBlock> tracked = blocks.get(chunkKey(portalBlock.getLocation()));
				if(tracked == null || !tracked.contains(portalBlock))
				{
					return null;
				}
			}
			for(PortalBlock portalBlock : connected)
			{
				unregisterBlockLocked(portalBlock);
			}
			return new RuneReservation(type, Set.copyOf(connected), true);
		}
	}

	private Set<PortalBlock> connectedRunes(String worldName, RuneCoordinate start, PortalType type)
	{
		Set<PortalBlock> connected = new HashSet<PortalBlock>();
		Set<RuneCoordinate> visited = new HashSet<RuneCoordinate>();
		ArrayDeque<RuneCoordinate> search = new ArrayDeque<RuneCoordinate>();
		search.add(start);
		while(!search.isEmpty())
		{
			RuneCoordinate coordinate = search.removeFirst();
			if(!visited.add(coordinate))
			{
				continue;
			}
			PortalBlock portalBlock = findTrackedBlock(worldName, coordinate.x(), coordinate.y(), coordinate.z());
			if(portalBlock == null || portalBlock.getType() != type)
			{
				continue;
			}
			connected.add(portalBlock);
			for(int[] offset : ADJACENT_OFFSETS)
			{
				search.addLast(new RuneCoordinate(coordinate.x() + offset[0], coordinate.y() + offset[1], coordinate.z() + offset[2]));
			}
		}
		return connected;
	}

	private static boolean isCoplanar(Set<PortalBlock> connected)
	{
		int minX = Integer.MAX_VALUE;
		int maxX = Integer.MIN_VALUE;
		int minY = Integer.MAX_VALUE;
		int maxY = Integer.MIN_VALUE;
		int minZ = Integer.MAX_VALUE;
		int maxZ = Integer.MIN_VALUE;
		for(PortalBlock portalBlock : connected)
		{
			Location location = portalBlock.getLocation();
			minX = Math.min(minX, location.getBlockX());
			maxX = Math.max(maxX, location.getBlockX());
			minY = Math.min(minY, location.getBlockY());
			maxY = Math.max(maxY, location.getBlockY());
			minZ = Math.min(minZ, location.getBlockZ());
			maxZ = Math.max(maxZ, location.getBlockZ());
		}
		return ConstructionManager.isCoplanarPortalArea(maxX - minX, maxY - minY, maxZ - minZ);
	}

	private void consumeConnectedRunes(Player player, UUID ownerId, World world, Set<PortalBlock> connected, PortalType type, Vector look)
	{
		Map<ChunkCoordinate, List<PortalBlock>> byChunk = new HashMap<ChunkCoordinate, List<PortalBlock>>();
		for(PortalBlock portalBlock : connected)
		{
			Location location = portalBlock.getLocation();
			ChunkCoordinate chunk = new ChunkCoordinate(location.getBlockX() >> 4, location.getBlockZ() >> 4);
			byChunk.computeIfAbsent(chunk, ignored -> new ArrayList<PortalBlock>()).add(portalBlock);
		}
		List<EffectManager.PortalBlockSnapshot> snapshots = Collections.synchronizedList(new ArrayList<EffectManager.PortalBlockSnapshot>());
		Set<Block> consumed = ConcurrentHashMap.newKeySet();
		AtomicBoolean failed = new AtomicBoolean();
		AtomicInteger remaining = new AtomicInteger(byChunk.size());
		Material expectedMaterial = runeMaterial(type);
		for(Map.Entry<ChunkCoordinate, List<PortalBlock>> entry : byChunk.entrySet())
		{
			ChunkCoordinate chunk = entry.getKey();
			boolean scheduled = FoliaScheduler.runRegion(Wormholes.instance, world, chunk.x(), chunk.z(), () ->
			{
				try
				{
					for(PortalBlock portalBlock : entry.getValue())
					{
						Location location = portalBlock.getLocation();
						Block block = world.getBlockAt(location.getBlockX(), location.getBlockY(), location.getBlockZ());
						if(block.getType() != expectedMaterial)
						{
							failed.set(true);
							continue;
						}
						EffectManager.PortalBlockSnapshot snapshot = new EffectManager.PortalBlockSnapshot(block.getLocation(), block.getBlockData());
						block.setType(Material.AIR, false);
						snapshots.add(snapshot);
						consumed.add(block);
					}
				}
				catch(Throwable error)
				{
					failed.set(true);
				}
				finally
				{
					if(remaining.decrementAndGet() == 0)
					{
						finishRuneConstruction(player, ownerId, world, snapshots, consumed, connected, type, look, failed.get());
					}
				}
			});
			if(!scheduled)
			{
				failed.set(true);
				if(remaining.decrementAndGet() == 0)
				{
					finishRuneConstruction(player, ownerId, world, snapshots, consumed, connected, type, look, true);
				}
			}
		}
	}

	private void finishRuneConstruction(Player player, UUID ownerId, World world, List<EffectManager.PortalBlockSnapshot> synchronizedSnapshots,
			Set<Block> consumed, Set<PortalBlock> reserved, PortalType type, Vector look, boolean failed)
	{
		List<EffectManager.PortalBlockSnapshot> snapshots;
		synchronized(synchronizedSnapshots)
		{
			snapshots = List.copyOf(synchronizedSnapshots);
		}
		if(failed || snapshots.size() != reserved.size() || consumed.size() != reserved.size())
		{
			rollbackRuneReservation(world, reserved, snapshots, type);
			notifyRuneRollback(player);
			return;
		}
		if(snapshots.isEmpty())
		{
			return;
		}
		double sumX = 0.0D;
		double sumY = 0.0D;
		double sumZ = 0.0D;
		for(EffectManager.PortalBlockSnapshot snapshot : snapshots)
		{
			sumX += snapshot.location().getX() + 0.5D;
			sumY += snapshot.location().getY() + 0.5D;
			sumZ += snapshot.location().getZ() + 0.5D;
		}
		Location center = new Location(world, sumX / snapshots.size(), sumY / snapshots.size(), sumZ / snapshots.size());
		boolean scheduled = FoliaScheduler.runRegion(Wormholes.instance, center, () ->
		{
			if(Wormholes.constructionManager.constructPortal(ownerId, consumed, type, look))
			{
				Wormholes.effectManager.playPortalVortex(world, center, snapshots);
				return;
			}
			rollbackRuneReservation(world, reserved, snapshots, type);
			notifyRuneRollback(player);
		});
		if(!scheduled)
		{
			rollbackRuneReservation(world, reserved, snapshots, type);
			notifyRuneRollback(player);
		}
	}

	private void notifyRuneRollback(Player player)
	{
		if(player != null)
		{
			FoliaScheduler.runEntity(Wormholes.instance, player,
					() -> WormholesAudience.sendActionBar(player, Component.text("Portal formation was interrupted; the reserved runes were restored.", NamedTextColor.RED)));
		}
	}

	private void rollbackRuneReservation(World world, Set<PortalBlock> reserved, List<EffectManager.PortalBlockSnapshot> snapshots, PortalType type)
	{
		Map<RuneCoordinate, BlockData> originals = new HashMap<RuneCoordinate, BlockData>();
		for(EffectManager.PortalBlockSnapshot snapshot : snapshots)
		{
			originals.put(RuneCoordinate.from(snapshot.location()), snapshot.data());
		}
		Map<ChunkCoordinate, List<PortalBlock>> byChunk = new HashMap<ChunkCoordinate, List<PortalBlock>>();
		for(PortalBlock portalBlock : reserved)
		{
			Location location = portalBlock.getLocation();
			ChunkCoordinate chunk = new ChunkCoordinate(location.getBlockX() >> 4, location.getBlockZ() >> 4);
			byChunk.computeIfAbsent(chunk, ignored -> new ArrayList<PortalBlock>()).add(portalBlock);
		}
		Material expectedMaterial = runeMaterial(type);
		for(Map.Entry<ChunkCoordinate, List<PortalBlock>> entry : byChunk.entrySet())
		{
			ChunkCoordinate chunk = entry.getKey();
			Runnable rollback = () ->
			{
				for(PortalBlock portalBlock : entry.getValue())
				{
					Location location = portalBlock.getLocation();
					Block block = world.getBlockAt(location.getBlockX(), location.getBlockY(), location.getBlockZ());
					BlockData original = originals.get(RuneCoordinate.from(location));
					if(original != null && block.getType().isAir())
					{
						block.setBlockData(original, false);
					}
					if(block.getType() == expectedMaterial)
					{
						registerBlockSilently(portalBlock);
					}
				}
			};
			boolean scheduled = FoliaScheduler.runRegion(Wormholes.instance, world, chunk.x(), chunk.z(), rollback);
			if(!scheduled && FoliaScheduler.isOwnedByCurrentRegion(world, chunk.x(), chunk.z()))
			{
				rollback.run();
			}
			else if(!scheduled)
			{
				Wormholes.w("Could not restore reserved portal runes in " + world.getName() + " chunk " + chunk.x() + "," + chunk.z() + " because region scheduling was rejected");
			}
		}
	}

	private static Material runeMaterial(PortalType type)
	{
		return switch(type)
		{
			case PORTAL -> Material.PRISMARINE;
			case WORMHOLE -> Material.DARK_PRISMARINE;
			case GATEWAY -> Material.BLACK_STAINED_GLASS;
		};
	}

	private record RuneReservation(PortalType type, Set<PortalBlock> blocks, boolean coplanar)
	{
	}

	private record RuneCoordinate(int x, int y, int z)
	{
		private static RuneCoordinate from(Location location)
		{
			return new RuneCoordinate(location.getBlockX(), location.getBlockY(), location.getBlockZ());
		}
	}

	private record ChunkCoordinate(int x, int z)
	{
	}

	@EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
	public void on(BlockPlaceEvent e)
	{
		ItemStack inHand = e.getItemInHand();
		PortalType placedType = null;

		if(isTemplateMatch(inHand, portalRuneTemplate))
		{
			placedType = PortalType.PORTAL;
		}
		else if(isTemplateMatch(inHand, wormholeRuneTemplate))
		{
			placedType = PortalType.WORMHOLE;
		}
		else if(isTemplateMatch(inHand, gatewayRuneTemplate))
		{
			placedType = PortalType.GATEWAY;
		}

		if(placedType == null)
		{
			return;
		}

		placeBlock(new PortalBlock(placedType, e.getBlock().getLocation()));
		WormholesAudience.sendActionBar(e.getPlayer(),Component.text("Rune placed. Build any connected shape on one flat surface, then left-click any rune with the Portal Wand.", NamedTextColor.AQUA));
	}

	@EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
	public void on(BlockBreakEvent e)
	{
		if(blocks.containsKey(new GChunk(e.getBlock().getLocation().getChunk())))
		{
			ItemStack drop = null;

			for(PortalBlock i : new KList<PortalBlock>(blocks.get(new GChunk(e.getBlock().getLocation().getChunk()))))
			{
				if(i.getLocation().equals(e.getBlock().getLocation()))
				{
					removeBlock(i);
					e.setDropItems(false);

					if(e.getPlayer().getGameMode().equals(GameMode.SURVIVAL))
					{
						switch(i.getType())
						{
							case PORTAL:
								drop = getPortalRune(1);
								break;
							case WORMHOLE:
								drop = getWormholeRune(1);
								break;
							case GATEWAY:
								drop = getGatewayRune(1);
								break;
						}
					}
				}
			}

			if(drop != null)
			{
				ItemStack dr = drop;
				Block dropBlock = e.getBlock();
				BlockBreakEvent breakEvent = e;

				FoliaScheduler.runRegion(Wormholes.instance, dropBlock.getLocation(), () ->
				{
					if(!breakEvent.isCancelled() && dropBlock.isEmpty())
					{
						dropBlock.getWorld().dropItemNaturally(dropBlock.getLocation().clone().add(0.5, 0.5, 0.5), dr);
					}
				}, 1L);
			}
		}
	}

	public boolean isBlock(Block block, PortalType type)
	{
		PortalBlock b = getBlock(block);

		if(b == null)
		{
			return false;
		}

		return b.getType().equals(type);
	}

	public PortalBlock getBlock(Block block)
	{
		synchronized(runeMutationLock)
		{
			return findTrackedBlock(block.getWorld().getName(), block.getX(), block.getY(), block.getZ());
		}
	}

	public void removeBlock(PortalBlock block)
	{
		if(unregisterBlock(block))
		{
			Wormholes.effectManager.playPortalBlockDestroyed(block.getLocation().getBlock());
		}
	}

	private boolean unregisterBlock(PortalBlock block)
	{
		synchronized(runeMutationLock)
		{
			return unregisterBlockLocked(block);
		}
	}

	private boolean unregisterBlockLocked(PortalBlock block)
	{
		GChunk chunk = chunkKey(block.getLocation());
		Set<PortalBlock> tracked = blocks.get(chunk);
		if(tracked == null || !tracked.remove(block))
		{
			return false;
		}
		if(tracked.isEmpty())
		{
			blocks.remove(chunk, tracked);
		}
		return true;
	}

	public void placeBlock(PortalBlock block)
	{
		registerBlockSilently(block);
		Wormholes.effectManager.playPortalBlockPlaced(block.getLocation().getBlock());
	}

	private void registerBlockSilently(PortalBlock block)
	{
		synchronized(runeMutationLock)
		{
			GChunk chunk = chunkKey(block.getLocation());
			blocks.computeIfAbsent(chunk, ignored -> ConcurrentHashMap.newKeySet()).add(block);
		}
	}

	private PortalBlock findTrackedBlock(String worldName, int x, int y, int z)
	{
		Set<PortalBlock> tracked = blocks.get(new GChunk(x >> 4, z >> 4, worldName));
		if(tracked == null)
		{
			return null;
		}
		for(PortalBlock portalBlock : tracked)
		{
			Location location = portalBlock.getLocation();
			if(location.getBlockX() == x && location.getBlockY() == y && location.getBlockZ() == z)
			{
				return portalBlock;
			}
		}
		return null;
	}

	private static GChunk chunkKey(Location location)
	{
		return new GChunk(location.getBlockX() >> 4, location.getBlockZ() >> 4, location.getWorld().getName());
	}

	public void registerRecipes()
	{
		unregisterAllRecipes();

		//@builder
		registerRecipe(new ShapedRecipe(new NamespacedKey(Wormholes.instance, "portal_wand"), getWand())
				.shape("d d", " r ", " d ")
				.setIngredient('d', Material.GLOWSTONE_DUST)
				.setIngredient('r', Material.BLAZE_ROD));
		registerRecipe(new ShapedRecipe(new NamespacedKey(Wormholes.instance, "portal_rune"), getPortalRune(4))
				.shape("pbp", "bdb", "pbp")
				.setIngredient('d', Material.BLAZE_POWDER)
				.setIngredient('b', Material.PRISMARINE_CRYSTALS)
				.setIngredient('p', Material.ENDER_PEARL));
		registerRecipe(new ShapedRecipe(new NamespacedKey(Wormholes.instance, "wormhole_rune"), getWormholeRune(4))
				.shape("pbp", "bdb", "pbp")
				.setIngredient('d', Material.NETHER_STAR)
				.setIngredient('b', Material.PRISMARINE_SHARD)
				.setIngredient('p', Material.ENDER_EYE));
		//@done
	}

	private void registerRecipe(Recipe r)
	{
		if(r instanceof Keyed)
		{
			Keyed k = (Keyed) r;

			try
			{
				Bukkit.addRecipe(r);
				Wormholes.instance.getLogger().info("Registered Recipe: " + k.getKey().toString());
			}

			catch(Throwable e)
			{
				Wormholes.instance.getLogger().warning("Recipe: " + k.getKey().toString() + " is already registered. Skipping registry.");
			}
		}
	}

	private void unregisterAllRecipes()
	{
		Iterator<Recipe> it = Bukkit.getServer().recipeIterator();

		while(it.hasNext())
		{
			Recipe r = it.next();

			if(r instanceof Keyed)
			{
				Keyed k = (Keyed) r;

				if(k.getKey().getKey().equals("wormholes"))
				{
					Wormholes.instance.getLogger().info("Unregistering Recipe: " + k.getKey().toString());
					it.remove();
				}
			}
		}
	}

	public boolean isSame(ItemStack is, ItemStack ib)
	{
		if(is == null && ib == null)
		{
			return true;
		}

		if(is == null || ib == null)
		{
			return false;
		}

		if(is.getType() != ib.getType())
		{
			return false;
		}

		return is.isSimilar(ib);
	}

	public boolean isPortalTool(ItemStack item)
	{
		return isWand(item) || isPortalRune(item);
	}

	public boolean isWand(ItemStack item)
	{
		return isTemplateMatch(item, wandTemplate);
	}

	public boolean isPortalRune(ItemStack item)
	{
		return isTemplateMatch(item, portalRuneTemplate) || isTemplateMatch(item, wormholeRuneTemplate) || isTemplateMatch(item, gatewayRuneTemplate);
	}

	public ItemStack getWand()
	{
		return wandTemplate.clone();
	}

	public ItemStack getPortalRune(int c)
	{
		ItemStack is = portalRuneTemplate.clone();
		is.setAmount(c);

		return is;
	}

	public ItemStack getWormholeRune(int c)
	{
		ItemStack is = wormholeRuneTemplate.clone();
		is.setAmount(c);

		return is;
	}

	public ItemStack getGatewayRune(int c)
	{
		ItemStack is = gatewayRuneTemplate.clone();
		is.setAmount(c);

		return is;
	}

	private boolean isTemplateMatch(ItemStack item, ItemStack template)
	{
		return item != null && item.getType() == template.getType() && item.isSimilar(template);
	}

	public void refund(Set<Block> blocks, PortalType type)
	{
		if(blocks == null || blocks.isEmpty())
		{
			return;
		}

		KList<Block> refund = new KList<Block>(blocks);
		ItemStack is = get(type, 1);
		Location anchor = refund.get(0).getLocation();

		Runnable[] tickHolder = new Runnable[1];
		tickHolder[0] = () ->
		{
			if(refund.isEmpty())
			{
				return;
			}

			if(M.r(Settings.PORTAL_COLAPSE_SPEED))
			{
				Block b = refund.pop();
				FoliaScheduler.runRegion(Wormholes.instance, b.getLocation(), () ->
				{
					b.getWorld().dropItemNaturally(b.getLocation().clone().add(0.5, 0.5, 0.5), is);
					Wormholes.effectManager.playPortalFailRefund(b);
				});
			}

			FoliaScheduler.runRegion(Wormholes.instance, anchor, tickHolder[0], 1L);
		};

		FoliaScheduler.runRegion(Wormholes.instance, anchor, tickHolder[0]);
	}

	public ItemStack get(PortalType t, int stack)
	{
		switch(t)
		{
			case GATEWAY:
				return getGatewayRune(stack);
			case PORTAL:
				return getPortalRune(stack);
			case WORMHOLE:
				return getWormholeRune(stack);
			default:
				break;
		}

		return null;
	}
}
