package art.arcane.wormholes;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;

import org.bukkit.Bukkit;
import org.bukkit.Chunk;
import org.bukkit.Color;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.Particle;
import org.bukkit.Sound;
import org.bukkit.SoundCategory;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.data.BlockData;
import org.bukkit.entity.BlockDisplay;
import org.bukkit.entity.Display;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.server.PluginDisableEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.util.Transformation;
import org.joml.Quaternionf;
import org.joml.Vector3f;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;

import art.arcane.wormholes.portal.ILocalPortal;
import art.arcane.wormholes.portal.IPortal;
import art.arcane.wormholes.portal.ITunnel;
import art.arcane.wormholes.portal.RemotePortal;
import art.arcane.wormholes.portal.UniversalTunnel;
import art.arcane.wormholes.network.view.RemoteViewCache;
import art.arcane.wormholes.config.VisualQualityProfile;
import art.arcane.wormholes.service.WormholesAudience;
import art.arcane.volmlib.util.scheduling.AR;
import art.arcane.volmlib.util.scheduling.FoliaScheduler;
import art.arcane.wormholes.util.Area;
import art.arcane.volmlib.util.collection.KList;
import art.arcane.wormholes.util.MSound;
import art.arcane.wormholes.util.ParticleEffect;

public class EffectManager implements Listener
{
	private static final int LOOKING_SCAN_INTERVAL_TICKS = 3;
	private static final int SYNC_SWEEP_INTERVAL_TICKS = 15;
	private static final String EFFECT_ENTITY_TAG = "wormholes_fx";
	private final Map<UUID, Boolean> portalSyncActive = new ConcurrentHashMap<>();
	private final Set<UUID> temporaryDisplays = ConcurrentHashMap.newKeySet();
	private final Object displayLifecycleLock = new Object();
	private volatile boolean closing;

	public EffectManager()
	{
		Wormholes.v("Starting Effect Manager");
		cleanupOrphanedDisplays();

		new AR(LOOKING_SCAN_INTERVAL_TICKS)
		{
			@Override
			public void run()
			{
				if(Wormholes.portalManager == null)
				{
					return;
				}
				List<ILocalPortal> portals = Wormholes.portalManager.getLocalPortals();
				if(portals.isEmpty())
				{
					return;
				}
				for(Player player : Bukkit.getOnlinePlayers())
				{
					FoliaScheduler.runEntity(Wormholes.instance, player, () -> scanLookingPortalsFor(player, portals));
				}
			}
		};

		new AR(SYNC_SWEEP_INTERVAL_TICKS)
		{
			@Override
			public void run()
			{
				sweepRemoteSync();
			}
		};
	}

	public void shutdown()
	{
		Set<UUID> displays;
		synchronized(displayLifecycleLock)
		{
			if(closing)
			{
				return;
			}
			closing = true;
			displays = new HashSet<UUID>(temporaryDisplays);
		}
		portalSyncActive.clear();
		boolean folia = FoliaScheduler.isFoliaThreading(Bukkit.getServer());
		CountDownLatch removals = new CountDownLatch(displays.size());
		for(UUID displayId : displays)
		{
			Entity entity = Bukkit.getEntity(displayId);
			if(entity == null)
			{
				temporaryDisplays.remove(displayId);
				removals.countDown();
				continue;
			}
			if(!folia || FoliaScheduler.isOwnedByCurrentRegion(entity))
			{
				removeTemporaryDisplay(entity);
				removals.countDown();
				continue;
			}
			boolean scheduled = entity.getScheduler().execute(Wormholes.instance, () ->
			{
				removeTemporaryDisplay(entity);
				removals.countDown();
			}, removals::countDown, 1L);
			if(!scheduled)
			{
				removals.countDown();
			}
		}
		if(folia && removals.getCount() > 0)
		{
			try
			{
				removals.await(1L, TimeUnit.SECONDS);
			}
			catch(InterruptedException e)
			{
				Thread.currentThread().interrupt();
			}
		}
	}

	@EventHandler(priority = EventPriority.LOWEST)
	public void onPluginDisable(PluginDisableEvent event)
	{
		if(event.getPlugin() == Wormholes.instance)
		{
			shutdown();
		}
	}

	private void cleanupOrphanedDisplays()
	{
		for(World world : Bukkit.getWorlds())
		{
			for(Chunk chunk : world.getLoadedChunks())
			{
				FoliaScheduler.runRegion(Wormholes.instance, world, chunk.getX(), chunk.getZ(), () ->
				{
					for(Entity entity : chunk.getEntities())
					{
						if(isPortalEffectEntity(entity))
						{
							entity.remove();
						}
					}
				});
			}
		}
	}

	public static boolean isPortalEffectEntity(Entity entity)
	{
		return entity instanceof Display && entity.getScoreboardTags().contains(EFFECT_ENTITY_TAG);
	}

	private void trackTemporaryDisplay(Display display)
	{
		synchronized(displayLifecycleLock)
		{
			if(closing)
			{
				display.remove();
				return;
			}
			display.addScoreboardTag(EFFECT_ENTITY_TAG);
			temporaryDisplays.add(display.getUniqueId());
		}
	}

	private void removeTemporaryDisplay(Entity display)
	{
		if(display == null)
		{
			return;
		}
		temporaryDisplays.remove(display.getUniqueId());
		if(display.isValid())
		{
			display.remove();
		}
	}

	private void sweepRemoteSync()
	{
		RemoteViewCache cache = Wormholes.remoteViewCache;
		if(cache == null || Wormholes.portalManager == null || Wormholes.instance == null)
		{
			portalSyncActive.clear();
			return;
		}

		Set<UUID> currentPortalIds = new HashSet<>();
		for(ILocalPortal portal : Wormholes.portalManager.getLocalPortals())
		{
			if(portal == null || portal.getId() == null)
			{
				continue;
			}
			UUID portalKey = portal.getId();
			currentPortalIds.add(portalKey);
			if(!portal.isOpen() || !portal.hasTunnel())
			{
				portalSyncActive.remove(portalKey);
				continue;
			}
			ITunnel tunnel = portal.getTunnel();
			if(!(tunnel instanceof UniversalTunnel))
			{
				portalSyncActive.remove(portalKey);
				continue;
			}
			IPortal destination = tunnel.getDestination();
			if(!(destination instanceof RemotePortal remote) || remote.getId() == null)
			{
				portalSyncActive.remove(portalKey);
				continue;
			}
			Location center = portal.getCenter();
			if(center == null || center.getWorld() == null)
			{
				portalSyncActive.remove(portalKey);
				continue;
			}

			boolean ready = cache.isViewReady(remote.getId());
			boolean syncing = !ready && cache.hasSlicesFor(remote.getId());
			if(syncing)
			{
				boolean justStarted = portalSyncActive.put(portalKey, Boolean.TRUE) == null;
				FoliaScheduler.runRegion(Wormholes.instance, center, () -> playPortalSyncing(center, justStarted));
			}
			else if(ready && Boolean.TRUE.equals(portalSyncActive.remove(portalKey)))
			{
				FoliaScheduler.runRegion(Wormholes.instance, center, () -> playPortalSyncComplete(center));
			}
			else
			{
				portalSyncActive.remove(portalKey);
			}
		}
		portalSyncActive.keySet().removeIf(portalId -> !currentPortalIds.contains(portalId));
	}

	public void playPortalSyncing(Location center, boolean justStarted)
	{
		World world = center.getWorld();
		if(world == null)
		{
			return;
		}
		if(Settings.ENABLE_PARTICLES)
		{
			world.spawnParticle(Particle.PORTAL, center, 4, 0.45, 0.65, 0.45, 0.18);
			world.spawnParticle(Particle.REVERSE_PORTAL, center, 1, 0.2, 0.35, 0.2, 0.01);
		}
		if(justStarted)
		{
			world.playSound(center, Sound.BLOCK_BEACON_AMBIENT, SoundCategory.BLOCKS, 0.3f, 1.7f);
		}
	}

	public void playPortalSyncComplete(Location center)
	{
		World world = center.getWorld();
		if(world == null)
		{
			return;
		}
		if(Settings.ENABLE_PARTICLES)
		{
			world.spawnParticle(Particle.REVERSE_PORTAL, center, 12, 0.4, 0.6, 0.4, 0.4);
		}
		world.playSound(center, Sound.BLOCK_BEACON_ACTIVATE, SoundCategory.BLOCKS, 0.55f, 1.5f);
	}

	private void scanLookingPortalsFor(Player player, List<ILocalPortal> portals)
	{
		ItemStack handItem = player.getInventory().getItemInMainHand();
		if(!Wormholes.blockManager.isPortalTool(handItem))
		{
			return;
		}

		for(ILocalPortal portal : portals)
		{
			if(portal.isLookingAt(player))
			{
				Location portalCenter = portal.getCenter();
				if(portalCenter == null)
				{
					continue;
				}

				FoliaScheduler.runRegion(Wormholes.instance, portalCenter, () -> portal.onLooking(player, true));
			}
		}
	}

	@EventHandler
	public void on(PlayerInteractEvent e)
	{
		Action action = e.getAction();
		boolean isLeft = action == Action.LEFT_CLICK_AIR || action == Action.LEFT_CLICK_BLOCK;
		boolean isRight = action == Action.RIGHT_CLICK_AIR || action == Action.RIGHT_CLICK_BLOCK;
		if(!isLeft && !isRight)
		{
			return;
		}

		Player player = e.getPlayer();
		ItemStack handItem = player.getInventory().getItemInMainHand();
		if(!Wormholes.blockManager.isWand(handItem))
		{
			return;
		}

		if(action == Action.LEFT_CLICK_BLOCK && e.getClickedBlock() != null && Wormholes.blockManager.getBlock(e.getClickedBlock()) != null)
		{
			return;
		}

		for(ILocalPortal portal : Wormholes.portalManager.getLocalPortals())
		{
			if(!portal.isLookingAt(player))
			{
				continue;
			}

			e.setCancelled(true);
			portal.onWanded(player);
			return;
		}
	}

	public void playNotificationFail(String message, Player p)
	{
		Component component = LegacyComponentSerializer.legacySection().deserialize(message).colorIfAbsent(NamedTextColor.RED);
		WormholesAudience.sendActionBar(p, component);
	}

	public void playNotificationFail(String message, Location l)
	{
		for(Player i : new Area(l, 24).getNearbyPlayers())
		{
			playNotificationFail(message, i);
		}
	}

	public void playNotificationSuccess(String message, Location l)
	{
		for(Player i : new Area(l, 24).getNearbyPlayers())
		{
			playNotificationSuccess(message, i);
		}
	}

	public void playNotificationSuccess(String message, Player p)
	{
		Component component = LegacyComponentSerializer.legacySection().deserialize(message).colorIfAbsent(NamedTextColor.GREEN);
		WormholesAudience.sendActionBar(p, component);
	}

	public void playNotification(ItemStack is, String message, Player p)
	{
		Component component = LegacyComponentSerializer.legacySection().deserialize(message);
		WormholesAudience.sendActionBar(p, component);
	}

	public void playPortalBlockPlaced(Block block)
	{
		block.getWorld().playSound(block.getLocation().clone().add(0.5, 0.5, 0.5), MSound.FRAME_FILL.bukkitSound(), SoundCategory.BLOCKS, 0.65f, 1.1f + ((float) (Math.random() * 0.2)));
	}

	public void playPortalBlockDestroyed(Block block)
	{
		block.getWorld().playSound(block.getLocation().clone().add(0.5, 0.5, 0.5), MSound.GLASS.bukkitSound(), SoundCategory.BLOCKS, 0.5f, 1.55f + ((float) (Math.random() * 0.2)));
	}

	public void playPortalFailOpen(Set<Block> blocks)
	{
		Block block = new KList<Block>(blocks).getRandom();

		for(int i = 0; i < 4; i++)
		{
			block.getWorld().playSound(block.getLocation().clone().add(0.5, 0.5, 0.5), MSound.AMBIENCE_CAVE.bukkitSound(), 2.5f, 0.5f + ((float) (Math.random() * 1.45)));
		}
	}

	public void playPortalFailRefund(Block block)
	{
		ParticleEffect.EXPLOSION_LARGE.display(0f, 1, block.getLocation().clone().add(0.5, 0.5, 0.5), 32);
		block.getWorld().playSound(block.getLocation().clone().add(0.5, 0.5, 0.5), MSound.EXPLODE.bukkitSound(), 0.7f, (float) (1.6 + ((float) (Math.random() * 0.35))));
		block.getWorld().playSound(block.getLocation().clone().add(0.5, 0.5, 0.5), MSound.GLASS.bukkitSound(), 1.2f, (float) (0.25 + ((float) (Math.random() * 0.95))));
	}

	private static final int VORTEX_KEYFRAME_TICKS = 7;
	private static final double VORTEX_BEND_RADIANS = Math.toRadians(42.0D);

	public record PortalBlockSnapshot(Location location, BlockData data)
	{
	}

	private static final class VortexBlock
	{
		private final BlockDisplay display;
		private final double angle0;
		private final double radius0;
		private final double normalOffset0;

		private VortexBlock(BlockDisplay display, double angle0, double radius0, double normalOffset0)
		{
			this.display = display;
			this.angle0 = angle0;
			this.radius0 = radius0;
			this.normalOffset0 = normalOffset0;
		}
	}

	public void playPortalVortex(World world, Location center, List<PortalBlockSnapshot> snapshots)
	{
		if(closing || world == null || center == null || snapshots == null || snapshots.isEmpty())
		{
			return;
		}
		if(!Settings.ENABLE_PARTICLES)
		{
			world.playSound(center, Sound.BLOCK_RESPAWN_ANCHOR_CHARGE, SoundCategory.BLOCKS, 0.55f, 0.55f);
			return;
		}

		double minX = Double.MAX_VALUE;
		double minY = Double.MAX_VALUE;
		double minZ = Double.MAX_VALUE;
		double maxX = -Double.MAX_VALUE;
		double maxY = -Double.MAX_VALUE;
		double maxZ = -Double.MAX_VALUE;
		for(PortalBlockSnapshot snapshot : snapshots)
		{
			double x = Math.floor(snapshot.location().getX());
			double y = Math.floor(snapshot.location().getY());
			double z = Math.floor(snapshot.location().getZ());
			minX = Math.min(minX, x);
			maxX = Math.max(maxX, x);
			minY = Math.min(minY, y);
			maxY = Math.max(maxY, y);
			minZ = Math.min(minZ, z);
			maxZ = Math.max(maxZ, z);
		}
		double extentX = maxX - minX;
		double extentY = maxY - minY;
		double extentZ = maxZ - minZ;
		final int normalAxis = (extentX <= extentY && extentX <= extentZ) ? 0 : (extentY <= extentZ ? 1 : 2);
		final int planeA = normalAxis == 0 ? 1 : 0;
		final int planeB = normalAxis == 2 ? 1 : 2;
		final double[] c = new double[] { center.getX(), center.getY(), center.getZ() };

		int displayCap = formationDisplayCap(Settings.VISUAL_QUALITY_PROFILE);
		List<PortalBlockSnapshot> selected = selectFormationSnapshots(snapshots, center, planeA, planeB, displayCap);
		List<VortexBlock> vortex = new ArrayList<VortexBlock>(selected.size());
		for(PortalBlockSnapshot snapshot : selected)
		{
			Location loc = snapshot.location();
			double cornerX = Math.floor(loc.getX());
			double cornerY = Math.floor(loc.getY());
			double cornerZ = Math.floor(loc.getZ());
			double blockCenterX = cornerX + 0.5D;
			double blockCenterY = cornerY + 0.5D;
			double blockCenterZ = cornerZ + 0.5D;
			BlockData data = snapshot.data();
			Transformation initial = formationTransformation(center.getX(), center.getY(), center.getZ(), blockCenterX, blockCenterY, blockCenterZ, 1.0f, new Quaternionf());
			BlockDisplay display = world.spawn(center, BlockDisplay.class, e ->
			{
				e.setBlock(data);
				e.setBrightness(new Display.Brightness(15, 15));
				e.setPersistent(false);
				e.setViewRange(2.5f);
				e.setTransformation(initial);
			});
			trackTemporaryDisplay(display);
			double[] blockCenter = new double[] { blockCenterX, blockCenterY, blockCenterZ };
			double a0 = blockCenter[planeA] - c[planeA];
			double b0 = blockCenter[planeB] - c[planeB];
			double normalOffset = blockCenter[normalAxis] - c[normalAxis];
			vortex.add(new VortexBlock(display, Math.atan2(b0, a0), Math.hypot(a0, b0), normalOffset));
		}

		world.spawnParticle(Particle.PORTAL, center, 12, 0.65, 0.8, 0.65, 0.35);
		world.playSound(center, Sound.BLOCK_RESPAWN_ANCHOR_CHARGE, SoundCategory.BLOCKS, 0.55f, 0.55f);
		applyVortexKeyframe(vortex, center, c, normalAxis, planeA, planeB, 0.68D, 0.72f, VORTEX_BEND_RADIANS * 0.28D, VORTEX_KEYFRAME_TICKS);
		boolean middleScheduled = FoliaScheduler.runRegion(Wormholes.instance, center,
				() -> applyVortexKeyframeUnlessClosing(vortex, center, c, normalAxis, planeA, planeB, 0.24D, 0.3f, VORTEX_BEND_RADIANS * 0.72D, VORTEX_KEYFRAME_TICKS), VORTEX_KEYFRAME_TICKS);
		boolean collapseScheduled = FoliaScheduler.runRegion(Wormholes.instance, center,
				() -> applyVortexKeyframeUnlessClosing(vortex, center, c, normalAxis, planeA, planeB, 0.0D, 0.025f, VORTEX_BEND_RADIANS, VORTEX_KEYFRAME_TICKS), VORTEX_KEYFRAME_TICKS * 2L);
		boolean cleanupScheduled = FoliaScheduler.runRegion(Wormholes.instance, center, () ->
		{
			removeVortex(vortex);
			if(!closing)
			{
				world.spawnParticle(Particle.REVERSE_PORTAL, center, 16, 0.08, 0.12, 0.08, 0.45);
				world.spawnParticle(Particle.SCULK_SOUL, center, 4, 0.12, 0.18, 0.12, 0.02);
			}
		}, VORTEX_KEYFRAME_TICKS * 3L);
		if(!middleScheduled || !collapseScheduled || !cleanupScheduled)
		{
			removeVortex(vortex);
		}
	}

	private void removeVortex(List<VortexBlock> vortex)
	{
		for(VortexBlock block : vortex)
		{
			removeTemporaryDisplay(block.display);
		}
	}

	static int formationDisplayCap(VisualQualityProfile profile)
	{
		return switch(profile)
		{
			case PERFORMANCE -> 6;
			case BALANCED -> 10;
			case AUTO -> 12;
			case CINEMATIC -> 16;
		};
	}

	private static List<PortalBlockSnapshot> selectFormationSnapshots(List<PortalBlockSnapshot> snapshots, Location center, int planeA, int planeB, int cap)
	{
		if(snapshots.size() <= cap)
		{
			return List.copyOf(snapshots);
		}
		PortalBlockSnapshot[] sectors = new PortalBlockSnapshot[cap];
		double[] sectorRadius = new double[cap];
		Arrays.fill(sectorRadius, -1.0D);
		for(PortalBlockSnapshot snapshot : snapshots)
		{
			double offsetA = coordinate(snapshot.location(), planeA) + 0.5D - coordinate(center, planeA);
			double offsetB = coordinate(snapshot.location(), planeB) + 0.5D - coordinate(center, planeB);
			double normalizedAngle = (Math.atan2(offsetB, offsetA) + Math.PI) / (Math.PI * 2.0D);
			int sector = Math.min(cap - 1, Math.max(0, (int) Math.floor(normalizedAngle * cap)));
			double radius = (offsetA * offsetA) + (offsetB * offsetB);
			if(radius > sectorRadius[sector])
			{
				sectorRadius[sector] = radius;
				sectors[sector] = snapshot;
			}
		}
		List<PortalBlockSnapshot> selected = new ArrayList<PortalBlockSnapshot>(cap);
		for(PortalBlockSnapshot snapshot : sectors)
		{
			if(snapshot != null)
			{
				selected.add(snapshot);
			}
		}
		for(PortalBlockSnapshot candidate : snapshots)
		{
			if(selected.size() >= cap)
			{
				break;
			}
			if(!selected.contains(candidate))
			{
				selected.add(candidate);
			}
		}
		return selected;
	}

	private void applyVortexKeyframe(List<VortexBlock> vortex, Location anchor, double[] center, int normalAxis, int planeA, int planeB,
			double radiusFactor, float scale, double bend, int duration)
	{
		for(VortexBlock block : vortex)
		{
			if(!block.display.isValid())
			{
				continue;
			}
			double angle = block.angle0 + bend;
			double radius = block.radius0 * radiusFactor;
			double[] target = new double[] { center[0], center[1], center[2] };
			target[normalAxis] += block.normalOffset0 * radiusFactor;
			target[planeA] += radius * Math.cos(angle);
			target[planeB] += radius * Math.sin(angle);
			Quaternionf rotation = axisRotation(normalAxis, (float) bend);
			block.display.setInterpolationDelay(0);
			block.display.setInterpolationDuration(duration);
			block.display.setTransformation(formationTransformation(anchor.getX(), anchor.getY(), anchor.getZ(), target[0], target[1], target[2], scale, rotation));
		}
	}

	private void applyVortexKeyframeUnlessClosing(List<VortexBlock> vortex, Location anchor, double[] center, int normalAxis, int planeA, int planeB,
			double radiusFactor, float scale, double bend, int duration)
	{
		if(!closing)
		{
			applyVortexKeyframe(vortex, anchor, center, normalAxis, planeA, planeB, radiusFactor, scale, bend, duration);
		}
	}

	private static Transformation formationTransformation(double anchorX, double anchorY, double anchorZ, double targetX, double targetY, double targetZ, float scale, Quaternionf rotation)
	{
		float half = 0.5f * scale;
		Vector3f pivot = rotation.transform(new Vector3f(half, half, half));
		Vector3f translation = new Vector3f((float) (targetX - anchorX) - pivot.x, (float) (targetY - anchorY) - pivot.y, (float) (targetZ - anchorZ) - pivot.z);
		return new Transformation(translation, rotation, new Vector3f(scale, scale, scale), new Quaternionf());
	}

	private static double coordinate(Location location, int axis)
	{
		return switch(axis)
		{
			case 0 -> location.getX();
			case 1 -> location.getY();
			default -> location.getZ();
		};
	}


	private static final int CLOSE_CRACK_TICKS = 7;

	static CloseEffectPlan closeEffectPlan(VisualQualityProfile profile)
	{
		return switch(profile)
		{
			case PERFORMANCE -> new CloseEffectPlan(3, 1, 6);
			case BALANCED -> new CloseEffectPlan(4, 2, 8);
			case AUTO -> new CloseEffectPlan(4, 2, 10);
			case CINEMATIC -> new CloseEffectPlan(5, 3, 12);
		};
	}

	static double ellipseRadius(double halfA, double halfB, double angle)
	{
		double cos = Math.cos(angle);
		double sin = Math.sin(angle);
		return 1.0D / Math.sqrt((cos * cos) / (halfA * halfA) + (sin * sin) / (halfB * halfB));
	}

	record CloseEffectPlan(int branches, int segments, int shards)
	{
	}

	public void playPortalClose(World world, Location corner, double sx, double sy, double sz, BooleanSupplier active)
	{
		if(closing || world == null || corner == null || active == null || !active.getAsBoolean())
		{
			return;
		}

		double[] ext = new double[] { sx, sy, sz };
		final int normalAxis = (ext[0] <= ext[1] && ext[0] <= ext[2]) ? 0 : (ext[1] <= ext[2] ? 1 : 2);
		final int planeA = normalAxis == 0 ? 1 : 0;
		final int planeB = normalAxis == 2 ? 1 : 2;
		final double[] cc = new double[] { corner.getX() + (sx / 2.0D), corner.getY() + (sy / 2.0D), corner.getZ() + (sz / 2.0D) };
		Location center = new Location(world, cc[0], cc[1], cc[2]);
		final double halfA = Math.max(0.3D, ext[planeA] / 2.0D);
		final double halfB = Math.max(0.3D, ext[planeB] / 2.0D);
		CloseEffectPlan effectPlan = closeEffectPlan(Settings.VISUAL_QUALITY_PROFILE);
		world.playSound(center, Sound.BLOCK_AMETHYST_BLOCK_HIT, SoundCategory.BLOCKS, 0.35f, 0.65f);
		if(!Settings.ENABLE_PARTICLES)
		{
			return;
		}

		float thickness = 0.16f;
		float paneA = (float) Math.max(0.25D, ext[planeA]);
		float paneB = (float) Math.max(0.25D, ext[planeB]);
		BlockData glass = Material.TINTED_GLASS.createBlockData();
		BlockDisplay pane = world.spawn(center, BlockDisplay.class, e ->
		{
			e.setBlock(glass);
			e.setBrightness(new Display.Brightness(10, 15));
			e.setPersistent(false);
			e.setViewRange(2.5f);
			e.setTransformation(centeredPaneTransform(normalAxis, planeA, planeB, thickness, paneA, paneB));
		});
		trackTemporaryDisplay(pane);

		double[] crackAngle = new double[effectPlan.branches()];
		double[] crackReach = new double[effectPlan.branches()];
		double[] crackBend = new double[effectPlan.branches()];
		for(int i = 0; i < effectPlan.branches(); i++)
		{
			crackAngle[i] = ((Math.PI * 2.0D * i) / effectPlan.branches()) + ((Math.random() - 0.5D) * 0.45D);
			crackReach[i] = 0.6D + (Math.random() * 0.4D);
			crackBend[i] = (Math.random() - 0.5D) * 0.7D;
		}

		Particle.DustOptions crackDust = new Particle.DustOptions(Color.fromRGB(235, 245, 255), 0.7f);
		BlockData shardData = Material.GLASS.createBlockData();

		int[] tick = new int[] { 0 };
		Runnable[] holder = new Runnable[1];
		holder[0] = () ->
		{
			if(closing || !active.getAsBoolean())
			{
				removeTemporaryDisplay(pane);
				return;
			}
			int t = tick[0]++;
			if(t >= CLOSE_CRACK_TICKS)
			{
				removeTemporaryDisplay(pane);
				for(int i = 0; i < effectPlan.shards(); i++)
				{
					double a = Math.random() * Math.PI * 2.0D;
					double radialA = Math.cos(a);
					double radialB = Math.sin(a);
					double rr = ellipseRadius(halfA, halfB, a) * (0.2D + (Math.random() * 0.8D));
					double[] p = new double[] { cc[0], cc[1], cc[2] };
					p[planeA] = cc[planeA] + (rr * radialA);
					p[planeB] = cc[planeB] + (rr * radialB);
					double[] velocity = outwardShardVelocity(normalAxis, planeA, planeB, radialA, radialB, (i & 1) == 0 ? 1.0D : -1.0D);
					world.spawnParticle(Particle.BLOCK, new Location(world, p[0], p[1], p[2]), 0, velocity[0], velocity[1], velocity[2], 0.35D, shardData);
				}
				world.spawnParticle(Particle.REVERSE_PORTAL, center, Math.min(8, effectPlan.shards()), 0.15, 0.22, 0.15, 0.5);
				world.spawnParticle(Particle.FLASH, center, 1, 0.0, 0.0, 0.0, 0.0);
				world.playSound(center, Sound.BLOCK_GLASS_BREAK, SoundCategory.BLOCKS, 0.75f, 0.82f);
				world.playSound(center, Sound.BLOCK_BEACON_DEACTIVATE, SoundCategory.BLOCKS, 0.4f, 0.55f);
				return;
			}

			double frac = (double) (t + 1) / (double) CLOSE_CRACK_TICKS;
			for(int i = 0; i < effectPlan.branches(); i++)
			{
				for(int segment = 1; segment <= effectPlan.segments(); segment++)
				{
					double segmentFraction = (double) segment / effectPlan.segments();
					double angle = crackAngle[i] + (crackBend[i] * frac * frac * segmentFraction);
					double len = ellipseRadius(halfA, halfB, angle) * crackReach[i] * frac * segmentFraction;
					double[] p = new double[] { cc[0], cc[1], cc[2] };
					p[planeA] = cc[planeA] + (len * Math.cos(angle));
					p[planeB] = cc[planeB] + (len * Math.sin(angle));
					world.spawnParticle(Particle.DUST, new Location(world, p[0], p[1], p[2]), 1, 0.0, 0.0, 0.0, 0.0, crackDust);
				}
			}
			if(t == CLOSE_CRACK_TICKS - 2 && pane.isValid())
			{
				boolean collapseA = planeA == 1 || planeB != 1;
				float collapsedA = collapseA ? 0.06f : paneA;
				float collapsedB = collapseA ? paneB : 0.06f;
				pane.setInterpolationDelay(0);
				pane.setInterpolationDuration(2);
				pane.setTransformation(centeredPaneTransform(normalAxis, planeA, planeB, thickness, collapsedA, collapsedB));
			}
			if(!FoliaScheduler.runRegion(Wormholes.instance, center, holder[0], 1L))
			{
				removeTemporaryDisplay(pane);
			}
		};
		if(!FoliaScheduler.runRegion(Wormholes.instance, center, holder[0], 1L))
		{
			removeTemporaryDisplay(pane);
		}
	}

	private static Transformation centeredPaneTransform(int normalAxis, int planeA, int planeB, float thickness, float scaleA, float scaleB)
	{
		float[] scale = new float[3];
		scale[normalAxis] = thickness;
		scale[planeA] = scaleA;
		scale[planeB] = scaleB;
		return new Transformation(new Vector3f(-scale[0] / 2.0f, -scale[1] / 2.0f, -scale[2] / 2.0f), new Quaternionf(), new Vector3f(scale[0], scale[1], scale[2]), new Quaternionf());
	}

	static double[] outwardShardVelocity(int normalAxis, int planeA, int planeB, double radialA, double radialB, double normalDirection)
	{
		double[] velocity = new double[3];
		velocity[normalAxis] = normalDirection * 0.65D;
		velocity[planeA] = radialA;
		velocity[planeB] = radialB;
		return velocity;
	}

	public void playPortalOpen(World world, Location center, double sx, double sy, double sz, BooleanSupplier active)
	{
		if(closing || world == null || center == null || active == null || !active.getAsBoolean())
		{
			return;
		}
		playPortalOpenClimaxIfActive(world, center, sx, sy, sz, active);
		final int afterglowTicks = 3;
		int[] tick = new int[] { 0 };
		Runnable[] holder = new Runnable[1];
		holder[0] = () ->
		{
			if(closing || !active.getAsBoolean())
			{
				return;
			}
			int t = tick[0]++;
			if(t >= afterglowTicks)
			{
				return;
			}
			if(Settings.ENABLE_PARTICLES)
			{
				double spread = Math.max(0.12D, 0.45D - (((double) t / afterglowTicks) * 0.25D));
				world.spawnParticle(Particle.REVERSE_PORTAL, center, 3, spread, spread, spread, 0.18);
				world.spawnParticle(Particle.ENCHANT, center, 1, spread, spread, spread, 0.03);
			}
			FoliaScheduler.runRegion(Wormholes.instance, center, holder[0], 1L);
		};
		FoliaScheduler.runRegion(Wormholes.instance, center, holder[0], 1L);
	}

	public void playPortalOpenClimax(World world, Location center, double sx, double sy, double sz)
	{
		playPortalOpenClimaxIfActive(world, center, sx, sy, sz, () -> true);
	}

	private void playPortalOpenClimaxIfActive(World world, Location center, double sx, double sy, double sz, BooleanSupplier active)
	{
		if(closing || world == null || center == null || active == null || !active.getAsBoolean())
		{
			return;
		}
		if(Settings.ENABLE_PARTICLES)
		{
			world.spawnParticle(Particle.FLASH, center, 1, 0.0, 0.0, 0.0, 0.0);
			world.spawnParticle(Particle.REVERSE_PORTAL, center, 16, 0.18, 0.28, 0.18, 0.55);
			world.spawnParticle(Particle.END_ROD, center, 6, 0.1, 0.1, 0.1, 0.18);
		}
		world.playSound(center, Sound.BLOCK_END_PORTAL_SPAWN, SoundCategory.BLOCKS, 0.75f, 0.88f);
		world.playSound(center, Sound.BLOCK_BEACON_ACTIVATE, SoundCategory.BLOCKS, 0.4f, 0.62f);
		if(Settings.ENABLE_PARTICLES)
		{
			playPortalRipple(world, center, sx, sy, sz, active);
		}
	}

	private void playPortalRipple(World world, Location center, double sx, double sy, double sz, BooleanSupplier active)
	{
		int normalAxis = 1;
		double min = sy;
		if(sx <= min)
		{
			min = sx;
			normalAxis = 0;
		}
		if(sz < min)
		{
			normalAxis = 2;
		}
		final int planeA = normalAxis == 0 ? 1 : 0;
		final int planeB = normalAxis == 2 ? 1 : 2;
		double extA = planeA == 0 ? sx : (planeA == 1 ? sy : sz);
		double extB = planeB == 0 ? sx : (planeB == 1 ? sy : sz);
		final double maxR = Math.max(1.0D, (Math.max(extA, extB) * 0.5D) + 0.75D);
		final double[] cc = new double[] { center.getX(), center.getY(), center.getZ() };
		final int ripTicks = 5;
		final int points = openingRingPoints(Settings.VISUAL_QUALITY_PROFILE);
		final Particle.DustTransition ringDust = new Particle.DustTransition(Color.fromRGB(185, 105, 255), Color.fromRGB(20, 5, 35), 0.85f);
		final int[] rt = new int[] { 0 };
		final Runnable[] holder = new Runnable[1];
		holder[0] = () ->
		{
			if(closing || !active.getAsBoolean())
			{
				return;
			}
			int t = rt[0]++;
			if(t >= ripTicks)
			{
				return;
			}
			double frac = (double) (t + 1) / (double) ripTicks;
			double radius = 0.12D + (maxR * Math.pow(1.0D - frac, 1.35D));
			double twist = frac * Math.PI * 0.85D;
			for(int i = 0; i < points; i++)
			{
				double a = ((Math.PI * 2.0D * i) / points) + twist;
				double[] p = new double[] { cc[0], cc[1], cc[2] };
				p[planeA] = cc[planeA] + (radius * Math.cos(a));
				p[planeB] = cc[planeB] + (radius * Math.sin(a));
				world.spawnParticle(Particle.DUST_COLOR_TRANSITION, new Location(world, p[0], p[1], p[2]), 1, 0.0, 0.0, 0.0, 0.0, ringDust);
			}
			if(t == ripTicks - 1)
			{
				world.spawnParticle(Particle.SONIC_BOOM, center, 1, 0.0, 0.0, 0.0, 0.0);
			}
			FoliaScheduler.runRegion(Wormholes.instance, center, holder[0], 1L);
		};
		FoliaScheduler.runRegion(Wormholes.instance, center, holder[0], 1L);
	}

	static int openingRingPoints(VisualQualityProfile profile)
	{
		return switch(profile)
		{
			case PERFORMANCE -> 6;
			case BALANCED -> 8;
			case AUTO -> 10;
			case CINEMATIC -> 12;
		};
	}

	public void playPortalDeletion(World world, Location corner, double sx, double sy, double sz)
	{
		playPortalClose(world, corner, sx, sy, sz, () -> true);
	}

	private static Quaternionf axisRotation(int axis, float angle)
	{
		return switch(axis)
		{
			case 0 -> new Quaternionf().rotationX(angle);
			case 2 -> new Quaternionf().rotationZ(angle);
			default -> new Quaternionf().rotationY(angle);
		};
	}
}
