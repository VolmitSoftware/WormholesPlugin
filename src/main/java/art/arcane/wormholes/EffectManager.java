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
import art.arcane.wormholes.platform.WormholesPlatform;
import art.arcane.wormholes.render.PortalToolPreviewRenderer;
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
	private static final int SWIRL_TICKS = 20;
	private static final int OPEN_PRELUDE_TICKS = 18;
	private static final int KAWOOSH_TICKS = 12;
	private static final int KAWOOSH_SURGE_TICKS = 5;
	private static final int KAWOOSH_BOOM_DELAY_TICKS = 5;
	private static final double KAWOOSH_TWIST_RADIANS = Math.PI * 1.25D;
	private static final int CLOSE_CRACK_TICKS = 10;
	private static final double VORTEX_MATCH_RADIUS_SQUARED = 16.0D;
	private static final long VORTEX_MARKER_GRACE_MILLIS = 1500L;
	private final Map<UUID, Boolean> portalSyncActive = new ConcurrentHashMap<>();
	private final Map<VortexKey, VortexMarker> activeVortices = new ConcurrentHashMap<>();
	private final Set<UUID> temporaryDisplays = ConcurrentHashMap.newKeySet();
	private final PortalToolPreviewRenderer portalToolPreviewRenderer;
	private final Object displayLifecycleLock = new Object();
	private volatile boolean closing;

	public EffectManager()
	{
		Wormholes.v("Starting Effect Manager");
		portalToolPreviewRenderer = new PortalToolPreviewRenderer();
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
		activeVortices.clear();
		portalToolPreviewRenderer.clear();
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
			boolean scheduled = WormholesPlatform.scheduleEntity(Wormholes.instance, entity, () ->
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

	public void trackTemporaryDisplay(Display display)
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

	public void removeTemporaryDisplay(Entity display)
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
		ItemStack mainHandItem = player.getInventory().getItemInMainHand();
		ItemStack offHandItem = player.getInventory().getItemInOffHand();
		if(!Wormholes.blockManager.isPortalTool(mainHandItem) && !Wormholes.blockManager.isPortalTool(offHandItem))
		{
			return;
		}

		portalToolPreviewRenderer.render(player, portals);
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
		final double convergeSx = extentX + 1.0D;
		final double convergeSy = extentY + 1.0D;
		final double convergeSz = extentZ + 1.0D;

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
			vortex.add(new VortexBlock(display, Math.atan2(b0, a0), Math.hypot(a0, b0), normalOffset,
					1.2D + (Math.random() * 1.0D), 1.2D + (Math.random() * 0.5D), 0.8D + (Math.random() * 0.5D),
					Math.random() * Math.PI * 2.0D, 0.2D + (Math.random() * 0.25D)));
		}

		world.spawnParticle(Particle.PORTAL, center, 12, 0.65, 0.8, 0.65, 0.35);
		world.playSound(center, Sound.BLOCK_RESPAWN_ANCHOR_CHARGE, SoundCategory.BLOCKS, 0.55f, 0.55f);
		world.playSound(center, MSound.FRAME_SPAWN.bukkitSound(), SoundCategory.BLOCKS, 1.4f, 0.35f);

		VortexKey markerKey = vortexKey(world, center);
		VortexMarker marker = new VortexMarker(markerKey, world, center.getX(), center.getY(), center.getZ(),
				System.currentTimeMillis() + (SWIRL_TICKS * 50L) + VORTEX_MARKER_GRACE_MILLIS);
		activeVortices.put(markerKey, marker);

		int[] tick = new int[] { 0 };
		Runnable[] holder = new Runnable[1];
		holder[0] = () ->
		{
			if(closing)
			{
				activeVortices.remove(markerKey, marker);
				removeVortex(vortex);
				return;
			}
			int t = tick[0]++;
			if(t >= SWIRL_TICKS)
			{
				removeVortex(vortex);
				playVortexConvergence(world, center, marker, convergeSx, convergeSy, convergeSz);
				if(!FoliaScheduler.runRegion(Wormholes.instance, center, () -> activeVortices.remove(markerKey, marker), VORTEX_MARKER_GRACE_MILLIS / 50L))
				{
					activeVortices.remove(markerKey, marker);
				}
				return;
			}
			double frac = (double) (t + 1) / (double) SWIRL_TICKS;
			double[] target = new double[3];
			int index = 0;
			for(VortexBlock block : vortex)
			{
				index++;
				if(!block.display().isValid())
				{
					continue;
				}
				double spin = frac * block.turns() * Math.PI * 2.0D;
				double angle = block.angle0() + spin;
				double radiusFactor = Math.pow(1.0D - frac, block.radialExponent());
				double radius = block.radius0() * radiusFactor;
				float scale = (float) Math.max(0.04D, Math.pow(1.0D - frac, block.shrinkExponent()));
				target[0] = c[0];
				target[1] = c[1];
				target[2] = c[2];
				target[normalAxis] += block.normalOffset0() * radiusFactor;
				target[planeA] += radius * Math.cos(angle);
				target[planeB] += radius * Math.sin(angle);
				float tiltAngle = (float) (block.wobbleAmplitude() * Math.sin((spin * 2.0D) + block.wobblePhase()));
				block.display().setInterpolationDelay(0);
				block.display().setInterpolationDuration(2);
				block.display().setTransformation(formationTransformation(center.getX(), center.getY(), center.getZ(), target[0], target[1], target[2], scale, axisRotation(normalAxis, tiltAngle)));
				if((t & 1) == 0)
				{
					Particle trail = (index & 1) == 0 ? Particle.PORTAL : Particle.REVERSE_PORTAL;
					world.spawnParticle(trail, target[0], target[1], target[2], 1, 0.04, 0.04, 0.04, 0.02);
				}
			}
			if(!FoliaScheduler.runRegion(Wormholes.instance, center, holder[0], 1L))
			{
				activeVortices.remove(markerKey, marker);
				removeVortex(vortex);
			}
		};
		if(!FoliaScheduler.runRegion(Wormholes.instance, center, holder[0], 1L))
		{
			activeVortices.remove(markerKey, marker);
			removeVortex(vortex);
		}
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
		world.playSound(center, MSound.ECHEST_CLOSE.bukkitSound(), SoundCategory.BLOCKS, 1.2f, 0.55f);
		if(!Settings.ENABLE_PARTICLES)
		{
			world.playSound(center, Sound.BLOCK_GLASS_BREAK, SoundCategory.BLOCKS, 1.0f, 0.9f);
			return;
		}

		float thickness = 0.18f;
		float paneA = (float) Math.max(0.25D, ext[planeA]);
		float paneB = (float) Math.max(0.25D, ext[planeB]);
		BlockData glass = Material.GLASS.createBlockData();
		BlockDisplay pane = world.spawn(center, BlockDisplay.class, e ->
		{
			e.setBlock(glass);
			e.setBrightness(new Display.Brightness(15, 15));
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
		double[] branchletAngle = new double[effectPlan.branches()];
		double[] branchletStart = new double[effectPlan.branches()];
		double[] branchletReach = new double[effectPlan.branches()];
		int[] branchletTick = new int[effectPlan.branches()];
		for(int i = 0; i < effectPlan.branches(); i++)
		{
			double side = Math.random() < 0.5D ? -1.0D : 1.0D;
			branchletAngle[i] = crackAngle[i] + (side * (0.55D + (Math.random() * 0.5D)));
			branchletStart[i] = 0.35D + (Math.random() * 0.3D);
			branchletReach[i] = 0.18D + (Math.random() * 0.22D);
			branchletTick[i] = 3 + (int) (Math.random() * 4.0D);
		}

		Particle.DustOptions crackDust = new Particle.DustOptions(Color.fromRGB(235, 245, 255), 0.75f);
		Particle.DustOptions branchletDust = new Particle.DustOptions(Color.fromRGB(210, 230, 255), 0.55f);
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
			double[] point = new double[3];
			if(t >= CLOSE_CRACK_TICKS)
			{
				removeTemporaryDisplay(pane);
				for(int i = 0; i < effectPlan.shards(); i++)
				{
					double a = Math.random() * Math.PI * 2.0D;
					double radialA = Math.cos(a);
					double radialB = Math.sin(a);
					double rr = ellipseRadius(halfA, halfB, a) * (0.15D + (Math.random() * 0.85D));
					point[0] = cc[0];
					point[1] = cc[1];
					point[2] = cc[2];
					point[planeA] += rr * radialA;
					point[planeB] += rr * radialB;
					double[] velocity = outwardShardVelocity(normalAxis, planeA, planeB, radialA, radialB, (i & 1) == 0 ? 1.0D : -1.0D);
					world.spawnParticle(Particle.BLOCK, point[0], point[1], point[2], 0, velocity[0], velocity[1], velocity[2], 0.35D, shardData);
				}
				world.spawnParticle(Particle.REVERSE_PORTAL, center, effectPlan.shards(), 0.2, 0.3, 0.2, 0.65);
				world.spawnParticle(Particle.SCULK_SOUL, center, 6, 0.25, 0.35, 0.25, 0.03);
				world.spawnParticle(Particle.FLASH, center, 1, 0.0, 0.0, 0.0, 0.0, Color.fromRGB(220, 235, 255));
				world.playSound(center, Sound.BLOCK_GLASS_BREAK, SoundCategory.BLOCKS, 1.8f, 0.8f);
				world.playSound(center, Sound.BLOCK_GLASS_BREAK, SoundCategory.BLOCKS, 1.1f, 1.05f);
				world.playSound(center, Sound.ENTITY_ITEM_BREAK, SoundCategory.BLOCKS, 1.2f, 0.8f);
				return;
			}

			double frac = (double) (t + 1) / (double) CLOSE_CRACK_TICKS;
			for(int i = 0; i < effectPlan.branches(); i++)
			{
				for(int segment = 1; segment <= effectPlan.segments(); segment++)
				{
					double segmentFraction = (double) segment / (double) effectPlan.segments();
					double angle = crackAngle[i] + (crackBend[i] * frac * frac * segmentFraction);
					double len = ellipseRadius(halfA, halfB, angle) * crackReach[i] * frac * segmentFraction;
					point[0] = cc[0];
					point[1] = cc[1];
					point[2] = cc[2];
					point[planeA] += len * Math.cos(angle);
					point[planeB] += len * Math.sin(angle);
					world.spawnParticle(Particle.DUST, point[0], point[1], point[2], 1, 0.0, 0.0, 0.0, 0.0, crackDust);
				}
			}
			for(int i = 0; i < effectPlan.branches(); i++)
			{
				if(t < branchletTick[i])
				{
					continue;
				}
				double baseFrac = (double) (branchletTick[i] + 1) / (double) CLOSE_CRACK_TICKS;
				double baseAngle = crackAngle[i] + (crackBend[i] * baseFrac * baseFrac * branchletStart[i]);
				double baseLen = ellipseRadius(halfA, halfB, baseAngle) * crackReach[i] * baseFrac * branchletStart[i];
				double growth = Math.min(1.0D, (double) (t - branchletTick[i] + 1) / 4.0D);
				for(int segment = 1; segment <= 2; segment++)
				{
					double offshoot = ellipseRadius(halfA, halfB, branchletAngle[i]) * branchletReach[i] * growth * ((double) segment / 2.0D);
					point[0] = cc[0];
					point[1] = cc[1];
					point[2] = cc[2];
					point[planeA] += (baseLen * Math.cos(baseAngle)) + (offshoot * Math.cos(branchletAngle[i]));
					point[planeB] += (baseLen * Math.sin(baseAngle)) + (offshoot * Math.sin(branchletAngle[i]));
					world.spawnParticle(Particle.DUST, point[0], point[1], point[2], 1, 0.0, 0.0, 0.0, 0.0, branchletDust);
				}
			}
			if(t == 0)
			{
				world.playSound(center, Sound.BLOCK_GLASS_BREAK, SoundCategory.BLOCKS, 0.4f, 1.7f);
			}
			if(t % 3 == 1)
			{
				world.playSound(center, Sound.BLOCK_AMETHYST_BLOCK_HIT, SoundCategory.BLOCKS, 0.5f, 0.7f);
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

	public void playPortalOpen(World world, Location center, double sx, double sy, double sz, BooleanSupplier active)
	{
		if(closing || world == null || center == null || active == null || !active.getAsBoolean())
		{
			return;
		}
		if(!Settings.ENABLE_PARTICLES)
		{
			world.playSound(center, MSound.FRAME_SPAWN.bukkitSound(), SoundCategory.BLOCKS, 1.4f, 0.35f);
			playKawooshSounds(world, center, active);
			return;
		}
		VortexMarker marker = findVortexMarker(world, center);
		if(marker != null)
		{
			if(marker.kawooshPlayed)
			{
				activeVortices.remove(marker.key, marker);
				return;
			}
			marker.kawoosh = new KawooshRequest(center.clone(), sx, sy, sz, active);
			return;
		}
		world.playSound(center, MSound.FRAME_SPAWN.bukkitSound(), SoundCategory.BLOCKS, 1.4f, 0.35f);
		playPortalOpenPrelude(world, center, sx, sy, sz, active);
	}

	public void playPortalDeletion(World world, Location corner, double sx, double sy, double sz)
	{
		playPortalClose(world, corner, sx, sy, sz, () -> true);
	}

	static int formationDisplayCap(VisualQualityProfile profile)
	{
		return switch(profile)
		{
			case PERFORMANCE -> 8;
			case BALANCED -> 16;
			case AUTO -> 18;
			case CINEMATIC -> 24;
		};
	}

	static CloseEffectPlan closeEffectPlan(VisualQualityProfile profile)
	{
		return switch(profile)
		{
			case PERFORMANCE -> new CloseEffectPlan(4, 3, 14);
			case BALANCED -> new CloseEffectPlan(6, 4, 22);
			case AUTO -> new CloseEffectPlan(6, 4, 26);
			case CINEMATIC -> new CloseEffectPlan(8, 5, 30);
		};
	}

	static KawooshPlan kawooshPlan(VisualQualityProfile profile)
	{
		return switch(profile)
		{
			case PERFORMANCE -> new KawooshPlan(2, 6, 20, 8, 2);
			case BALANCED -> new KawooshPlan(3, 9, 40, 18, 4);
			case AUTO -> new KawooshPlan(3, 11, 44, 20, 5);
			case CINEMATIC -> new KawooshPlan(3, 14, 48, 24, 6);
		};
	}

	static int openingRingPoints(VisualQualityProfile profile)
	{
		return switch(profile)
		{
			case PERFORMANCE -> 6;
			case BALANCED -> 10;
			case AUTO -> 12;
			case CINEMATIC -> 16;
		};
	}

	static double ellipseRadius(double halfA, double halfB, double angle)
	{
		double cos = Math.cos(angle);
		double sin = Math.sin(angle);
		return 1.0D / Math.sqrt((cos * cos) / (halfA * halfA) + (sin * sin) / (halfB * halfB));
	}

	static double[] outwardShardVelocity(int normalAxis, int planeA, int planeB, double radialA, double radialB, double normalDirection)
	{
		double[] velocity = new double[3];
		velocity[normalAxis] = normalDirection * 0.65D;
		velocity[planeA] = radialA;
		velocity[planeB] = radialB;
		return velocity;
	}

	private void playPortalOpenPrelude(World world, Location center, double sx, double sy, double sz, BooleanSupplier active)
	{
		double[] ext = new double[] { sx, sy, sz };
		final int normalAxis = (ext[0] <= ext[1] && ext[0] <= ext[2]) ? 0 : (ext[1] <= ext[2] ? 1 : 2);
		final int planeA = normalAxis == 0 ? 1 : 0;
		final int planeB = normalAxis == 2 ? 1 : 2;
		final double halfA = Math.max(0.6D, (ext[planeA] / 2.0D) + 0.35D);
		final double halfB = Math.max(0.6D, (ext[planeB] / 2.0D) + 0.35D);
		final double[] cc = new double[] { center.getX(), center.getY(), center.getZ() };
		final int streams = openingRingPoints(Settings.VISUAL_QUALITY_PROFILE);
		final double[] streamAngle = new double[streams];
		final double[] streamTurns = new double[streams];
		final double[] streamExponent = new double[streams];
		for(int i = 0; i < streams; i++)
		{
			streamAngle[i] = ((Math.PI * 2.0D * i) / streams) + ((Math.random() - 0.5D) * 0.8D);
			streamTurns[i] = 1.2D + (Math.random() * 1.0D);
			streamExponent[i] = 1.2D + (Math.random() * 0.5D);
		}
		final Particle.DustTransition streamDust = new Particle.DustTransition(Color.fromRGB(185, 105, 255), Color.fromRGB(20, 5, 35), 0.8f);
		int[] tick = new int[] { 0 };
		Runnable[] holder = new Runnable[1];
		holder[0] = () ->
		{
			if(closing || !active.getAsBoolean())
			{
				return;
			}
			int t = tick[0]++;
			if(t >= OPEN_PRELUDE_TICKS)
			{
				playPortalKawoosh(world, center, sx, sy, sz, active);
				return;
			}
			double frac = (double) (t + 1) / (double) OPEN_PRELUDE_TICKS;
			double[] point = new double[3];
			for(int i = 0; i < streams; i++)
			{
				double angle = streamAngle[i] + (frac * streamTurns[i] * Math.PI * 2.0D);
				double radius = ellipseRadius(halfA, halfB, angle) * Math.pow(1.0D - frac, streamExponent[i]);
				point[0] = cc[0];
				point[1] = cc[1];
				point[2] = cc[2];
				point[planeA] += radius * Math.cos(angle);
				point[planeB] += radius * Math.sin(angle);
				Particle stream = (i & 1) == 0 ? Particle.PORTAL : Particle.REVERSE_PORTAL;
				world.spawnParticle(stream, point[0], point[1], point[2], 1, 0.03, 0.03, 0.03, 0.02);
				if((t & 1) == 0)
				{
					world.spawnParticle(Particle.DUST_COLOR_TRANSITION, point[0], point[1], point[2], 1, 0.0, 0.0, 0.0, 0.0, streamDust);
				}
			}
			FoliaScheduler.runRegion(Wormholes.instance, center, holder[0], 1L);
		};
		FoliaScheduler.runRegion(Wormholes.instance, center, holder[0], 1L);
	}

	private void playPortalKawoosh(World world, Location center, double sx, double sy, double sz, BooleanSupplier active)
	{
		if(closing || world == null || center == null || active == null || !active.getAsBoolean())
		{
			return;
		}
		double[] ext = new double[] { sx, sy, sz };
		final int normalAxis = (ext[0] <= ext[1] && ext[0] <= ext[2]) ? 0 : (ext[1] <= ext[2] ? 1 : 2);
		final int planeA = normalAxis == 0 ? 1 : 0;
		final int planeB = normalAxis == 2 ? 1 : 2;
		final double halfA = Math.max(0.6D, (ext[planeA] / 2.0D) + 0.35D);
		final double halfB = Math.max(0.6D, (ext[planeB] / 2.0D) + 0.35D);
		final double[] cc = new double[] { center.getX(), center.getY(), center.getZ() };
		final KawooshPlan plan = kawooshPlan(Settings.VISUAL_QUALITY_PROFILE);
		world.spawnParticle(Particle.FLASH, center, 1, 0.0, 0.0, 0.0, 0.0, Color.fromRGB(190, 130, 255));
		world.spawnParticle(Particle.REVERSE_PORTAL, center, plan.impactReverse(), 0.25, 0.45, 0.25, 0.75);
		world.spawnParticle(Particle.END_ROD, center, plan.impactEndRod(), 0.15, 0.15, 0.15, 0.3);
		playKawooshSounds(world, center, active);
		final Particle.DustTransition armDust = new Particle.DustTransition(Color.fromRGB(185, 105, 255), Color.fromRGB(20, 5, 35), 0.9f);
		int[] tick = new int[] { 0 };
		Runnable[] holder = new Runnable[1];
		holder[0] = () ->
		{
			if(closing || !active.getAsBoolean())
			{
				return;
			}
			int t = tick[0]++;
			if(t >= KAWOOSH_TICKS)
			{
				return;
			}
			double frac = (double) (t + 1) / (double) KAWOOSH_TICKS;
			int armPoints = Math.max(3, (int) Math.round(plan.armPoints() * frac));
			double[] point = new double[3];
			for(int arm = 0; arm < plan.arms(); arm++)
			{
				double armOffset = (Math.PI * 2.0D * arm) / plan.arms();
				for(int j = 1; j <= armPoints; j++)
				{
					double along = frac * ((double) j / (double) armPoints);
					double angle = armOffset + (along * KAWOOSH_TWIST_RADIANS);
					double radius = ellipseRadius(halfA, halfB, angle) * along;
					point[0] = cc[0];
					point[1] = cc[1];
					point[2] = cc[2];
					point[planeA] += radius * Math.cos(angle);
					point[planeB] += radius * Math.sin(angle);
					if(j == armPoints)
					{
						world.spawnParticle(Particle.END_ROD, point[0], point[1], point[2], 1, 0.0, 0.0, 0.0, 0.02);
					}
					else
					{
						world.spawnParticle(Particle.DUST_COLOR_TRANSITION, point[0], point[1], point[2], 1, 0.0, 0.0, 0.0, 0.0, armDust);
					}
				}
			}
			if(t < KAWOOSH_SURGE_TICKS)
			{
				double surgeSpeed = 0.6D - (t * 0.07D);
				for(int i = 0; i < plan.surgeCount(); i++)
				{
					double[] direction = new double[3];
					direction[normalAxis] = (i & 1) == 0 ? 1.0D : -1.0D;
					direction[planeA] = (Math.random() - 0.5D) * 0.45D;
					direction[planeB] = (Math.random() - 0.5D) * 0.45D;
					world.spawnParticle(Particle.END_ROD, cc[0], cc[1], cc[2], 0, direction[0], direction[1], direction[2], surgeSpeed);
				}
			}
			else
			{
				double settleSpread = Math.max(0.15D, 0.9D * (1.0D - frac));
				world.spawnParticle(Particle.REVERSE_PORTAL, cc[0], cc[1], cc[2], 6, settleSpread, settleSpread, settleSpread, 0.2);
				world.spawnParticle(Particle.ENCHANT, cc[0], cc[1], cc[2], 3, settleSpread, settleSpread, settleSpread, 0.05);
			}
			FoliaScheduler.runRegion(Wormholes.instance, center, holder[0], 1L);
		};
		FoliaScheduler.runRegion(Wormholes.instance, center, holder[0], 1L);
	}

	private void playKawooshSounds(World world, Location center, BooleanSupplier active)
	{
		world.playSound(center, Sound.BLOCK_END_PORTAL_SPAWN, SoundCategory.BLOCKS, 1.8f, 0.85f);
		world.playSound(center, Sound.BLOCK_BEACON_ACTIVATE, SoundCategory.BLOCKS, 2.0f, 0.55f);
		FoliaScheduler.runRegion(Wormholes.instance, center, () ->
		{
			if(!closing && active.getAsBoolean())
			{
				world.playSound(center, Sound.ENTITY_WARDEN_SONIC_BOOM, SoundCategory.BLOCKS, 1.0f, 0.8f);
			}
		}, KAWOOSH_BOOM_DELAY_TICKS);
	}

	private void playVortexConvergence(World world, Location center, VortexMarker marker, double sx, double sy, double sz)
	{
		if(closing)
		{
			return;
		}
		marker.kawooshPlayed = true;
		KawooshRequest request = marker.kawoosh;
		if(request != null && request.active().getAsBoolean())
		{
			playPortalKawoosh(world, request.center(), request.sx(), request.sy(), request.sz(), request.active());
			return;
		}
		playPortalKawoosh(world, center, sx, sy, sz, () -> true);
	}

	private VortexMarker findVortexMarker(World world, Location center)
	{
		long now = System.currentTimeMillis();
		for(Map.Entry<VortexKey, VortexMarker> entry : activeVortices.entrySet())
		{
			VortexMarker marker = entry.getValue();
			if(now >= marker.expiresAtMillis)
			{
				activeVortices.remove(entry.getKey(), marker);
				continue;
			}
			if(vortexMarkerMatches(marker.world.getUID(), marker.x, marker.y, marker.z, marker.expiresAtMillis, now, world.getUID(), center.getX(), center.getY(), center.getZ()))
			{
				return marker;
			}
		}
		return null;
	}

	static boolean vortexMarkerMatches(UUID markerWorldId, double markerX, double markerY, double markerZ, long expiresAtMillis, long nowMillis, UUID worldId, double x, double y, double z)
	{
		if(nowMillis >= expiresAtMillis)
		{
			return false;
		}
		if(!markerWorldId.equals(worldId))
		{
			return false;
		}
		double dx = markerX - x;
		double dy = markerY - y;
		double dz = markerZ - z;
		return (dx * dx) + (dy * dy) + (dz * dz) <= VORTEX_MATCH_RADIUS_SQUARED;
	}

	private static VortexKey vortexKey(World world, Location center)
	{
		long x = center.getBlockX() & 0x3FFFFFFL;
		long y = center.getBlockY() & 0xFFFL;
		long z = center.getBlockZ() & 0x3FFFFFFL;
		return new VortexKey(world.getUID(), (y << 52) | (z << 26) | x);
	}

	private void removeVortex(List<VortexBlock> vortex)
	{
		for(VortexBlock block : vortex)
		{
			removeTemporaryDisplay(block.display());
		}
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

	private static Transformation centeredPaneTransform(int normalAxis, int planeA, int planeB, float thickness, float scaleA, float scaleB)
	{
		float[] scale = new float[3];
		scale[normalAxis] = thickness;
		scale[planeA] = scaleA;
		scale[planeB] = scaleB;
		return new Transformation(new Vector3f(-scale[0] / 2.0f, -scale[1] / 2.0f, -scale[2] / 2.0f), new Quaternionf(), new Vector3f(scale[0], scale[1], scale[2]), new Quaternionf());
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

	public record PortalBlockSnapshot(Location location, BlockData data)
	{
	}

	record CloseEffectPlan(int branches, int segments, int shards)
	{
	}

	record KawooshPlan(int arms, int armPoints, int impactReverse, int impactEndRod, int surgeCount)
	{
	}

	private record KawooshRequest(Location center, double sx, double sy, double sz, BooleanSupplier active)
	{
	}

	private record VortexBlock(BlockDisplay display, double angle0, double radius0, double normalOffset0, double turns, double radialExponent, double shrinkExponent, double wobblePhase, double wobbleAmplitude)
	{
	}

	private record VortexKey(UUID worldId, long packedPos)
	{
	}

	private static final class VortexMarker
	{
		private final VortexKey key;
		private final World world;
		private final double x;
		private final double y;
		private final double z;
		private final long expiresAtMillis;
		private volatile KawooshRequest kawoosh;
		private volatile boolean kawooshPlayed;

		private VortexMarker(VortexKey key, World world, double x, double y, double z, long expiresAtMillis)
		{
			this.key = key;
			this.world = world;
			this.x = x;
			this.y = y;
			this.z = z;
			this.expiresAtMillis = expiresAtMillis;
		}
	}
}
