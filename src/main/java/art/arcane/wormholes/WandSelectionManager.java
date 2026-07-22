package art.arcane.wormholes;

import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.entity.BlockDisplay;
import org.bukkit.entity.Display;
import org.bukkit.entity.Player;
import org.bukkit.event.Event;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.player.PlayerChangedWorldEvent;
import org.bukkit.event.player.PlayerDropItemEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerItemHeldEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerSwapHandItemsEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;
import org.bukkit.util.Transformation;
import org.bukkit.util.Vector;
import org.joml.Quaternionf;
import org.joml.Vector3f;

import art.arcane.volmlib.util.localization.MessageArgument;
import art.arcane.volmlib.util.scheduling.FoliaScheduler;
import art.arcane.wormholes.localization.WormholesLocalization;
import art.arcane.wormholes.localization.WormholesMessages;
import art.arcane.wormholes.portal.ILocalPortal;
import art.arcane.wormholes.portal.PortalType;
import art.arcane.wormholes.service.WormholesAudience;

public class WandSelectionManager implements Listener
{
	static final int MAX_DRAWN_CELLS = 4096;
	private static final double BUILD_CLICK_RANGE = 64.0D;
	private static final float PANE_THICKNESS = 0.12f;
	private static final float PANE_INSET = 0.02f;

	private final Map<UUID, WandSelection> selections = new ConcurrentHashMap<>();

	public WandSelectionManager()
	{
		Wormholes.v("Starting Wand Selection Manager");
	}

	@EventHandler(priority = EventPriority.HIGH)
	public void on(PlayerInteractEvent e)
	{
		Action action = e.getAction();
		boolean isLeft = action == Action.LEFT_CLICK_AIR || action == Action.LEFT_CLICK_BLOCK;
		boolean isRight = action == Action.RIGHT_CLICK_AIR || action == Action.RIGHT_CLICK_BLOCK;
		if(!isLeft && !isRight)
		{
			return;
		}
		if(e.getHand() != null && e.getHand() != EquipmentSlot.HAND)
		{
			return;
		}
		Player player = e.getPlayer();
		if(!Wormholes.blockManager.isWand(player.getInventory().getItemInMainHand()))
		{
			return;
		}
		if(e.getClickedBlock() != null && e.useInteractedBlock() == Event.Result.DENY)
		{
			return;
		}
		if(isLookingAtPortal(player))
		{
			return;
		}

		WandSelection selection = selections.get(player.getUniqueId());
		if(isLeft)
		{
			if(selection != null && selection.isComplete() && selection.worldId.equals(player.getWorld().getUID()) && isBuildClick(player, selection, e.getClickedBlock()))
			{
				deny(e);
				buildSelection(player, selection);
				return;
			}
			if(action == Action.LEFT_CLICK_BLOCK && e.getClickedBlock() != null)
			{
				deny(e);
				setCorner(player, e.getClickedBlock(), true);
			}
			return;
		}
		if(action == Action.RIGHT_CLICK_BLOCK && e.getClickedBlock() != null)
		{
			deny(e);
			setCorner(player, e.getClickedBlock(), false);
		}
	}

	@EventHandler
	public void on(PlayerItemHeldEvent e)
	{
		ItemStack next = e.getPlayer().getInventory().getItem(e.getNewSlot());
		if(!Wormholes.blockManager.isWand(next))
		{
			clearSelection(e.getPlayer().getUniqueId());
		}
	}

	@EventHandler
	public void on(PlayerSwapHandItemsEvent e)
	{
		if(!Wormholes.blockManager.isWand(e.getMainHandItem()))
		{
			clearSelection(e.getPlayer().getUniqueId());
		}
	}

	@EventHandler
	public void on(PlayerDropItemEvent e)
	{
		if(Wormholes.blockManager.isWand(e.getItemDrop().getItemStack()))
		{
			clearSelection(e.getPlayer().getUniqueId());
		}
	}

	@EventHandler
	public void on(PlayerQuitEvent e)
	{
		clearSelection(e.getPlayer().getUniqueId());
	}

	@EventHandler
	public void on(PlayerChangedWorldEvent e)
	{
		clearSelection(e.getPlayer().getUniqueId());
	}

	static int[] selectionMin(int[] a, int[] b)
	{
		return new int[] { Math.min(a[0], b[0]), Math.min(a[1], b[1]), Math.min(a[2], b[2]) };
	}

	static int[] selectionMax(int[] a, int[] b)
	{
		return new int[] { Math.max(a[0], b[0]), Math.max(a[1], b[1]), Math.max(a[2], b[2]) };
	}

	static int flatAxis(int[] min, int[] max)
	{
		for(int axis = 0; axis < 3; axis++)
		{
			if(min[axis] == max[axis])
			{
				return axis;
			}
		}
		return -1;
	}

	static long cellCount(int[] min, int[] max)
	{
		return (long) (max[0] - min[0] + 1) * (long) (max[1] - min[1] + 1) * (long) (max[2] - min[2] + 1);
	}

	static float[] paneBox(int[] min, int[] max, int normalAxis, float thickness, float inset)
	{
		float[] box = new float[6];
		for(int axis = 0; axis < 3; axis++)
		{
			float extent = (float) (max[axis] - min[axis] + 1);
			if(axis == normalAxis)
			{
				box[axis] = thickness;
				box[axis + 3] = (extent - thickness) / 2.0f;
			}
			else
			{
				box[axis] = extent - (inset * 2.0f);
				box[axis + 3] = inset;
			}
		}
		return box;
	}

	static boolean rayIntersectsBox(double ox, double oy, double oz, double dx, double dy, double dz, double minX, double minY, double minZ, double maxX, double maxY, double maxZ, double range)
	{
		double[] origin = new double[] { ox, oy, oz };
		double[] direction = new double[] { dx, dy, dz };
		double[] lower = new double[] { minX, minY, minZ };
		double[] upper = new double[] { maxX, maxY, maxZ };
		double tMin = 0.0D;
		double tMax = range;
		for(int axis = 0; axis < 3; axis++)
		{
			if(Math.abs(direction[axis]) < 1.0E-9D)
			{
				if(origin[axis] < lower[axis] || origin[axis] > upper[axis])
				{
					return false;
				}
				continue;
			}
			double inverse = 1.0D / direction[axis];
			double t1 = (lower[axis] - origin[axis]) * inverse;
			double t2 = (upper[axis] - origin[axis]) * inverse;
			tMin = Math.max(tMin, Math.min(t1, t2));
			tMax = Math.min(tMax, Math.max(t1, t2));
			if(tMin > tMax)
			{
				return false;
			}
		}
		return true;
	}

	private static void deny(PlayerInteractEvent e)
	{
		if(e.getClickedBlock() != null)
		{
			e.setUseInteractedBlock(Event.Result.DENY);
		}
		e.setCancelled(true);
	}

	private void setCorner(Player player, Block block, boolean primary)
	{
		UUID playerId = player.getUniqueId();
		UUID worldId = block.getWorld().getUID();
		WandSelection previous = selections.get(playerId);
		int[] clicked = new int[] { block.getX(), block.getY(), block.getZ() };
		int[] cornerA = primary ? clicked : (previous != null && previous.worldId.equals(worldId) ? previous.cornerA : null);
		int[] cornerB = primary ? (previous != null && previous.worldId.equals(worldId) ? previous.cornerB : null) : clicked;
		WandSelection selection = new WandSelection(worldId, cornerA, cornerB);
		selections.put(playerId, selection);
		removePane(previous);
		spawnPane(player, selection);
		sendSelectionFeedback(player, selection, primary);
	}

	private void sendSelectionFeedback(Player player, WandSelection selection, boolean primary)
	{
		if(!selection.isComplete())
		{
			WormholesAudience.sendActionBar(player, Wormholes.text().component(
					primary ? WormholesMessages.WAND_CORNER_A : WormholesMessages.WAND_CORNER_B));
			return;
		}
		int[] min = selectionMin(selection.cornerA, selection.cornerB);
		int[] max = selectionMax(selection.cornerA, selection.cornerB);
		int flat = flatAxis(min, max);
		long cells = cellCount(min, max);
		if(flat < 0)
		{
			WormholesAudience.sendActionBar(player, Wormholes.text().component(WormholesMessages.WAND_NOT_FLAT));
			return;
		}
		if(cells > MAX_DRAWN_CELLS)
		{
			WormholesAudience.sendActionBar(player, Wormholes.text().component(
					WormholesMessages.WAND_TOO_LARGE,
					WormholesLocalization.args(
							MessageArgument.untrusted("count", cells),
							MessageArgument.untrusted("maximum", MAX_DRAWN_CELLS))));
			return;
		}
		WormholesAudience.sendActionBar(player, Wormholes.text().component(
				WormholesMessages.WAND_SELECTED,
				WormholesLocalization.args(MessageArgument.untrusted("count", cells))));
	}

	private void buildSelection(Player player, WandSelection selection)
	{
		int[] min = selectionMin(selection.cornerA, selection.cornerB);
		int[] max = selectionMax(selection.cornerA, selection.cornerB);
		if(flatAxis(min, max) < 0)
		{
			WormholesAudience.sendActionBar(player, Wormholes.text().component(WormholesMessages.WAND_NOT_FLAT));
			return;
		}
		long cells = cellCount(min, max);
		if(cells > MAX_DRAWN_CELLS)
		{
			WormholesAudience.sendActionBar(player, Wormholes.text().component(
					WormholesMessages.WAND_TOO_LARGE,
					WormholesLocalization.args(
							MessageArgument.untrusted("count", cells),
							MessageArgument.untrusted("maximum", MAX_DRAWN_CELLS))));
			return;
		}
		World world = player.getWorld();
		Set<Block> blocks = new HashSet<Block>((int) cells);
		for(int x = min[0]; x <= max[0]; x++)
		{
			for(int y = min[1]; y <= max[1]; y++)
			{
				for(int z = min[2]; z <= max[2]; z++)
				{
					blocks.add(world.getBlockAt(x, y, z));
				}
			}
		}
		clearSelection(player.getUniqueId());
		if(Wormholes.constructionManager.constructPortal(player.getUniqueId(), blocks, PortalType.PORTAL, player.getLocation().getDirection()))
		{
			WormholesAudience.sendActionBar(player, Wormholes.text().component(WormholesMessages.WAND_OPENING));
			return;
		}
		WormholesAudience.sendActionBar(player, Wormholes.text().component(WormholesMessages.WAND_OPEN_FAILED));
	}

	private boolean isBuildClick(Player player, WandSelection selection, Block clicked)
	{
		int[] min = selectionMin(selection.cornerA, selection.cornerB);
		int[] max = selectionMax(selection.cornerA, selection.cornerB);
		if(clicked != null
			&& clicked.getX() >= min[0] && clicked.getX() <= max[0]
			&& clicked.getY() >= min[1] && clicked.getY() <= max[1]
			&& clicked.getZ() >= min[2] && clicked.getZ() <= max[2])
		{
			return true;
		}
		Location eye = player.getEyeLocation();
		Vector direction = eye.getDirection();
		return rayIntersectsBox(eye.getX(), eye.getY(), eye.getZ(), direction.getX(), direction.getY(), direction.getZ(),
			min[0], min[1], min[2], max[0] + 1.0D, max[1] + 1.0D, max[2] + 1.0D, BUILD_CLICK_RANGE);
	}

	private boolean isLookingAtPortal(Player player)
	{
		for(ILocalPortal portal : Wormholes.portalManager.getLocalPortals())
		{
			if(portal.isLookingAt(player))
			{
				return true;
			}
		}
		return false;
	}

	private void spawnPane(Player player, WandSelection selection)
	{
		World world = player.getWorld();
		int[] cornerA = selection.cornerA != null ? selection.cornerA : selection.cornerB;
		int[] cornerB = selection.cornerB != null ? selection.cornerB : selection.cornerA;
		int[] min = selectionMin(cornerA, cornerB);
		int[] max = selectionMax(cornerA, cornerB);
		int flat = flatAxis(min, max);
		boolean valid = selection.isComplete() && flat >= 0 && cellCount(min, max) <= MAX_DRAWN_CELLS;
		Material material = !selection.isComplete() || valid ? Material.LIGHT_BLUE_STAINED_GLASS : Material.RED_STAINED_GLASS;
		boolean planar = selection.isComplete() && flat >= 0;
		float[] box = paneBox(min, max, planar ? flat : -1, PANE_THICKNESS, PANE_INSET);
		double playerNormal = switch(planar ? flat : -1)
		{
			case 0 -> player.getLocation().getX();
			case 1 -> player.getLocation().getY();
			case 2 -> player.getLocation().getZ();
			default -> 0.0D;
		};
		boolean playerOnPositiveSide = planar && playerNormal > min[flat] + 0.5D;
		Location anchor = new Location(world, min[0], min[1], min[2]);
		selection.paneAnchor = anchor;
		UUID playerId = player.getUniqueId();
		FoliaScheduler.runRegion(Wormholes.instance, anchor, () ->
		{
			if(selections.get(playerId) != selection)
			{
				return;
			}
			if(planar && world.getBlockAt(min[0], min[1], min[2]).getType().isSolid())
			{
				box[flat + 3] = playerOnPositiveSide ? 1.005f : -(PANE_THICKNESS + 0.005f);
			}
			Transformation transform = new Transformation(new Vector3f(box[3], box[4], box[5]), new Quaternionf(), new Vector3f(box[0], box[1], box[2]), new Quaternionf());
			BlockDisplay display = world.spawn(anchor, BlockDisplay.class, d ->
			{
				d.setBlock(material.createBlockData());
				d.setBrightness(new Display.Brightness(15, 15));
				d.setPersistent(false);
				d.setViewRange(4.0f);
				d.setVisibleByDefault(false);
				d.setTransformation(transform);
			});
			player.showEntity(Wormholes.instance, display);
			Wormholes.effectManager.trackTemporaryDisplay(display);
			selection.pane = display;
			if(selections.get(playerId) != selection)
			{
				Wormholes.effectManager.removeTemporaryDisplay(display);
				selection.pane = null;
			}
		});
	}

	private void clearSelection(UUID playerId)
	{
		removePane(selections.remove(playerId));
	}

	private void removePane(WandSelection selection)
	{
		if(selection == null)
		{
			return;
		}
		BlockDisplay pane = selection.pane;
		Location anchor = selection.paneAnchor;
		if(pane == null || anchor == null)
		{
			return;
		}
		selection.pane = null;
		if(!FoliaScheduler.runRegion(Wormholes.instance, anchor, () -> Wormholes.effectManager.removeTemporaryDisplay(pane)))
		{
			Wormholes.effectManager.removeTemporaryDisplay(pane);
		}
	}

	private static final class WandSelection
	{
		private final UUID worldId;
		private final int[] cornerA;
		private final int[] cornerB;
		private volatile BlockDisplay pane;
		private volatile Location paneAnchor;

		private WandSelection(UUID worldId, int[] cornerA, int[] cornerB)
		{
			this.worldId = worldId;
			this.cornerA = cornerA;
			this.cornerB = cornerB;
		}

		private boolean isComplete()
		{
			return cornerA != null && cornerB != null;
		}
	}
}
