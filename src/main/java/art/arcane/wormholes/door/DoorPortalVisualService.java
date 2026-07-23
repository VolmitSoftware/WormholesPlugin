package art.arcane.wormholes.door;

import art.arcane.volmlib.util.bukkit.WorldIdentity;
import art.arcane.volmlib.util.scheduling.FoliaScheduler;
import art.arcane.wormholes.Settings;
import art.arcane.wormholes.platform.WormholesPlatform;
import org.bukkit.Axis;
import org.bukkit.Chunk;
import org.bukkit.Color;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.Particle;
import org.bukkit.Sound;
import org.bukkit.SoundCategory;
import org.bukkit.World;
import org.bukkit.block.BlockFace;
import org.bukkit.block.data.BlockData;
import org.bukkit.block.data.Orientable;
import org.bukkit.block.data.type.Door;
import org.bukkit.entity.BlockDisplay;
import org.bukkit.entity.Display;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.Plugin;
import org.bukkit.util.Transformation;
import org.joml.Quaternionf;
import org.joml.Vector3f;

import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.atomic.AtomicBoolean;

/** Owns the bright portal plane shown inside an open dimensional door. */
final class DoorPortalVisualService implements AutoCloseable
{
	static final Material PORTAL_MATERIAL = Material.CRYING_OBSIDIAN;
	static final Material PORTAL_OVERLAY_MATERIAL = Material.NETHER_PORTAL;
	private static final float PORTAL_INSET = 0.0625F;
	private static final float PORTAL_RECESS = (float) DoorwayPlane.PORTAL_RECESS;
	private static final float PORTAL_WIDTH = 1.0F - PORTAL_INSET;
	private static final float PORTAL_HEIGHT = 2.0F - (PORTAL_INSET * 2.0F);
	private static final float PORTAL_THICKNESS = (float) DoorwayPlane.PORTAL_THICKNESS;
	private static final float PORTAL_OVERLAY_THICKNESS = 0.15F;
	private static final int SPARKLE_PERIOD_TICKS = 16;
	private static final double AMBIENT_SOUND_CHANCE = 0.008D;
	private static final Particle.DustTransition SURFACE_DUST =
		new Particle.DustTransition(Color.fromRGB(185, 105, 255), Color.fromRGB(20, 5, 35), 0.7F);

	private final Plugin plugin;
	private final NamespacedKey markerKey;
	private final ConcurrentHashMap<UUID, Visual> visuals;
	private final Set<ChunkMarker> cleanedChunks;
	private final AtomicBoolean closed;

	DoorPortalVisualService(Plugin plugin)
	{
		this.plugin = Objects.requireNonNull(plugin, "plugin");
		markerKey = new NamespacedKey(plugin, "dimensional_door_visual");
		visuals = new ConcurrentHashMap<>();
		cleanedChunks = ConcurrentHashMap.newKeySet();
		closed = new AtomicBoolean();
	}

	void show(PlacedDoorEndpoint endpoint, VanillaDoorSnapshot snapshot)
	{
		Objects.requireNonNull(endpoint, "endpoint");
		Objects.requireNonNull(snapshot, "snapshot");
		if(closed.get())
		{
			return;
		}
		UUID doorId = endpoint.identity().itemId();
		Visual current = visuals.get(doorId);
		if(current != null && current.isValid())
		{
			return;
		}
		if(current != null && visuals.remove(doorId, current))
		{
			current.remove();
		}

		DoorwayPlane plane = snapshot.plane();
		World world = world(endpoint);
		if(world == null)
		{
			return;
		}
		Location anchor = new Location(world, plane.blockX() + 0.5D, plane.blockY(), plane.blockZ() + 0.5D);
		PortalPlaneGeometry geometry = geometry(plane.facing(), snapshot.hinge());
		if(closed.get())
		{
			return;
		}
		BlockDisplay backing = spawnBacking(world, anchor, doorId, geometry);
		if(closed.get())
		{
			remove(backing);
			return;
		}
		PortalPlaneGeometry overlayGeometry = overlayGeometry(geometry, plane.facing());
		BlockDisplay overlay;
		try
		{
			overlay = spawnOverlay(
				world,
				anchor,
				doorId,
				plane.facing(),
				overlayGeometry);
		}
		catch(RuntimeException | Error failure)
		{
			remove(backing);
			throw failure;
		}
		if(closed.get())
		{
			remove(backing);
			remove(overlay);
			return;
		}
		Visual replacement = new Visual(endpoint.position(), backing, overlay);
		Visual raced = visuals.put(doorId, replacement);
		if(raced != null && raced != replacement)
		{
			raced.remove();
		}
		if(closed.get())
		{
			visuals.remove(doorId, replacement);
			replacement.remove();
			return;
		}
		startAnimator(doorId, replacement, world, anchor, plane.facing(), overlayGeometry);
	}

	private void startAnimator(
		UUID doorId,
		Visual visual,
		World world,
		Location anchor,
		BlockFace facing,
		PortalPlaneGeometry overlayGeometry)
	{
		int chunkX = anchor.getBlockX() >> 4;
		int chunkZ = anchor.getBlockZ() >> 4;
		int[] tick = new int[] {0};
		boolean[] attended = new boolean[] {false};
		Runnable[] holder = new Runnable[1];
		holder[0] = () ->
		{
			if(!shouldContinueAnimating(doorId, visual))
			{
				return;
			}
			int t = tick[0];
			tick[0] = t + DoorPortalAnimation.FRAME_PERIOD_TICKS;
			if(t % DoorPortalAnimation.ATTENDANCE_PERIOD_TICKS == 0)
			{
				attended[0] = hasNearbyViewer(world, anchor);
			}
			if(attended[0])
			{
				animateFrame(visual, world, anchor, facing, overlayGeometry, t);
			}
			FoliaScheduler.runRegion(plugin, world, chunkX, chunkZ, holder[0], DoorPortalAnimation.FRAME_PERIOD_TICKS);
		};
		FoliaScheduler.runRegion(plugin, world, chunkX, chunkZ, holder[0], DoorPortalAnimation.FRAME_PERIOD_TICKS);
	}

	boolean shouldContinueAnimating(UUID doorId, Visual visual)
	{
		return !closed.get() && visuals.get(doorId) == visual && visual.isValid();
	}

	private boolean hasNearbyViewer(World world, Location anchor)
	{
		for(Player player : world.getPlayers())
		{
			if(player.getWorld() == world && player.getLocation().distanceSquared(anchor) <= DoorPortalAnimation.ATTENDANCE_RANGE_SQUARED)
			{
				return true;
			}
		}
		return false;
	}

	void animateFrame(
		Visual visual,
		World world,
		Location anchor,
		BlockFace facing,
		PortalPlaneGeometry overlayGeometry,
		int tick)
	{
		BlockDisplay overlay = visual.overlay();
		PortalPlaneGeometry frame = DoorPortalAnimation.frame(overlayGeometry, facing, tick);
		overlay.setInterpolationDelay(0);
		overlay.setInterpolationDuration(DoorPortalAnimation.FRAME_PERIOD_TICKS);
		overlay.setTransformation(new Transformation(
			new Vector3f(frame.translationX(), frame.translationY(), frame.translationZ()),
			new Quaternionf(),
			new Vector3f(frame.scaleX(), frame.scaleY(), frame.scaleZ()),
			new Quaternionf()));
		if(!Settings.ENABLE_PARTICLES)
		{
			return;
		}
		for(int arm = 0; arm < DoorPortalAnimation.ORBIT_ARMS; arm++)
		{
			double[] point = DoorPortalAnimation.orbitPoint(overlayGeometry, facing, tick, arm);
			Particle trail = (arm & 1) == 0 ? Particle.PORTAL : Particle.REVERSE_PORTAL;
			world.spawnParticle(
				trail,
				anchor.getX() + point[0],
				anchor.getY() + point[1],
				anchor.getZ() + point[2],
				1, 0.03D, 0.03D, 0.03D, 0.015D);
		}
		ThreadLocalRandom random = ThreadLocalRandom.current();
		if(tick % SPARKLE_PERIOD_TICKS == 0)
		{
			double[] point = DoorPortalAnimation.scatterPoint(
				overlayGeometry, facing, random.nextDouble(), random.nextDouble());
			world.spawnParticle(
				Particle.DUST_COLOR_TRANSITION,
				anchor.getX() + point[0],
				anchor.getY() + point[1],
				anchor.getZ() + point[2],
				1, 0.0D, 0.0D, 0.0D, 0.0D, SURFACE_DUST);
		}
		if(random.nextDouble() < AMBIENT_SOUND_CHANCE)
		{
			world.playSound(
				anchor.clone().add(0.0D, 1.0D, 0.0D),
				Sound.BLOCK_PORTAL_AMBIENT,
				SoundCategory.BLOCKS,
				0.3F,
				0.65F + (random.nextFloat() * 0.3F));
		}
	}

	private BlockDisplay spawnBacking(
		World world,
		Location anchor,
		UUID doorId,
		PortalPlaneGeometry geometry)
	{
		return world.spawn(anchor, BlockDisplay.class, spawned -> configureDisplay(
			spawned, doorId, PORTAL_MATERIAL.createBlockData(), geometry));
	}

	private BlockDisplay spawnOverlay(
		World world,
		Location anchor,
		UUID doorId,
		BlockFace facing,
		PortalPlaneGeometry geometry)
	{
		return world.spawn(anchor, BlockDisplay.class, spawned -> configureDisplay(
			spawned, doorId, portalOverlayData(facing), geometry));
	}

	private void configureDisplay(
		BlockDisplay display,
		UUID doorId,
		BlockData blockData,
		PortalPlaneGeometry geometry)
	{
		display.setBlock(blockData);
		display.setTransformation(new Transformation(
			new Vector3f(geometry.translationX(), geometry.translationY(), geometry.translationZ()),
			new Quaternionf(),
			new Vector3f(geometry.scaleX(), geometry.scaleY(), geometry.scaleZ()),
			new Quaternionf()));
		display.setBrightness(new Display.Brightness(15, 15));
		display.setDisplayWidth(PORTAL_WIDTH);
		display.setDisplayHeight(PORTAL_HEIGHT);
		display.setViewRange(32.0F);
		display.setShadowRadius(0.0F);
		display.setShadowStrength(0.0F);
		display.setPersistent(false);
		display.setInvulnerable(true);
		display.setGravity(false);
		display.setSilent(true);
		display.getPersistentDataContainer().set(markerKey, PersistentDataType.STRING, doorId.toString());
	}

	private static BlockData portalOverlayData(BlockFace facing)
	{
		Orientable blockData = (Orientable) PORTAL_OVERLAY_MATERIAL.createBlockData();
		blockData.setAxis(overlayAxis(facing));
		return blockData;
	}

	void hide(UUID doorId)
	{
		Visual visual = visuals.remove(Objects.requireNonNull(doorId, "doorId"));
		if(visual != null)
		{
			visual.remove();
		}
	}

	void cleanChunk(Chunk chunk)
	{
		Objects.requireNonNull(chunk, "chunk");
		ChunkMarker marker = new ChunkMarker(chunk.getWorld().getUID(), chunk.getX(), chunk.getZ());
		if(!cleanedChunks.add(marker))
		{
			return;
		}
		for(Entity entity : chunk.getEntities())
		{
			if(!(entity instanceof BlockDisplay display))
			{
				continue;
			}
			String encoded = display.getPersistentDataContainer().get(markerKey, PersistentDataType.STRING);
			if(encoded == null)
			{
				continue;
			}
			try
			{
				UUID doorId = UUID.fromString(encoded);
				Visual tracked = visuals.get(doorId);
				if(tracked == null || !tracked.contains(display))
				{
					display.remove();
				}
			}
			catch(IllegalArgumentException ignored)
			{
				display.remove();
			}
		}
	}

	void unloadChunk(Chunk chunk)
	{
		UUID worldId = chunk.getWorld().getUID();
		int chunkX = chunk.getX();
		int chunkZ = chunk.getZ();
		cleanedChunks.remove(new ChunkMarker(worldId, chunkX, chunkZ));
		for(Map.Entry<UUID, Visual> entry : visuals.entrySet())
		{
			DoorPosition position = entry.getValue().position();
			if(position.worldId().equals(worldId)
				&& Math.floorDiv(position.x(), 16) == chunkX
				&& Math.floorDiv(position.z(), 16) == chunkZ
				&& visuals.remove(entry.getKey(), entry.getValue()))
			{
				entry.getValue().remove();
			}
		}
	}

	@Override
	public void close()
	{
		if(!closed.compareAndSet(false, true))
		{
			return;
		}
		for(Map.Entry<UUID, Visual> entry : Map.copyOf(visuals).entrySet())
		{
			Visual visual = entry.getValue();
			if(!visual.hasValidDisplay() || visual.isOwnedByCurrentRegion())
			{
				visual.remove();
				continue;
			}
			World world = world(visual.position());
			if(world != null)
			{
				FoliaScheduler.runRegion(plugin, world, visual.position().x() >> 4, visual.position().z() >> 4, () ->
					visual.remove());
			}
		}
		visuals.clear();
		cleanedChunks.clear();
	}

	static PortalPlaneGeometry geometry(BlockFace facing, Door.Hinge hinge)
	{
		Objects.requireNonNull(facing, "facing");
		Objects.requireNonNull(hinge, "hinge");
		float lateralTranslation = lateralTranslation(facing, hinge);
		return switch(facing)
		{
			case NORTH -> new PortalPlaneGeometry(
				lateralTranslation,
				PORTAL_INSET,
				0.5F - PORTAL_RECESS - PORTAL_THICKNESS,
				PORTAL_WIDTH,
				PORTAL_HEIGHT,
				PORTAL_THICKNESS);
			case SOUTH -> new PortalPlaneGeometry(
				lateralTranslation,
				PORTAL_INSET,
				-0.5F + PORTAL_RECESS,
				PORTAL_WIDTH,
				PORTAL_HEIGHT,
				PORTAL_THICKNESS);
			case EAST -> new PortalPlaneGeometry(
				-0.5F + PORTAL_RECESS,
				PORTAL_INSET,
				lateralTranslation,
				PORTAL_THICKNESS,
				PORTAL_HEIGHT,
				PORTAL_WIDTH);
			case WEST -> new PortalPlaneGeometry(
				0.5F - PORTAL_RECESS - PORTAL_THICKNESS,
				PORTAL_INSET,
				lateralTranslation,
				PORTAL_THICKNESS,
				PORTAL_HEIGHT,
				PORTAL_WIDTH);
			default -> throw new IllegalArgumentException("Door portal facing must be cardinal: " + facing);
		};
	}

	static PortalPlaneGeometry overlayGeometry(PortalPlaneGeometry backing, BlockFace facing)
	{
		Objects.requireNonNull(backing, "backing");
		Objects.requireNonNull(facing, "facing");
		return switch(facing)
		{
			case NORTH, SOUTH -> new PortalPlaneGeometry(
				backing.translationX(),
				backing.translationY(),
				backing.translationZ() + (backing.scaleZ() / 2.0F) - (PORTAL_OVERLAY_THICKNESS / 2.0F),
				backing.scaleX(),
				backing.scaleY(),
				PORTAL_OVERLAY_THICKNESS);
			case EAST, WEST -> new PortalPlaneGeometry(
				backing.translationX() + (backing.scaleX() / 2.0F) - (PORTAL_OVERLAY_THICKNESS / 2.0F),
				backing.translationY(),
				backing.translationZ(),
				PORTAL_OVERLAY_THICKNESS,
				backing.scaleY(),
				backing.scaleZ());
			default -> throw new IllegalArgumentException("Door portal facing must be cardinal: " + facing);
		};
	}

	static Axis overlayAxis(BlockFace facing)
	{
		Objects.requireNonNull(facing, "facing");
		return switch(facing)
		{
			case NORTH, SOUTH -> Axis.X;
			case EAST, WEST -> Axis.Z;
			default -> throw new IllegalArgumentException("Door portal facing must be cardinal: " + facing);
		};
	}

	private static float lateralTranslation(BlockFace facing, Door.Hinge hinge)
	{
		int hingeSign = hinge == Door.Hinge.LEFT ? 1 : -1;
		int farSideSign = switch(facing)
		{
			case NORTH, SOUTH -> -facing.getModZ() * hingeSign;
			case EAST, WEST -> facing.getModX() * hingeSign;
			default -> throw new IllegalArgumentException("Door portal facing must be cardinal: " + facing);
		};
		return farSideSign > 0 ? -0.5F + PORTAL_INSET : -0.5F;
	}

	private World world(PlacedDoorEndpoint endpoint)
	{
		return world(endpoint.position());
	}

	private static void remove(BlockDisplay display)
	{
		if(display.isValid())
		{
			display.remove();
		}
	}

	private World world(DoorPosition position)
	{
		World byId = plugin.getServer().getWorld(position.worldId());
		return byId == null ? WorldIdentity.resolve(position.worldKey()).orElse(null) : byId;
	}

	record Visual(DoorPosition position, BlockDisplay backing, BlockDisplay overlay)
	{
		Visual
		{
			Objects.requireNonNull(position, "position");
			Objects.requireNonNull(backing, "backing");
			Objects.requireNonNull(overlay, "overlay");
		}

		private boolean isValid()
		{
			return backing.isValid() && overlay.isValid();
		}

		private boolean hasValidDisplay()
		{
			return backing.isValid() || overlay.isValid();
		}

		private boolean contains(BlockDisplay display)
		{
			return backing == display || overlay == display;
		}

		private boolean isOwnedByCurrentRegion()
		{
			return backing.isValid()
				? WormholesPlatform.isOwnedByCurrentRegion(backing)
				: WormholesPlatform.isOwnedByCurrentRegion(overlay);
		}

		private void remove()
		{
			DoorPortalVisualService.remove(backing);
			DoorPortalVisualService.remove(overlay);
		}
	}

	private record ChunkMarker(UUID worldId, int chunkX, int chunkZ)
	{
		private ChunkMarker
		{
			Objects.requireNonNull(worldId, "worldId");
		}
	}

	record PortalPlaneGeometry(
		float translationX,
		float translationY,
		float translationZ,
		float scaleX,
		float scaleY,
		float scaleZ)
	{
	}
}
