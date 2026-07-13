package art.arcane.wormholes.portal;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.entity.Player;

import art.arcane.volmlib.util.scheduling.FoliaScheduler;
import art.arcane.wormholes.Settings;
import art.arcane.wormholes.Wormholes;
import art.arcane.wormholes.platform.WormholesPlatform;

public final class ArrivalWarmer
{
	private final ConcurrentHashMap<ChunkKey, WarmHold> holds = new ConcurrentHashMap<ChunkKey, WarmHold>();
	private final ConcurrentHashMap<UUID, Long> destinationThrottle = new ConcurrentHashMap<UUID, Long>();
	private final ConcurrentHashMap<UUID, Long> imminentThrottle = new ConcurrentHashMap<UUID, Long>();

	public void warmAround(World world, int blockX, int blockZ)
	{
		warmAround(world, blockX, blockZ, Settings.ARRIVAL_WARM_RADIUS_CHUNKS, Settings.ARRIVAL_WARM_HOLD_MILLIS);
	}

	public void warmAround(World world, int blockX, int blockZ, int radiusChunks, long holdMillis)
	{
		if(world == null || radiusChunks < 0 || holdMillis <= 0L)
		{
			return;
		}
		int centerX = blockX >> 4;
		int centerZ = blockZ >> 4;
		long expiry = System.currentTimeMillis() + holdMillis;
		for(int dx = -radiusChunks; dx <= radiusChunks; dx++)
		{
			for(int dz = -radiusChunks; dz <= radiusChunks; dz++)
			{
				retain(world, centerX + dx, centerZ + dz, expiry);
			}
		}
	}

	public void warmDestinationOf(ILocalPortal source)
	{
		if(source == null || !Settings.ARRIVAL_PREWARM_ON_INTEREST || !source.hasTunnel())
		{
			return;
		}
		ITunnel tunnel = source.getTunnel();
		if(tunnel == null || tunnel.getTunnelType() == TunnelType.UNIVERSAL)
		{
			return;
		}
		IPortal destinationPortal = tunnel.getDestination();
		if(!(destinationPortal instanceof ILocalPortal))
		{
			return;
		}
		ILocalPortal destination = (ILocalPortal) destinationPortal;
		long now = System.currentTimeMillis();
		Long nextAllowed = destinationThrottle.get(destination.getId());
		if(nextAllowed != null && nextAllowed.longValue() > now)
		{
			return;
		}
		destinationThrottle.put(destination.getId(), Long.valueOf(now + Settings.ARRIVAL_WARM_THROTTLE_MILLIS));
		Location center = destination.getCenter();
		if(center == null || center.getWorld() == null)
		{
			return;
		}
		warmAround(center.getWorld(), center.getBlockX(), center.getBlockZ());
	}

	public void warmImminent(ILocalPortal source, Player viewer)
	{
		if(source == null || viewer == null || !Settings.ARRIVAL_PREWARM_ON_INTEREST || !source.hasTunnel())
		{
			return;
		}
		ITunnel tunnel = source.getTunnel();
		if(tunnel == null || tunnel.getTunnelType() == TunnelType.UNIVERSAL)
		{
			return;
		}
		IPortal destinationPortal = tunnel.getDestination();
		if(!(destinationPortal instanceof ILocalPortal))
		{
			return;
		}
		ILocalPortal destination = (ILocalPortal) destinationPortal;
		long now = System.currentTimeMillis();
		Long nextAllowed = imminentThrottle.get(destination.getId());
		if(nextAllowed != null && nextAllowed.longValue() > now)
		{
			return;
		}
		imminentThrottle.put(destination.getId(), Long.valueOf(now + Settings.ARRIVAL_WARM_THROTTLE_MILLIS));
		Location center = destination.getCenter();
		if(center == null || center.getWorld() == null)
		{
			return;
		}
		warmAround(center.getWorld(), center.getBlockX(), center.getBlockZ(), viewRadius(viewer), Settings.ARRIVAL_WARM_HOLD_MILLIS);
	}

	public int viewRadius(Player viewer)
	{
		int distance = WormholesPlatform.sendViewDistance(viewer);
		if(distance <= 0)
		{
			distance = Bukkit.getViewDistance();
		}
		int radius = Math.max(distance, Settings.ARRIVAL_WARM_RADIUS_CHUNKS);
		return Math.min(radius, Settings.ARRIVAL_WARM_MAX_RADIUS_CHUNKS);
	}

	private void retain(World world, int chunkX, int chunkZ, long expiry)
	{
		ChunkKey key = new ChunkKey(world.getUID(), chunkX, chunkZ);
		WarmHold existing = holds.get(key);
		if(existing != null)
		{
			existing.extend(expiry);
			return;
		}
		WarmHold hold = new WarmHold(world, chunkX, chunkZ, expiry);
		WarmHold prior = holds.putIfAbsent(key, hold);
		if(prior != null)
		{
			prior.extend(expiry);
			return;
		}
		applyTicket(key, hold);
	}

	private void applyTicket(ChunkKey key, WarmHold hold)
	{
		WormholesPlatform.loadChunk(Wormholes.instance, hold.world, hold.chunkX, hold.chunkZ).whenComplete((chunk, error) ->
		{
			if(error != null || chunk == null)
			{
				holds.remove(key, hold);
				return;
			}
			FoliaScheduler.runRegion(Wormholes.instance, hold.world, hold.chunkX, hold.chunkZ, () ->
			{
				if(holds.get(key) != hold)
				{
					return;
				}
				chunk.addPluginChunkTicket(Wormholes.instance);
				hold.applied = true;
			});
		});
	}

	public void sweep()
	{
		long now = System.currentTimeMillis();
		List<WarmHold> expired = null;
		for(WarmHold hold : holds.values())
		{
			if(hold.expiryMillis <= now)
			{
				if(expired == null)
				{
					expired = new ArrayList<WarmHold>();
				}
				expired.add(hold);
			}
		}
		if(expired != null)
		{
			for(WarmHold hold : expired)
			{
				ChunkKey key = new ChunkKey(hold.world.getUID(), hold.chunkX, hold.chunkZ);
				if(!holds.remove(key, hold))
				{
					continue;
				}
				if(hold.expiryMillis > now)
				{
					holds.putIfAbsent(key, hold);
					continue;
				}
				releaseTicket(hold);
			}
		}
		if(destinationThrottle.size() > 256)
		{
			destinationThrottle.values().removeIf(until -> until.longValue() <= now);
		}
		if(imminentThrottle.size() > 256)
		{
			imminentThrottle.values().removeIf(until -> until.longValue() <= now);
		}
	}

	private void releaseTicket(WarmHold hold)
	{
		if(!hold.applied)
		{
			return;
		}
		FoliaScheduler.runRegion(Wormholes.instance, hold.world, hold.chunkX, hold.chunkZ, () ->
		{
			if(hold.world.isChunkLoaded(hold.chunkX, hold.chunkZ))
			{
				hold.world.getChunkAt(hold.chunkX, hold.chunkZ).removePluginChunkTicket(Wormholes.instance);
			}
		});
	}

	public void shutdown()
	{
		List<WarmHold> all = new ArrayList<WarmHold>(holds.values());
		holds.clear();
		destinationThrottle.clear();
		imminentThrottle.clear();
		for(WarmHold hold : all)
		{
			releaseTicket(hold);
		}
	}

	private record ChunkKey(UUID worldId, int chunkX, int chunkZ)
	{
	}

	private static final class WarmHold
	{
		private final World world;
		private final int chunkX;
		private final int chunkZ;
		private volatile long expiryMillis;
		private volatile boolean applied;

		private WarmHold(World world, int chunkX, int chunkZ, long expiryMillis)
		{
			this.world = world;
			this.chunkX = chunkX;
			this.chunkZ = chunkZ;
			this.expiryMillis = expiryMillis;
			this.applied = false;
		}

		private void extend(long expiry)
		{
			if(expiry > expiryMillis)
			{
				expiryMillis = expiry;
			}
		}
	}
}
