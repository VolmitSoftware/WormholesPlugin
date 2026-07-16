package art.arcane.wormholes.door;

import art.arcane.volmlib.util.bukkit.WorldIdentity;
import art.arcane.volmlib.util.scheduling.FoliaScheduler;
import art.arcane.wormholes.platform.WormholesPlatform;
import org.bukkit.Axis;
import org.bukkit.Chunk;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.World;
import org.bukkit.block.BlockFace;
import org.bukkit.block.data.BlockData;
import org.bukkit.block.data.Orientable;
import org.bukkit.block.data.type.Door;
import org.bukkit.entity.BlockDisplay;
import org.bukkit.entity.Display;
import org.bukkit.entity.Entity;
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
		BlockDisplay overlay;
		try
		{
			overlay = spawnOverlay(
				world,
				anchor,
				doorId,
				plane.facing(),
				overlayGeometry(geometry, plane.facing()));
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

	private record Visual(DoorPosition position, BlockDisplay backing, BlockDisplay overlay)
	{
		private Visual
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
