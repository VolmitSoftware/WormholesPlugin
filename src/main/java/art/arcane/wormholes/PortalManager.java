package art.arcane.wormholes;

import java.io.File;
import java.io.IOException;
import java.util.UUID;

import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.event.Listener;

import art.arcane.wormholes.portal.ILocalPortal;
import art.arcane.wormholes.portal.IPortal;
import art.arcane.wormholes.portal.LocalPortal;
import art.arcane.wormholes.portal.PortalStructure;
import art.arcane.wormholes.portal.PortalType;
import art.arcane.volmlib.util.collection.KList;
import art.arcane.volmlib.util.collection.KMap;
import art.arcane.volmlib.util.scheduling.FoliaScheduler;
import art.arcane.wormholes.util.J;
import art.arcane.wormholes.util.JSONObject;
import art.arcane.wormholes.util.VIO;

public class PortalManager implements Listener
{
	private KMap<UUID, ILocalPortal> portals;

	public PortalManager()
	{
		Wormholes.v("Starting Portal Manager");
		portals = new KMap<>();
		J.s(() -> loadExistingPortals(), 40);
		J.ar(() -> updateLocalPortals(), 0);
	}

	private void loadExistingPortals()
	{
		Wormholes.v("Loading existing portals (worlds available: " + Bukkit.getWorlds().size() + ")...");
		File portalFolder = new File(Wormholes.instance.getDataFolder(), "portals");
		portalFolder.mkdirs();

		int found = 0;
		int loaded = 0;
		int skipped = 0;

		for(File i : portalFolder.listFiles())
		{
			if(i.isDirectory())
			{
				for(File j : i.listFiles())
				{
					if(j.isDirectory())
					{
						for(File k : j.listFiles())
						{
							if(k.isFile() && k.getName().endsWith(".json"))
							{
								found++;
								if(loadPortal(k))
								{
									loaded++;
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

		Wormholes.v("Portal load complete: " + loaded + " loaded, " + skipped + " skipped (of " + found + " files)");
	}

	private boolean loadPortal(File k)
	{
		try
		{
			JSONObject j = new JSONObject(VIO.readAll(k));
			String savedWorldName = j.getJSONObject("structure").getString("world");

			if(Bukkit.getWorld(savedWorldName) == null)
			{
				Wormholes.w("Skipping portal " + k.getName() + " - world '" + savedWorldName + "' is not loaded");
				return false;
			}

			PortalType type = PortalType.valueOf(j.getString("type"));
			PortalStructure structure = new PortalStructure();
			structure.loadJSON(j.getJSONObject("structure"));

			if(structure.getWorld() == null)
			{
				Wormholes.w("Skipping portal " + k.getName() + " - structure world resolved to null after load");
				return false;
			}

			ILocalPortal portal = new LocalPortal(UUID.fromString(j.getString("id")), type, structure);

			portal.loadJSON(j);
			addLocalPortal(portal);
			portal.save();
			Wormholes.v("Loaded portal " + portal.getId() + " (" + portal.getName() + ") in " + savedWorldName);
			return true;
		}
		catch(Throwable e)
		{
			Wormholes.f("Failed to load portal file " + k.getName());
			e.printStackTrace();
			return false;
		}
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
		}
	}

	public void removeLocalPortal(UUID portal)
	{
		if(portals.containsKey(portal))
		{
			Wormholes.instance.unregisterListener(portals.get(portal));
		}

		portals.remove(portal);
	}

	public void removeLocalPortal(IPortal portal)
	{
		removeLocalPortal(portal.getId());
	}

	public int getTotalPortalCount()
	{
		return getLocalPortals().size();
	}

	public int deleteAllPortals()
	{
		KList<ILocalPortal> existing = new KList<>(portals.v());
		int removed = existing.size();

		for(ILocalPortal portal : existing)
		{
			try
			{
				if(Wormholes.projectionManager != null)
				{
					Wormholes.projectionManager.removeProjector(portal);
				}
				Wormholes.instance.unregisterListener(portal);
				portal.close();
				portal.deleteData();
			}
			catch(Throwable e)
			{
				Wormholes.w("Failed to delete portal " + portal.getId());
				e.printStackTrace();
			}
		}

		portals.clear();
		deletePortalFolder();
		return removed;
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

	private void deletePortalFolder()
	{
		File portalFolder = new File(Wormholes.instance.getDataFolder(), "portals");
		deleteRecursively(portalFolder);
		portalFolder.mkdirs();
	}

	private void deleteRecursively(File file)
	{
		if(file == null || !file.exists())
		{
			return;
		}

		if(file.isDirectory())
		{
			File[] children = file.listFiles();
			if(children != null)
			{
				for(File child : children)
				{
					deleteRecursively(child);
				}
			}
		}

		if(!file.delete() && file.exists())
		{
			Wormholes.w("Failed to delete " + file.getAbsolutePath());
		}
	}

	public void shutDown()
	{
		Wormholes.v("Shutting down portal manager");
		saveAllNow();
		portals.clear();
	}
}
