package art.arcane.wormholes;

import java.util.HashSet;
import java.util.Iterator;
import java.util.Set;

import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.GameMode;
import org.bukkit.Keyed;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.block.Block;
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
import art.arcane.wormholes.util.Direction;
import art.arcane.wormholes.util.GChunk;
import art.arcane.volmlib.util.collection.KList;
import art.arcane.volmlib.util.collection.KMap;
import art.arcane.volmlib.util.collection.KSet;
import art.arcane.wormholes.util.J;
import art.arcane.wormholes.util.M;
import art.arcane.volmlib.util.scheduling.FoliaScheduler;
import art.arcane.wormholes.util.W;

public class BlockManager implements Listener
{
	private final KMap<GChunk, KSet<PortalBlock>> blocks;

	public BlockManager()
	{
		Wormholes.v("Starting Block Manager");
		registerRecipes();
		blocks = new KMap<>();
		J.ar(() -> updatePlacedBlocks(), 9);
	}

	public void destroyAll()
	{
		Wormholes.v("Releasing tracked portal blocks (" + blocks.k() + " chunks)");
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
					KSet<PortalBlock> set = blocks.get(new GChunk(cx + dx, cz + dz, world));
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
		if(!isSame(getWand(), e.getPlayer().getInventory().getItemInMainHand()))
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
			Wormholes.sendActionBar(e.getPlayer(),Component.text("That is not a placed rune. Place runes (Portal/Wormhole/Gateway) as one connected flat plane, then left-click one with the wand.", NamedTextColor.GOLD));
			return;
		}

		Wormholes.sendActionBar(e.getPlayer(),Component.text("Forming portal... shape must be one connected flat plane of " + b.getType().name().toLowerCase() + " runes.", NamedTextColor.AQUA));
		construct(e.getPlayer(), e.getClickedBlock());
	}

	private void construct(Player player, Block clickedBlock)
	{
		Set<Block> blocks = new HashSet<>();
		KList<Block> search = new KList<>();
		PortalBlock init = getBlock(clickedBlock);
		PortalType type = init.getType();
		search.addAll(findBlocks(blocks, clickedBlock, type));
		Direction d = Direction.closest(player.getLocation().getDirection());
		Vector look = player.getLocation().getDirection();
		Location anchor = clickedBlock.getLocation();

		Runnable[] tickHolder = new Runnable[1];
		tickHolder[0] = () ->
		{
			if(!M.r(Settings.PORTAL_CONSTRUCT_SPEED))
			{
				FoliaScheduler.runRegion(Wormholes.instance, anchor, tickHolder[0], 1L);
				return;
			}

			search.removeIf(i -> getBlock(i) == null);

			if(!search.isEmpty())
			{
				Block next = search.popRandom();
				search.addAll(findBlocks(blocks, next, type));
				FoliaScheduler.runRegion(Wormholes.instance, anchor, tickHolder[0], 1L);
				return;
			}

			Wormholes.constructionManager.constructPortal(player, blocks, type, d, look);
		};

		FoliaScheduler.runRegion(Wormholes.instance, anchor, tickHolder[0], 1L);
	}

	public Set<Block> findBlocks(Set<Block> blocks, Block cursor, PortalType type)
	{
		if(getBlock(cursor) != null)
		{
			blocks.add(cursor);
			cursor.setType(Material.AIR);
			Wormholes.effectManager.playPortalOpening(blocks.size(), cursor);
			removeBlock(getBlock(cursor));
		}

		Set<Block> found = new HashSet<>();

		for(Block i : W.blockFaces(cursor))
		{
			if(!blocks.contains(i) && isBlock(i, type))
			{
				found.add(i);
			}
		}

		return found;
	}

	@EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
	public void on(BlockPlaceEvent e)
	{
		ItemStack inHand = e.getItemInHand();
		PortalType placedType = null;

		if(isSame(inHand, getPortalRune(1)))
		{
			placedType = PortalType.PORTAL;
		}
		else if(isSame(inHand, getWormholeRune(1)))
		{
			placedType = PortalType.WORMHOLE;
		}
		else if(isSame(inHand, getGatewayRune(1)))
		{
			placedType = PortalType.GATEWAY;
		}

		if(placedType == null)
		{
			return;
		}

		placeBlock(new PortalBlock(placedType, e.getBlock().getLocation()));
		Wormholes.sendActionBar(e.getPlayer(),Component.text("Rune placed. Build one connected flat plane, then left-click any rune with the Portal Wand.", NamedTextColor.AQUA));
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
		if(blocks.containsKey(new GChunk(block.getLocation().getChunk())))
		{
			for(PortalBlock i : blocks.get(new GChunk(block.getLocation().getChunk())))
			{
				if(i.getLocation().equals(block.getLocation()))
				{
					return i;
				}
			}
		}

		return null;
	}

	public void removeBlock(PortalBlock block)
	{
		if(blocks.containsKey(new GChunk(block.getLocation().getChunk())))
		{
			blocks.get(new GChunk(block.getLocation().getChunk())).remove(block);

			if(blocks.get(new GChunk(block.getLocation().getChunk())).isEmpty())
			{
				blocks.remove(new GChunk(block.getLocation().getChunk()));
			}

			Wormholes.effectManager.playPortalBlockDestroyed(block.getLocation().getBlock());
		}
	}

	public void placeBlock(PortalBlock block)
	{
		if(!blocks.containsKey(new GChunk(block.getLocation().getChunk())))
		{
			blocks.put(new GChunk(block.getLocation().getChunk()), new KSet<>());
		}

		blocks.get(new GChunk(block.getLocation().getChunk())).add(block);
		Wormholes.effectManager.playPortalBlockPlaced(block.getLocation().getBlock());
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

		ItemStack a = is.clone();
		ItemStack b = ib.clone();
		a.setAmount(1);
		b.setAmount(1);

		return a.equals(b);
	}

	public boolean isPortalTool(ItemStack item)
	{
		return isSame(item, getWand()) || isPortalRune(item);
	}

	public boolean isPortalRune(ItemStack item)
	{
		return isSame(item, getPortalRune(1)) || isSame(item, getWormholeRune(1)) || isSame(item, getGatewayRune(1));
	}

	public ItemStack getWand()
	{
		ItemStack is = new ItemStack(Material.BLAZE_ROD);
		ItemMeta meta = is.getItemMeta();
		meta.addEnchant(Enchantment.INFINITY, 1, true);
		meta.setDisplayName(ChatColor.GOLD + "" + ChatColor.BOLD + "Portal Wand");
		is.setItemMeta(meta);

		return is;
	}

	public ItemStack getPortalRune(int c)
	{
		ItemStack is = new ItemStack(Material.PRISMARINE);
		ItemMeta meta = is.getItemMeta();
		meta.addEnchant(Enchantment.INFINITY, 1, true);
		meta.setDisplayName(ChatColor.LIGHT_PURPLE + "" + ChatColor.BOLD + "Portal Rune");
		is.setItemMeta(meta);
		is.setAmount(c);

		return is;
	}

	public ItemStack getWormholeRune(int c)
	{
		ItemStack is = new ItemStack(Material.DARK_PRISMARINE);
		ItemMeta meta = is.getItemMeta();
		meta.addEnchant(Enchantment.INFINITY, 1, true);
		meta.setDisplayName(ChatColor.GOLD + "" + ChatColor.BOLD + "Wormhole Rune");
		is.setItemMeta(meta);
		is.setAmount(c);

		return is;
	}

	public ItemStack getGatewayRune(int c)
	{
		ItemStack is = new ItemStack(Material.BLACK_STAINED_GLASS);
		ItemMeta meta = is.getItemMeta();
		meta.addEnchant(Enchantment.INFINITY, 1, true);
		meta.setDisplayName(ChatColor.RED + "" + ChatColor.BOLD + "Gateway Rune");
		is.setItemMeta(meta);
		is.setAmount(c);

		return is;
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
