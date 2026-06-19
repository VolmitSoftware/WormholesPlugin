package art.arcane.wormholes;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

import org.bukkit.Bukkit;
import org.bukkit.Color;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.Particle;
import org.bukkit.Sound;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.data.BlockData;
import org.bukkit.entity.BlockDisplay;
import org.bukkit.entity.Display;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.player.PlayerInteractEvent;
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
import art.arcane.wormholes.service.WormholesAudience;
import art.arcane.volmlib.util.scheduling.AR;
import art.arcane.volmlib.util.scheduling.FoliaScheduler;
import art.arcane.wormholes.util.Area;
import art.arcane.volmlib.util.collection.KList;
import art.arcane.wormholes.util.MSound;
import art.arcane.wormholes.util.ParticleEffect;

public class EffectManager implements Listener
{
	private static final int SYNC_SWEEP_INTERVAL_TICKS = 15;
	private static final double SYNC_AUDIENCE_RANGE = 48.0;
	private final Map<UUID, Boolean> portalSyncActive = new ConcurrentHashMap<>();

	public EffectManager()
	{
		Wormholes.v("Starting Effect Manager");

		new AR()
		{
			@Override
			public void run()
			{
				for(Player player : Bukkit.getOnlinePlayers())
				{
					FoliaScheduler.runEntity(Wormholes.instance, player, () -> scanLookingPortalsFor(player));
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

	private void sweepRemoteSync()
	{
		RemoteViewCache cache = Wormholes.remoteViewCache;
		if(cache == null || Wormholes.portalManager == null || Wormholes.instance == null)
		{
			return;
		}

		for(ILocalPortal portal : Wormholes.portalManager.getLocalPortals())
		{
			if(portal == null || portal.getId() == null || !portal.isOpen() || !portal.hasTunnel())
			{
				continue;
			}
			ITunnel tunnel = portal.getTunnel();
			if(!(tunnel instanceof UniversalTunnel))
			{
				continue;
			}
			IPortal destination = tunnel.getDestination();
			if(!(destination instanceof RemotePortal remote) || remote.getId() == null)
			{
				continue;
			}
			Location center = portal.getCenter();
			if(center == null || center.getWorld() == null)
			{
				continue;
			}

			UUID portalKey = portal.getId();
			if(!hasNearbyPlayer(center, SYNC_AUDIENCE_RANGE))
			{
				portalSyncActive.remove(portalKey);
				continue;
			}

			boolean ready = cache.isViewReady(remote.getId());
			boolean syncing = !ready && cache.hasSlicesFor(remote.getId());
			if(syncing)
			{
				portalSyncActive.put(portalKey, Boolean.TRUE);
				FoliaScheduler.runRegion(Wormholes.instance, center, () -> playPortalSyncing(center));
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
	}

	private boolean hasNearbyPlayer(Location center, double range)
	{
		World world = center.getWorld();
		double rangeSquared = range * range;
		for(Player player : Bukkit.getOnlinePlayers())
		{
			if(!world.equals(player.getWorld()))
			{
				continue;
			}
			if(player.getLocation().distanceSquared(center) <= rangeSquared)
			{
				return true;
			}
		}
		return false;
	}

	public void playPortalSyncing(Location center)
	{
		World world = center.getWorld();
		if(world == null)
		{
			return;
		}
		world.spawnParticle(Particle.PORTAL, center, 18, 0.6, 0.8, 0.6, 0.35);
		world.spawnParticle(Particle.REVERSE_PORTAL, center, 6, 0.3, 0.5, 0.3, 0.02);
		world.playSound(center, Sound.BLOCK_BEACON_AMBIENT, 0.45f, 1.7f + ((float) (Math.random() * 0.1)));
	}

	public void playPortalSyncComplete(Location center)
	{
		World world = center.getWorld();
		if(world == null)
		{
			return;
		}
		world.spawnParticle(Particle.REVERSE_PORTAL, center, 44, 0.5, 0.7, 0.5, 0.5);
		world.playSound(center, Sound.BLOCK_BEACON_ACTIVATE, 0.7f, 1.5f);
		world.playSound(center, Sound.BLOCK_PORTAL_TRIGGER, 0.5f, 1.8f);
	}

	private void scanLookingPortalsFor(Player player)
	{
		ItemStack handItem = player.getInventory().getItemInMainHand();
		boolean holdingPortalTool = Wormholes.blockManager.isPortalTool(handItem);

		for(ILocalPortal portal : Wormholes.portalManager.getLocalPortals())
		{
			if(portal.isLookingAt(player))
			{
				Location portalCenter = portal.getCenter();
				if(portalCenter == null)
				{
					continue;
				}

				FoliaScheduler.runRegion(Wormholes.instance, portalCenter, () -> portal.onLooking(player, holdingPortalTool));
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
		if(!Wormholes.blockManager.isSame(handItem, Wormholes.blockManager.getWand()))
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
		block.getWorld().playSound(block.getLocation().clone().add(0.5, 0.5, 0.5), MSound.FRAME_FILL.bukkitSound(), 1.2f, 1.1f + ((float) (Math.random() * 0.2)));
	}

	public void playPortalBlockDestroyed(Block block)
	{
		block.getWorld().playSound(block.getLocation().clone().add(0.5, 0.5, 0.5), MSound.EYE_DEATH.bukkitSound(), 0.7f, 1.46f + ((float) (Math.random() * 0.2)));
		block.getWorld().playSound(block.getLocation().clone().add(0.5, 0.5, 0.5), MSound.GLASS.bukkitSound(), 0.7f, 1.55f + ((float) (Math.random() * 0.2)));
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

	private static final int FORMATION_DISPLAY_CAP = 80;
	private static final int VORTEX_STEPS = 20;
	private static final double VORTEX_TURNS = 1.5D;

	public record PortalBlockSnapshot(Location location, BlockData data)
	{
	}

	private static final class VortexBlock
	{
		private final BlockDisplay display;
		private final double cornerX;
		private final double cornerY;
		private final double cornerZ;
		private final double angle0;
		private final double radius0;

		private VortexBlock(BlockDisplay display, double cornerX, double cornerY, double cornerZ, double angle0, double radius0)
		{
			this.display = display;
			this.cornerX = cornerX;
			this.cornerY = cornerY;
			this.cornerZ = cornerZ;
			this.angle0 = angle0;
			this.radius0 = radius0;
		}
	}

	public void playPortalVortex(World world, Location center, List<PortalBlockSnapshot> snapshots)
	{
		if(world == null || center == null || snapshots == null || snapshots.isEmpty())
		{
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

		List<VortexBlock> vortex = new ArrayList<VortexBlock>();
		int spawned = 0;
		for(PortalBlockSnapshot snapshot : snapshots)
		{
			if(spawned >= FORMATION_DISPLAY_CAP)
			{
				break;
			}
			Location loc = snapshot.location();
			double cornerX = Math.floor(loc.getX());
			double cornerY = Math.floor(loc.getY());
			double cornerZ = Math.floor(loc.getZ());
			Location corner = new Location(world, cornerX, cornerY, cornerZ);
			BlockData data = snapshot.data();
			BlockDisplay display = world.spawn(corner, BlockDisplay.class, e ->
			{
				e.setBlock(data);
				e.setBrightness(new Display.Brightness(15, 15));
				e.setPersistent(false);
				e.setViewRange(2.5f);
			});
			double[] blockCenter = new double[] { cornerX + 0.5D, cornerY + 0.5D, cornerZ + 0.5D };
			double a0 = blockCenter[planeA] - c[planeA];
			double b0 = blockCenter[planeB] - c[planeB];
			vortex.add(new VortexBlock(display, cornerX, cornerY, cornerZ, Math.atan2(b0, a0), Math.hypot(a0, b0)));
			spawned++;
		}

		world.spawnParticle(Particle.PORTAL, center, 80, 1.0, 1.2, 1.0, 0.8);
		world.playSound(center, Sound.BLOCK_BEACON_POWER_SELECT, 1.6f, 0.5f);
		world.playSound(center, Sound.BLOCK_PORTAL_TRIGGER, 0.8f, 1.4f);

		int[] tick = new int[] { 0 };
		Runnable[] holder = new Runnable[1];
		holder[0] = () ->
		{
			int t = tick[0]++;
			if(t > VORTEX_STEPS)
			{
				for(VortexBlock vb : vortex)
				{
					if(vb.display.isValid())
					{
						vb.display.remove();
					}
				}
				world.spawnParticle(Particle.REVERSE_PORTAL, center, 60, 0.1, 0.2, 0.1, 0.6);
				return;
			}

			double frac = (double) t / (double) VORTEX_STEPS;
			double spin = frac * VORTEX_TURNS * Math.PI * 2.0D;
			double radiusFactor = Math.pow(1.0D - frac, 1.4D);
			float scale = (float) Math.max(0.04D, 1.0D - frac);
			double half = 0.5D * scale;
			for(VortexBlock vb : vortex)
			{
				if(!vb.display.isValid())
				{
					continue;
				}
				double angle = vb.angle0 + spin;
				double r = vb.radius0 * radiusFactor;
				double[] worldPos = new double[] { c[0], c[1], c[2] };
				worldPos[planeA] = c[planeA] + (r * Math.cos(angle));
				worldPos[planeB] = c[planeB] + (r * Math.sin(angle));
				float tiltAngle = (float) (0.35D * Math.sin((spin * 2.0D) + vb.angle0));
				Quaternionf tilt = axisRotation(normalAxis, tiltAngle);
				Vector3f pivot = tilt.transform(new Vector3f((float) half, (float) half, (float) half));
				Vector3f translation = new Vector3f((float) (worldPos[0] - vb.cornerX) - pivot.x, (float) (worldPos[1] - vb.cornerY) - pivot.y, (float) (worldPos[2] - vb.cornerZ) - pivot.z);
				Transformation target = new Transformation(translation, tilt, new Vector3f(scale, scale, scale), new Quaternionf());
				vb.display.setInterpolationDelay(0);
				vb.display.setInterpolationDuration(2);
				vb.display.setTransformation(target);
			}

			if((t & 1) == 0)
			{
				world.spawnParticle(Particle.PORTAL, center, 10, 0.5, 0.6, 0.5, 0.6);
			}
			FoliaScheduler.runRegion(Wormholes.instance, center, holder[0], 1L);
		};
		FoliaScheduler.runRegion(Wormholes.instance, center, holder[0], 1L);
	}


	private static final int CLOSE_CRACK_TICKS = 9;
	private static final int CLOSE_CRACK_BRANCHES = 6;

	public void playPortalClose(World world, Location corner, double sx, double sy, double sz)
	{
		if(world == null || corner == null)
		{
			return;
		}

		double[] ext = new double[] { sx, sy, sz };
		final int normalAxis = (ext[0] <= ext[1] && ext[0] <= ext[2]) ? 0 : (ext[1] <= ext[2] ? 1 : 2);
		final int planeA = normalAxis == 0 ? 1 : 0;
		final int planeB = normalAxis == 2 ? 1 : 2;
		final double[] cc = new double[] { corner.getX() + (sx / 2.0D), corner.getY() + (sy / 2.0D), corner.getZ() + (sz / 2.0D) };
		Location center = new Location(world, cc[0], cc[1], cc[2]);
		final double maxR = Math.max(0.6D, Math.max(ext[planeA], ext[planeB]) / 2.0D);

		float thickness = 0.2f;
		float[] scale = new float[3];
		scale[normalAxis] = thickness;
		scale[planeA] = (float) Math.max(0.25D, ext[planeA]);
		scale[planeB] = (float) Math.max(0.25D, ext[planeB]);
		float[] tr = new float[3];
		tr[normalAxis] = 0.5f - (thickness / 2f);
		tr[planeA] = (float) ((ext[planeA] / 2.0D) - (scale[planeA] / 2.0D));
		tr[planeB] = (float) ((ext[planeB] / 2.0D) - (scale[planeB] / 2.0D));
		BlockData glass = Material.TINTED_GLASS.createBlockData();
		BlockDisplay pane = world.spawn(corner, BlockDisplay.class, e ->
		{
			e.setBlock(glass);
			e.setBrightness(new Display.Brightness(10, 15));
			e.setPersistent(false);
			e.setViewRange(2.5f);
			e.setTransformation(new Transformation(new Vector3f(tr[0], tr[1], tr[2]), new Quaternionf(), new Vector3f(scale[0], scale[1], scale[2]), new Quaternionf()));
		});

		double[] crackAngle = new double[CLOSE_CRACK_BRANCHES];
		double[] crackLen = new double[CLOSE_CRACK_BRANCHES];
		for(int i = 0; i < CLOSE_CRACK_BRANCHES; i++)
		{
			crackAngle[i] = Math.random() * Math.PI * 2.0D;
			crackLen[i] = maxR * (0.6D + (Math.random() * 0.4D));
		}

		Particle.DustOptions crackDust = new Particle.DustOptions(Color.fromRGB(235, 245, 255), 0.7f);
		BlockData shardData = Material.GLASS.createBlockData();

		int[] tick = new int[] { 0 };
		Runnable[] holder = new Runnable[1];
		holder[0] = () ->
		{
			int t = tick[0]++;
			if(t >= CLOSE_CRACK_TICKS)
			{
				if(pane.isValid())
				{
					pane.remove();
				}
				for(int i = 0; i < 60; i++)
				{
					double a = Math.random() * Math.PI * 2.0D;
					double rr = Math.random() * maxR;
					double[] p = new double[] { cc[0], cc[1], cc[2] };
					p[planeA] = cc[planeA] + (rr * Math.cos(a));
					p[planeB] = cc[planeB] + (rr * Math.sin(a));
					world.spawnParticle(Particle.BLOCK, new Location(world, p[0], p[1], p[2]), 2, 0.05, 0.05, 0.05, 0.0, shardData);
				}
				world.spawnParticle(Particle.FLASH, center, 1, 0.0, 0.0, 0.0, 0.0, Color.fromRGB(220, 235, 255));
				world.playSound(center, Sound.BLOCK_GLASS_BREAK, 2.0f, 0.7f);
				world.playSound(center, Sound.BLOCK_GLASS_BREAK, 1.4f, 1.1f);
				world.playSound(center, Sound.ENTITY_ITEM_BREAK, 1.2f, 0.8f);
				return;
			}

			double frac = (double) (t + 1) / (double) CLOSE_CRACK_TICKS;
			for(int i = 0; i < CLOSE_CRACK_BRANCHES; i++)
			{
				double len = crackLen[i] * frac;
				for(int s = 1; s <= 4; s++)
				{
					double rr = len * ((double) s / 4.0D);
					double[] p = new double[] { cc[0], cc[1], cc[2] };
					p[planeA] = cc[planeA] + (rr * Math.cos(crackAngle[i]));
					p[planeB] = cc[planeB] + (rr * Math.sin(crackAngle[i]));
					world.spawnParticle(Particle.DUST, new Location(world, p[0], p[1], p[2]), 1, 0.0, 0.0, 0.0, 0.0, crackDust);
				}
			}
			if(t == 0)
			{
				world.playSound(center, Sound.BLOCK_GLASS_BREAK, 0.6f, 1.6f);
			}
			if((t & 1) == 0)
			{
				world.playSound(center, Sound.BLOCK_AMETHYST_BLOCK_HIT, 0.5f, 0.7f);
			}
			FoliaScheduler.runRegion(Wormholes.instance, center, holder[0], 1L);
		};
		FoliaScheduler.runRegion(Wormholes.instance, center, holder[0], 1L);
	}

	public void playPortalOpenClimax(World world, Location center, double sx, double sy, double sz)
	{
		world.spawnParticle(Particle.FLASH, center, 1, 0.0, 0.0, 0.0, 0.0, Color.fromRGB(190, 130, 255));
		world.spawnParticle(Particle.REVERSE_PORTAL, center, 140, 0.25, 0.45, 0.25, 0.9);
		world.spawnParticle(Particle.END_ROD, center, 55, 0.15, 0.15, 0.15, 0.35);
		for(int i = 0; i < 16; i++)
		{
			Location spark = center.clone().add((Math.random() - 0.5) * 2.0, (Math.random() - 0.5) * 2.0, (Math.random() - 0.5) * 2.0);
			ParticleEffect.REDSTONE.display(new ParticleEffect.OrdinaryColor(150, 80, 255), spark, 32);
		}
		world.playSound(center, Sound.BLOCK_BEACON_ACTIVATE, 3.0f, 0.5f);
		world.playSound(center, Sound.BLOCK_END_PORTAL_SPAWN, 2.6f, 0.8f);
		FoliaScheduler.runRegion(Wormholes.instance, center, () -> world.playSound(center, Sound.ENTITY_WARDEN_SONIC_BOOM, 1.5f, 0.7f), 6L);
		playPortalRipple(world, center, sx, sy, sz);
	}

	private void playPortalRipple(World world, Location center, double sx, double sy, double sz)
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
		final int ripTicks = 12;
		final int[] rt = new int[] { 0 };
		final Runnable[] holder = new Runnable[1];
		holder[0] = () ->
		{
			int t = rt[0]++;
			if(t >= ripTicks)
			{
				return;
			}
			double frac = (double) (t + 1) / (double) ripTicks;
			double radius = maxR * frac;
			int points = Math.max(10, (int) (radius * 9.0D));
			double twist = frac * Math.PI * 1.25D;
			for(int i = 0; i < points; i++)
			{
				double a = ((Math.PI * 2.0D * i) / points) + twist;
				double[] p = new double[] { cc[0], cc[1], cc[2] };
				p[planeA] = cc[planeA] + (radius * Math.cos(a));
				p[planeB] = cc[planeB] + (radius * Math.sin(a));
				world.spawnParticle(Particle.END_ROD, new Location(world, p[0], p[1], p[2]), 1, 0.0, 0.0, 0.0, 0.0);
			}
			FoliaScheduler.runRegion(Wormholes.instance, center, holder[0], 1L);
		};
		FoliaScheduler.runRegion(Wormholes.instance, center, holder[0], 1L);
	}

	private static final int DELETION_STATIC_TICKS = 6;

	public void playPortalDeletion(World world, Location corner, double sx, double sy, double sz)
	{
		if(world == null || corner == null)
		{
			return;
		}

		double[] ext = new double[] { Math.max(0.25D, sx), Math.max(0.25D, sy), Math.max(0.25D, sz) };
		int normalAxis = 1;
		double min = ext[1];
		if(ext[0] <= min)
		{
			min = ext[0];
			normalAxis = 0;
		}
		if(ext[2] < min)
		{
			normalAxis = 2;
		}
		final int fNormalAxis = normalAxis;
		final int planeA = normalAxis == 0 ? 1 : 0;
		final int planeB = normalAxis == 2 ? 1 : 2;
		final int upAxis = planeA == 1 ? planeA : (planeB == 1 ? planeB : planeA);
		final int horizAxis = upAxis == planeA ? planeB : planeA;
		final float thickness = 0.2f;
		BlockData panel = Material.TINTED_GLASS.createBlockData();
		Location center = corner.clone().add(sx / 2.0D, sy / 2.0D, sz / 2.0D);

		BlockDisplay display = world.spawn(corner, BlockDisplay.class, e ->
		{
			e.setBlock(panel);
			e.setBrightness(new Display.Brightness(15, 15));
			e.setPersistent(false);
			e.setViewRange(2.5f);
			e.setTransformation(crtTransform(fNormalAxis, upAxis, horizAxis, ext, thickness, (float) ext[upAxis], (float) ext[horizAxis]));
		});
		world.playSound(center, Sound.BLOCK_BEACON_DEACTIVATE, 2.0f, 1.2f);
		double scanSpan = ext[horizAxis];

		int[] staticTick = new int[] { 0 };
		Runnable[] staticHolder = new Runnable[1];
		staticHolder[0] = () ->
		{
			if(staticTick[0]++ >= DELETION_STATIC_TICKS)
			{
				return;
			}
			for(int i = 0; i < 5; i++)
			{
				Location p = corner.clone().add(Math.random() * sx, Math.random() * sy, Math.random() * sz);
				world.spawnParticle(Particle.END_ROD, p, 1, 0.0, 0.0, 0.0, 0.0);
			}
			world.playSound(center, Sound.BLOCK_FIRE_EXTINGUISH, 0.5f, 2.0f);
			FoliaScheduler.runRegion(Wormholes.instance, center, staticHolder[0], 1L);
		};
		FoliaScheduler.runRegion(Wormholes.instance, center, staticHolder[0], 1L);

		FoliaScheduler.runRegion(Wormholes.instance, center, () ->
		{
			if(!display.isValid())
			{
				return;
			}
			display.setInterpolationDelay(0);
			display.setInterpolationDuration(4);
			display.setTransformation(crtTransform(fNormalAxis, upAxis, horizAxis, ext, thickness, 0.06f, (float) ext[horizAxis]));
			world.playSound(center, Sound.BLOCK_BEACON_DEACTIVATE, 1.6f, 0.9f);
		}, DELETION_STATIC_TICKS + 1L);

		FoliaScheduler.runRegion(Wormholes.instance, center, () ->
		{
			for(int i = 0; i <= 8; i++)
			{
				double off = ((i / 8.0D) - 0.5D) * scanSpan;
				double[] p = new double[] { center.getX(), center.getY(), center.getZ() };
				p[horizAxis] += off;
				world.spawnParticle(Particle.END_ROD, new Location(world, p[0], p[1], p[2]), 1, 0.0, 0.0, 0.0, 0.0);
			}
		}, DELETION_STATIC_TICKS + 5L);

		long slamAt = DELETION_STATIC_TICKS + 6L;
		FoliaScheduler.runRegion(Wormholes.instance, center, () ->
		{
			if(!display.isValid())
			{
				return;
			}
			display.setInterpolationDelay(0);
			display.setInterpolationDuration(2);
			display.setTransformation(crtTransform(fNormalAxis, upAxis, horizAxis, ext, thickness, 0.06f, 0.12f));
			world.playSound(center, MSound.GLASS.bukkitSound(), 1.6f, 0.6f);
			world.playSound(center, Sound.ENTITY_ENDERMAN_TELEPORT, 1.4f, 0.6f);
			world.playSound(center, Sound.BLOCK_BEACON_DEACTIVATE, 2.0f, 0.4f);
		}, slamAt);

		FoliaScheduler.runRegion(Wormholes.instance, center, () ->
		{
			if(!display.isValid())
			{
				return;
			}
			display.setInterpolationDelay(0);
			display.setInterpolationDuration(3);
			display.setTransformation(crtTransform(fNormalAxis, upAxis, horizAxis, ext, thickness, 0.02f, 0.02f));
		}, slamAt + 5L);

		FoliaScheduler.runRegion(Wormholes.instance, center, () ->
		{
			if(display.isValid())
			{
				display.remove();
			}
			world.spawnParticle(Particle.FLASH, center, 1, 0.0, 0.0, 0.0, 0.0, Color.fromRGB(230, 230, 255));
			world.spawnParticle(Particle.END_ROD, center, 10, 0.05, 0.05, 0.05, 0.3);
		}, slamAt + 9L);
	}

	private static Transformation crtTransform(int normalAxis, int upAxis, int horizAxis, double[] ext, float thickness, float sUp, float sHoriz)
	{
		float[] scale = new float[3];
		scale[normalAxis] = thickness;
		scale[upAxis] = sUp;
		scale[horizAxis] = sHoriz;
		float[] tr = new float[3];
		tr[normalAxis] = (float) ((ext[normalAxis] - thickness) / 2.0D);
		tr[upAxis] = (float) ((ext[upAxis] - sUp) / 2.0D);
		tr[horizAxis] = (float) ((ext[horizAxis] - sHoriz) / 2.0D);
		return new Transformation(new Vector3f(tr[0], tr[1], tr[2]), new Quaternionf(), new Vector3f(scale[0], scale[1], scale[2]), new Quaternionf());
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
