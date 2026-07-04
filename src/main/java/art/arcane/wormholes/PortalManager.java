package art.arcane.wormholes;

import java.io.File;
import java.io.IOException;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerMoveEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.world.WorldLoadEvent;

import art.arcane.wormholes.portal.ILocalPortal;
import art.arcane.wormholes.portal.IPortal;
import art.arcane.wormholes.portal.LocalPortal;
import art.arcane.wormholes.portal.PortalStructure;
import art.arcane.wormholes.portal.PortalType;
import art.arcane.wormholes.portal.PortalUpdateGate;
import art.arcane.volmlib.util.collection.KMap;
import art.arcane.volmlib.util.scheduling.FoliaScheduler;
import art.arcane.wormholes.network.view.ViewServer;
import art.arcane.wormholes.util.AxisAlignedBB;
import art.arcane.wormholes.util.J;
import art.arcane.wormholes.util.JSONObject;
import art.arcane.wormholes.util.VIO;

public class PortalManager implements Listener
{
	private static final int LOAD_RETRY_INTERVAL_TICKS = 20;
	private static final int LOAD_RETRY_ATTEMPTS = 30;
	private static final int ATTENDANCE_REFRESH_INTERVAL_TICKS = 5;
	private static final double ATTENDANCE_BASE_RANGE = 64.0D;

	private KMap<UUID, ILocalPortal> portals;
	private volatile List<ILocalPortal> portalSnapshot = List.of();
	private final Map<UUID, PlayerPosition> playerPositions = new ConcurrentHashMap<UUID, PlayerPosition>();
	private final List<File> pendingPortalFiles;
	private final boolean foliaRuntime;
	private boolean initialLoadComplete;
	private int loadedPortalFiles;
	private int pendingWorldPortalFiles;
	private int failedPortalFiles;
	private int unresolvedTunnelCount;
	private long driverTick;

	public PortalManager()
	{
		Wormholes.v("Starting Portal Manager");
		portals = new KMap<>();
		pendingPortalFiles = new ArrayList<File>();
		foliaRuntime = FoliaScheduler.isFoliaThreading(Bukkit.getServer());
		initialLoadComplete = false;
		loadedPortalFiles = 0;
		pendingWorldPortalFiles = 0;
		failedPortalFiles = 0;
		unresolvedTunnelCount = 0;
		driverTick = 0L;
		int loadDelay = Bukkit.getWorlds().isEmpty() ? 40 : 1;
		J.s(() -> loadExistingPortals(), loadDelay);
		schedulePendingLoadRetry(LOAD_RETRY_ATTEMPTS);
		J.ar(() -> updateLocalPortals(), 0);
	}

	@EventHandler
	public void on(WorldLoadEvent e)
	{
		loadPendingPortals();
	}

	@EventHandler
	public void on(PlayerJoinEvent e)
	{
		recordPlayerPosition(e.getPlayer(), e.getPlayer().getLocation());
	}

	@EventHandler
	public void on(PlayerMoveEvent e)
	{
		recordPlayerPosition(e.getPlayer(), e.getTo());
	}

	@EventHandler
	public void on(PlayerQuitEvent e)
	{
		playerPositions.remove(e.getPlayer().getUniqueId());
	}

	private void recordPlayerPosition(Player player, Location location)
	{
		if(location == null || location.getWorld() == null)
		{
			return;
		}
		playerPositions.put(player.getUniqueId(), new PlayerPosition(location.getWorld().getUID(), location.getX(), location.getY(), location.getZ()));
	}

	private void loadExistingPortals()
	{
		Wormholes.v("Loading existing portals (worlds available: " + Bukkit.getWorlds().size() + ")...");
		File portalFolder = new File(Wormholes.instance.getDataFolder(), "portals");
		portalFolder.mkdirs();

		int found = 0;
		int loaded = 0;
		int skipped = 0;

		File[] firstLevel = portalFolder.listFiles();
		if(firstLevel == null)
		{
			initialLoadComplete = true;
			return;
		}

		for(File i : firstLevel)
		{
			if(i.isDirectory())
			{
				File[] secondLevel = i.listFiles();
				if(secondLevel == null)
				{
					continue;
				}
				for(File j : secondLevel)
				{
					if(j.isDirectory())
					{
							File[] files = j.listFiles();
							if(files == null)
							{
								continue;
							}
							for(File k : files)
							{
								if(k.isFile() && k.getName().endsWith(".json"))
								{
									found++;
									PortalLoadResult result = loadPortal(k);
									if(result == PortalLoadResult.LOADED)
									{
										loaded++;
									}
									else if(result == PortalLoadResult.PENDING_WORLD)
									{
										queuePendingPortal(k);
										skipped++;
									}
									else
									{
										skipped++;
									}
							}
						}
					}
				}
			}
		}
	
		initialLoadComplete = true;
		refreshUnresolvedTunnelCount();
		Wormholes.v("Portal load complete: " + loaded + " loaded, " + skipped + " skipped (of " + found + " files), pending=" + pendingPortalFiles.size());
	}

	private PortalLoadResult loadPortal(File k)
	{
		try
		{
			JSONObject j = new JSONObject(VIO.readAll(k));
			String savedWorldName = j.getJSONObject("structure").getString("world");

			if(Bukkit.getWorld(savedWorldName) == null)
			{
				Wormholes.w("Skipping portal " + k.getName() + " - world '" + savedWorldName + "' is not loaded");
				return PortalLoadResult.PENDING_WORLD;
			}

			PortalType type = PortalType.valueOf(j.getString("type"));
			PortalStructure structure = new PortalStructure();
			structure.loadJSON(j.getJSONObject("structure"));

			if(structure.getWorld() == null)
			{
				Wormholes.w("Skipping portal " + k.getName() + " - structure world resolved to null after load");
				failedPortalFiles++;
				return PortalLoadResult.FAILED;
			}

			ILocalPortal portal = new LocalPortal(UUID.fromString(j.getString("id")), type, structure);

			portal.loadJSON(j);
			addLocalPortal(portal);
			loadedPortalFiles++;
			Wormholes.v("Loaded portal " + portal.getId() + " (" + portal.getName() + ") in " + savedWorldName);
			return PortalLoadResult.LOADED;
		}
		catch(Throwable e)
		{
			Wormholes.f("Failed to load portal file " + k.getName());
			e.printStackTrace();
			failedPortalFiles++;
			return PortalLoadResult.FAILED;
		}
	}

	private void queuePendingPortal(File file)
	{
		if(pendingPortalFiles.contains(file))
		{
			return;
		}
		pendingWorldPortalFiles++;
		pendingPortalFiles.add(file);
	}

	private void loadPendingPortals()
	{
		if(pendingPortalFiles.isEmpty())
		{
			refreshUnresolvedTunnelCount();
			return;
		}

		Iterator<File> iterator = pendingPortalFiles.iterator();
		while(iterator.hasNext())
		{
			File file = iterator.next();
			PortalLoadResult result = loadPortal(file);
			if(result == PortalLoadResult.PENDING_WORLD)
			{
				continue;
			}
			iterator.remove();
		}
		refreshUnresolvedTunnelCount();
	}

	private void schedulePendingLoadRetry(int remainingAttempts)
	{
		if(remainingAttempts <= 0)
		{
			return;
		}
		J.s(() ->
		{
			loadPendingPortals();
			if(!pendingPortalFiles.isEmpty())
			{
				schedulePendingLoadRetry(remainingAttempts - 1);
			}
		}, LOAD_RETRY_INTERVAL_TICKS);
	}

	public void saveAll()
	{
		for(ILocalPortal i : getLocalPortals())
		{
			i.save();
		}
	}

	public void saveAllNow()
	{
		for(ILocalPortal i : getLocalPortals())
		{
			try
			{
				i.saveNow();
			}

			catch(IOException e)
			{
				e.printStackTrace();
			}
		}
	}

	public void updateLocalPortals()
	{
		if(!initialLoadComplete)
		{
			return;
		}

		driverTick++;
		List<ILocalPortal> snapshot = getLocalPortals();

		if(driverTick % ATTENDANCE_REFRESH_INTERVAL_TICKS == 0)
		{
			refreshAttendance(snapshot);
		}

		if(foliaRuntime)
		{
			for(ILocalPortal i : snapshot)
			{
				if(PortalUpdateGate.isDue(i.isOpen(), i.isAmbientAttended(), driverTick, PortalUpdateGate.staggerOffset(i.getId())))
				{
					updateLocalPortal(i);
				}
			}
			return;
		}

		Map<UUID, WorldBatch> byWorld = new HashMap<UUID, WorldBatch>();
		for(ILocalPortal i : snapshot)
		{
			if(!PortalUpdateGate.isDue(i.isOpen(), i.isAmbientAttended(), driverTick, PortalUpdateGate.staggerOffset(i.getId())))
			{
				continue;
			}

			Location center = i.getCenter();
			if(center == null || center.getWorld() == null)
			{
				continue;
			}

			UUID worldId = center.getWorld().getUID();
			WorldBatch batch = byWorld.get(worldId);
			if(batch == null)
			{
				batch = new WorldBatch(center, new ArrayList<ILocalPortal>());
				byWorld.put(worldId, batch);
			}
			batch.portals().add(i);
		}

		for(WorldBatch batch : byWorld.values())
		{
			FoliaScheduler.runRegion(Wormholes.instance, batch.anchor(), () ->
			{
				for(ILocalPortal portal : batch.portals())
				{
					try
					{
						runPortalUpdate(portal);
					}
					catch(Throwable e)
					{
						e.printStackTrace();
					}
				}
			});
		}
	}

	private void refreshAttendance(List<ILocalPortal> snapshot)
	{
		Collection<PlayerPosition> positions = playerPositions.values();

		for(ILocalPortal portal : snapshot)
		{
			Location center = portal.getCenter();
			if(center == null || center.getWorld() == null)
			{
				portal.setAmbientAttended(false);
				continue;
			}

			AxisAlignedBB area = portal.getArea();
			double threshold = ATTENDANCE_BASE_RANGE;
			if(area != null)
			{
				threshold += 0.5D * Math.sqrt((area.sizeX() * area.sizeX()) + (area.sizeY() * area.sizeY()) + (area.sizeZ() * area.sizeZ()));
			}
			double thresholdSquared = threshold * threshold;
			UUID worldId = center.getWorld().getUID();
			boolean attended = false;
			for(PlayerPosition position : positions)
			{
				if(!worldId.equals(position.worldId()))
				{
					continue;
				}
				double dx = position.x() - center.getX();
				double dy = position.y() - center.getY();
				double dz = position.z() - center.getZ();
				if((dx * dx) + (dy * dy) + (dz * dz) <= thresholdSquared)
				{
					attended = true;
					break;
				}
			}
			portal.setAmbientAttended(attended);
		}
	}

	private void updateLocalPortal(ILocalPortal portal)
	{
		Location center = portal.getCenter();
		if(center == null || center.getWorld() == null)
		{
			return;
		}

		FoliaScheduler.runRegion(Wormholes.instance, center, () -> runPortalUpdate(portal));
	}

	private void runPortalUpdate(ILocalPortal portal)
	{
		try
		{
			portal.update();
		}
		catch(Throwable e)
		{
			e.printStackTrace();
		}

		if(portal.needsSaving())
		{
			portal.willSave();

			if(Wormholes.portalSyncService != null)
			{
				Wormholes.portalSyncService.broadcastPortal(portal);
			}

			FoliaScheduler.runAsync(Wormholes.instance, () ->
			{
				try
				{
					portal.saveNow();
				}
				catch(IOException e)
				{
					e.printStackTrace();
				}
			});
		}
	}

	public List<ILocalPortal> getLocalPortals()
	{
		return portalSnapshot;
	}

	public boolean hasLocalPortal(UUID id)
	{
		return portals.containsKey(id);
	}

	public ILocalPortal getLocalPortal(UUID id)
	{
		return portals.get(id);
	}

	public boolean hasLocalPortal(IPortal portal)
	{
		return hasLocalPortal(portal.getId());
	}

	public void addLocalPortal(ILocalPortal portal)
	{
		if(!hasLocalPortal(portal))
		{
			portals.put(portal.getId(), portal);
			refreshPortalSnapshot();
			Wormholes.instance.registerListener(portal);

			if(Wormholes.portalSyncService != null)
			{
				Wormholes.portalSyncService.broadcastPortal(portal);
			}
			syncGatewayTickets();
		}
	}

	public void removeLocalPortal(UUID portal)
	{
		if(portals.containsKey(portal))
		{
			Wormholes.instance.unregisterListener(portals.get(portal));

			if(Wormholes.portalSyncService != null)
			{
				Wormholes.portalSyncService.broadcastRemove(portal);
			}
		}

		portals.remove(portal);
		refreshPortalSnapshot();
		syncGatewayTickets();
	}

	public void removeLocalPortal(IPortal portal)
	{
		removeLocalPortal(portal.getId());
	}

	public int deleteAllPortals()
	{
		List<ILocalPortal> snapshot = getLocalPortals();

		for(ILocalPortal portal : snapshot)
		{
			if(Wormholes.projectionManager != null)
			{
				Wormholes.projectionManager.removeProjector(portal);
			}
			Wormholes.instance.unregisterListener(portal);
			if(Wormholes.portalSyncService != null)
			{
				Wormholes.portalSyncService.broadcastRemove(portal.getId());
			}
		}

		portals.clear();
		refreshPortalSnapshot();
		pendingPortalFiles.clear();
		initialLoadComplete = true;
		loadedPortalFiles = 0;
		pendingWorldPortalFiles = 0;
		failedPortalFiles = 0;
		unresolvedTunnelCount = 0;
		deletePortalFolder();
		syncGatewayTickets();
		return snapshot.size();
	}

	public int getTotalPortalCount()
	{
		return getLocalPortals().size();
	}

	public int getAccessableCount(PortalType t)
	{
		if(t.equals(PortalType.GATEWAY))
		{
			return getGatewayCount();
		}

		return getTotalPortalCount() - getGatewayCount();
	}

	public List<GatewayPortalInfo> listGatewayPortalsNear(Location origin, double radius)
	{
		if(origin == null || origin.getWorld() == null)
		{
			return List.of();
		}
		double radiusSquared = radius * radius;
		List<GatewayPortalInfo> matches = new ArrayList<GatewayPortalInfo>();
		for(ILocalPortal portal : getLocalPortals())
		{
			if(!portal.isGateway())
			{
				continue;
			}
			Location center = portal.getCenter();
			if(center == null || center.getWorld() == null || !center.getWorld().equals(origin.getWorld()))
			{
				continue;
			}
			double dx = center.getX() - origin.getX();
			double dy = center.getY() - origin.getY();
			double dz = center.getZ() - origin.getZ();
			double distanceSquared = (dx * dx) + (dy * dy) + (dz * dz);
			if(distanceSquared > radiusSquared)
			{
				continue;
			}
			art.arcane.wormholes.util.Direction normal = portal.getFrame() == null ? null : portal.getFrame().getNormal();
			double nx = normal == null ? 0.0D : normal.x();
			double ny = normal == null ? 0.0D : normal.y();
			double nz = normal == null ? 0.0D : normal.z();
			matches.add(new GatewayPortalInfo(portal.getId(), center.getX(), center.getY(), center.getZ(), nx, ny, nz));
		}
		return matches;
	}

	public record GatewayPortalInfo(UUID portalId, double centerX, double centerY, double centerZ, double normalX, double normalY, double normalZ)
	{
	}

	public int getGatewayCount()
	{
		int g = 0;

		for(ILocalPortal i : getLocalPortals())
		{
			if(i.isGateway())
			{
				g++;
			}
		}

		return g;
	}

	public File getSaveFile(UUID id)
	{
		return new File(new File(new File(new File(Wormholes.instance.getDataFolder(), "portals"), id.toString().split("-")[1]), id.toString().split("-")[0]), id.toString() + ".json");
	}

	public void shutDown()
	{
		Wormholes.v("Shutting down portal manager");
		saveAllNow();
		pendingPortalFiles.clear();
		portals.clear();
		refreshPortalSnapshot();
	}

	private void refreshUnresolvedTunnelCount()
	{
		int unresolved = 0;
		for(ILocalPortal portal : getLocalPortals())
		{
			if(portal.getTunnel() != null && !portal.hasTunnel())
			{
				unresolved++;
			}
		}
		unresolvedTunnelCount = unresolved;
	}

	private synchronized void refreshPortalSnapshot()
	{
		portalSnapshot = List.copyOf(portals.values());
	}

	private void syncGatewayTickets()
	{
		ViewServer activeViewServer = Wormholes.viewServer;
		if(activeViewServer != null)
		{
			activeViewServer.syncGatewayTickets();
		}
	}

	private void deletePortalFolder()
	{
		Path path = new File(Wormholes.instance.getDataFolder(), "portals").toPath();
		if(!Files.exists(path))
		{
			return;
		}
		try
		{
			Files.walkFileTree(path, new SimpleFileVisitor<Path>()
			{
				@Override
				public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException
				{
					Files.deleteIfExists(file);
					return FileVisitResult.CONTINUE;
				}

				@Override
				public FileVisitResult postVisitDirectory(Path dir, IOException exc) throws IOException
				{
					if(exc != null)
					{
						throw exc;
					}
					Files.deleteIfExists(dir);
					return FileVisitResult.CONTINUE;
				}
			});
		}
		catch(IOException e)
		{
			throw new IllegalStateException("Could not delete Wormholes portal data", e);
		}
	}

	private enum PortalLoadResult
	{
		LOADED,
		PENDING_WORLD,
		FAILED
	}

	private record PlayerPosition(UUID worldId, double x, double y, double z)
	{
	}

	private record WorldBatch(Location anchor, List<ILocalPortal> portals)
	{
	}
}
