package org.cyberpwn.vortex.service;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.util.zip.GZIPInputStream;
import org.apache.commons.io.IOUtils;
import org.bukkit.Location;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.util.Vector;
import org.cyberpwn.vortex.Settings;
import org.cyberpwn.vortex.VP;
import org.cyberpwn.vortex.aperture.AperturePlane;
import org.cyberpwn.vortex.aperture.BlacklistAperture;
import org.cyberpwn.vortex.aperture.RemoteInstance;
import org.cyberpwn.vortex.network.CL;
import org.cyberpwn.vortex.portal.LocalPortal;
import org.cyberpwn.vortex.portal.Portal;
import org.cyberpwn.vortex.portal.PortalKey;
import org.cyberpwn.vortex.projection.Viewport;
import wraith.CustomGZIPOutputStream;
import wraith.ForwardedPluginMessage;
import wraith.GList;
import wraith.GMap;
import wraith.Timer;

public class ApertureService
{
	private BlacklistAperture b;
	private GMap<PortalKey, AperturePlane> remoteApaturePlanes;
	private GMap<Player, GList<Entity>> blacklistQueue;
	private GMap<Player, GList<Entity>> blacklisted;
	
	public ApertureService()
	{
		b = new BlacklistAperture();
		blacklistQueue = new GMap<Player, GList<Entity>>();
		blacklisted = new GMap<Player, GList<Entity>>();
		remoteApaturePlanes = new GMap<PortalKey, AperturePlane>();
	}
	
	public void flush()
	{
		Timer t = new Timer();
		t.start();
		
		for(Player i : blacklisted.k())
		{
			if(!i.isOnline())
			{
				blacklisted.remove(i);
				continue;
			}
		}
		
		for(Player i : blacklistQueue.k())
		{
			if(!i.isOnline())
			{
				blacklistQueue.remove(i);
				continue;
			}
			
			if(!blacklisted.containsKey(i))
			{
				blacklisted.put(i, new GList<Entity>());
			}
			
			GList<Entity> toHide = blacklistQueue.get(i).copy();
			GList<Entity> hidden = blacklisted.get(i).copy();
			GList<Entity> toShow = new GList<Entity>();
			
			for(Entity j : hidden)
			{
				if(!toHide.contains(j))
				{
					toShow.add(j);
				}
			}
			
			for(Entity j : toHide)
			{
				b.hideEntity(i, j);
			}
			
			for(Entity j : toShow)
			{
				b.showEntity(i, j);
			}
			
			blacklisted.get(i).clear();
			blacklisted.get(i).add(toHide);
		}
		
		blacklistQueue.clear();
		
		t.stop();
		TimingsService.root.get("capture-manager").hit("apature-service", t.getTime());
		t = new Timer();
		t.start();
		
		for(Portal i : VP.host.getLocalPortals())
		{
			if(i.hasWormhole())
			{
				i.getApature().sample((LocalPortal) i);
				
				if(i.isWormholeMutex())
				{
					String server = i.getWormhole().getDestination().getServer();
					
					if(server != null)
					{
						try
						{
							byte[] data = i.getApature().compress();
							ByteArrayOutputStream boas = new ByteArrayOutputStream();
							CustomGZIPOutputStream gzo = new CustomGZIPOutputStream(boas);
							gzo.setLevel(Settings.NETWORK_COMPRESSION_LEVEL);
							DataOutputStream dos = new DataOutputStream(gzo);
							String js = i.getWormhole().getDestination().toData().toJSON().toString();
							dos.writeUTF(js);
							dos.write(data);
							dos.close();
							byte[] main = boas.toByteArray();
							new ForwardedPluginMessage(VP.instance, CL.L3.get(), server, main).send();
						}
						
						catch(IOException e)
						{
							e.printStackTrace();
						}
					}
				}
				
				GMap<Portal, GMap<Player, Viewport>> lastPort = VP.projector.getLastPort();
				
				if(lastPort.containsKey(i) && i.hasWormhole())
				{
					for(Player j : lastPort.get(i).k())
					{
						for(Entity k : lastPort.get(i).get(j).getEntities())
						{
							VP.aperture.hideEntity(j, k);
						}
						
						AperturePlane ap = i.getWormhole().getDestination().getApature();
						
						if(ap != null)
						{
							GMap<Vector, RemoteInstance> r = ap.remap(i.getIdentity().getBack(), i.getWormhole().getDestination().getIdentity().getFront());
							GMap<Vector, Vector> rl = ap.remapLook(i.getIdentity().getBack(), i.getWormhole().getDestination().getIdentity().getFront());
							
							for(Vector k : r.k())
							{
								Location l = i.getPosition().getCenter().clone().add(k);
								RemoteInstance ri = r.get(k);
								
								if(lastPort.get(i).get(j).contains(l))
								{
									l.setDirection(rl.get(k));
									VP.entity.set(j, i, ri, l);
								}
							}
						}
					}
				}
			}
		}
		
		t.stop();
		TimingsService.root.get("capture-manager").get("aperture-service").hit("entity-sample", t.getTime());
	}
	
	public void layer3Stream(byte[] data)
	{
		try
		{
			ByteArrayInputStream bois = new ByteArrayInputStream(data);
			GZIPInputStream gzi = new GZIPInputStream(bois);
			DataInputStream dis = new DataInputStream(gzi);
			String json = dis.readUTF();
			byte[] d = IOUtils.toByteArray(dis);
			
			for(Portal i : VP.host.getLocalPortals())
			{
				if(i.toData().toJSON().equals(json))
				{
					if(i.hasWormhole() && i.isWormholeMutex())
					{
						if(!remoteApaturePlanes.containsKey(i.getKey()))
						{
							remoteApaturePlanes.put(i.getKey(), new AperturePlane());
						}
						
						remoteApaturePlanes.get(i.getKey()).clear();
						remoteApaturePlanes.get(i.getKey()).addCompressed(d);
					}
				}
			}
		}
		
		catch(IOException e)
		{
			e.printStackTrace();
		}
	}
	
	public void hideEntity(Player v, Entity e)
	{
		if(!blacklistQueue.containsKey(v))
		{
			blacklistQueue.put(v, new GList<Entity>());
		}
		
		blacklistQueue.get(v).add(e);
	}
	
	public void showEntity(Player v, Entity e)
	{
		if(!blacklistQueue.containsKey(v))
		{
			blacklistQueue.put(v, new GList<Entity>());
		}
		
		blacklistQueue.get(v).add(e);
	}
	
	public void showAll(Player p)
	{
		for(Entity i : getHidden(p))
		{
			showEntity(p, i);
		}
	}
	
	public GList<Entity> getHidden(Player v)
	{
		if(!blacklistQueue.containsKey(v))
		{
			blacklistQueue.put(v, new GList<Entity>());
		}
		
		return blacklistQueue.get(v).copy();
	}
	
	public BlacklistAperture getB()
	{
		return b;
	}
	
	public GMap<PortalKey, AperturePlane> getRemoteApaturePlanes()
	{
		return remoteApaturePlanes;
	}
	
	public GMap<Player, GList<Entity>> getBlacklistQueue()
	{
		return blacklistQueue;
	}
	
	public GMap<Player, GList<Entity>> getBlacklisted()
	{
		return blacklisted;
	}
}
