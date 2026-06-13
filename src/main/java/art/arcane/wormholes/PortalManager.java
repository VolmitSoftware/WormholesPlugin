package art.arcane.wormholes;

import java.io.File;
import java.io.IOException;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.UUID;

import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.world.WorldLoadEvent;

import art.arcane.wormholes.portal.ILocalPortal;
import art.arcane.wormholes.portal.IPortal;
import art.arcane.wormholes.portal.LocalPortal;
import art.arcane.wormholes.portal.PortalStructure;
import art.arcane.wormholes.portal.PortalType;
import art.arcane.volmlib.util.collection.KList;
import art.arcane.volmlib.util.collection.KMap;
import art.arcane.volmlib.util.scheduling.FoliaScheduler;
import art.arcane.wormholes.network.view.ViewServer;
import art.arcane.wormholes.util.J;
import art.arcane.wormholes.util.JSONObject;
import art.arcane.wormholes.util.VIO;

public class PortalManager implements Listener
{
	private static final int LOAD_RETRY_INTERVAL_TICKS = 20;
	private static final int LOAD_RETRY_ATTEMPTS = 30;

	private KMap<UUID, ILocalPortal> portals;
	private final List<File> pendingPortalFiles;
	private boolean initialLoadComplete;
	private int loadedPortalFiles;
	private int pendingWorldPortalFiles;
	private int failedPortalFiles;
	private int unresolvedTunnelCount;

	public PortalManager()
	{
		Wormholes.v("Starting Portal Manager");
		portals = new KMap<>();
		pendingPortalFiles = new ArrayList<File>();
		initialLoadComplete = false;
		loadedPortalFiles = 0;
		pendingWorldPortalFiles = 0;
		failedPortalFiles = 0;
		unresolvedTunnelCount = 0;
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
		for(ILocalPortal i : portals.v())
		{
			i.save();
		}
	}

	public void saveAllNow()
	{
		for(ILocalPortal i : portals.v())
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
		for(ILocalPortal i : getLocalPortals())
		{
			updateLocalPortal(i);
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

	public KList<ILocalPortal> getLocalPortals()
	{
		return portals.v();
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
		syncGatewayTickets();
	}

	public void removeLocalPortal(IPortal portal)
	{
		removeLocalPortal(portal.getId());
	}

	public int deleteAllPortals()
	{
		List<ILocalPortal> snapshot = new ArrayList<ILocalPortal>();
		for(ILocalPortal portal : portals.v())
		{
			snapshot.add(portal);
		}

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

	public int getGatewayCount()
	{
		int g = 0;

		for(ILocalPortal i : portals.v())
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
	}

	private void refreshUnresolvedTunnelCount()
	{
		int unresolved = 0;
		for(ILocalPortal portal : portals.v())
		{
			if(portal.getTunnel() != null && !portal.hasTunnel())
			{
				unresolved++;
			}
		}
		unresolvedTunnelCount = unresolved;
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
}
