package art.arcane.wormholes.door;

import art.arcane.volmlib.util.bukkit.WorldIdentity;
import art.arcane.volmlib.util.scheduling.FoliaScheduler;
import art.arcane.wormholes.platform.WormholesPlatform;
import org.bukkit.Chunk;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.World;
import org.bukkit.block.BlockFace;
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
	static final Material PORTAL_MATERIAL = Material.LIGHT_BLUE_CONCRETE;
	private static final float PORTAL_INSET = 0.0625F;
	private static final float PORTAL_WIDTH = 1.0F - (PORTAL_INSET * 2.0F);
	private static final float PORTAL_HEIGHT = 2.0F - (PORTAL_INSET * 2.0F);
	private static final float PORTAL_THICKNESS = 0.035F;

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
		if(current != null && current.display().isValid())
		{
			return;
		}
		if(current != null)
		{
			visuals.remove(doorId, current);
		}

		DoorwayPlane plane = snapshot.plane();
		World world = world(endpoint);
		if(world == null)
		{
			return;
		}
		Location anchor = new Location(world, plane.blockX() + 0.5D, plane.blockY(), plane.blockZ() + 0.5D);
		PortalPlaneGeometry geometry = geometry(plane.facing());
		if(closed.get())
		{
			return;
		}
		BlockDisplay display = world.spawn(anchor, BlockDisplay.class, spawned ->
		{
			spawned.setBlock(PORTAL_MATERIAL.createBlockData());
			spawned.setTransformation(new Transformation(
				new Vector3f(geometry.translationX(), geometry.translationY(), geometry.translationZ()),
				new Quaternionf(),
				new Vector3f(geometry.scaleX(), geometry.scaleY(), geometry.scaleZ()),
				new Quaternionf()));
			spawned.setBrightness(new Display.Brightness(15, 15));
			spawned.setDisplayWidth(PORTAL_WIDTH);
			spawned.setDisplayHeight(PORTAL_HEIGHT);
			spawned.setViewRange(32.0F);
			spawned.setShadowRadius(0.0F);
			spawned.setShadowStrength(0.0F);
			spawned.setPersistent(false);
			spawned.setInvulnerable(true);
			spawned.setGravity(false);
			spawned.setSilent(true);
			spawned.getPersistentDataContainer().set(markerKey, PersistentDataType.STRING, doorId.toString());
		});
		if(closed.get())
		{
			if(display.isValid())
			{
				display.remove();
			}
			return;
		}
		Visual replacement = new Visual(endpoint.position(), display);
		Visual raced = visuals.put(doorId, replacement);
		if(raced != null && raced.display() != display && raced.display().isValid())
		{
			raced.display().remove();
		}
		if(closed.get())
		{
			visuals.remove(doorId, replacement);
			if(display.isValid())
			{
				display.remove();
			}
		}
	}

	void hide(UUID doorId)
	{
		Visual visual = visuals.remove(Objects.requireNonNull(doorId, "doorId"));
		if(visual != null && visual.display().isValid())
		{
			visual.display().remove();
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
				if(tracked == null || tracked.display() != display)
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
				BlockDisplay display = entry.getValue().display();
				if(display.isValid())
				{
					display.remove();
				}
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
			if(visual.display().isValid() && WormholesPlatform.isOwnedByCurrentRegion(visual.display()))
			{
				visual.display().remove();
				continue;
			}
			World world = world(visual.position());
			if(world != null)
			{
				FoliaScheduler.runRegion(plugin, world, visual.position().x() >> 4, visual.position().z() >> 4, () ->
				{
					BlockDisplay display = visual.display();
					if(display.isValid())
					{
						display.remove();
					}
				});
			}
		}
		visuals.clear();
		cleanedChunks.clear();
	}

	static PortalPlaneGeometry geometry(BlockFace facing)
	{
		Objects.requireNonNull(facing, "facing");
		return switch(facing)
		{
			case NORTH -> new PortalPlaneGeometry(
				-PORTAL_WIDTH / 2.0F,
				PORTAL_INSET,
				0.5F,
				PORTAL_WIDTH,
				PORTAL_HEIGHT,
				PORTAL_THICKNESS);
			case SOUTH -> new PortalPlaneGeometry(
				-PORTAL_WIDTH / 2.0F,
				PORTAL_INSET,
				-0.5F - PORTAL_THICKNESS,
				PORTAL_WIDTH,
				PORTAL_HEIGHT,
				PORTAL_THICKNESS);
			case EAST -> new PortalPlaneGeometry(
				-0.5F - PORTAL_THICKNESS,
				PORTAL_INSET,
				-PORTAL_WIDTH / 2.0F,
				PORTAL_THICKNESS,
				PORTAL_HEIGHT,
				PORTAL_WIDTH);
			case WEST -> new PortalPlaneGeometry(
				0.5F,
				PORTAL_INSET,
				-PORTAL_WIDTH / 2.0F,
				PORTAL_THICKNESS,
				PORTAL_HEIGHT,
				PORTAL_WIDTH);
			default -> throw new IllegalArgumentException("Door portal facing must be cardinal: " + facing);
		};
	}

	private World world(PlacedDoorEndpoint endpoint)
	{
		return world(endpoint.position());
	}

	private World world(DoorPosition position)
	{
		World byId = plugin.getServer().getWorld(position.worldId());
		return byId == null ? WorldIdentity.resolve(position.worldKey()).orElse(null) : byId;
	}

	private record Visual(DoorPosition position, BlockDisplay display)
	{
		private Visual
		{
			Objects.requireNonNull(position, "position");
			Objects.requireNonNull(display, "display");
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
