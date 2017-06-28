package com.volmit.wormholes.aperture;

import java.lang.reflect.InvocationTargetException;
import java.util.UUID;
import org.bukkit.Location;
import org.bukkit.entity.Player;
import org.bukkit.util.Vector;
import com.comphenix.protocol.PacketType;
import com.comphenix.protocol.ProtocolLibrary;
import com.comphenix.protocol.events.PacketContainer;
import com.comphenix.protocol.wrappers.EnumWrappers.NativeGameMode;
import com.comphenix.protocol.wrappers.EnumWrappers.PlayerInfoAction;
import com.comphenix.protocol.wrappers.PlayerInfoData;
import com.comphenix.protocol.wrappers.WrappedChatComponent;
import com.comphenix.protocol.wrappers.WrappedDataWatcher;
import com.comphenix.protocol.wrappers.WrappedGameProfile;
import com.comphenix.protocol.wrappers.WrappedWatchableObject;
import com.volmit.wormholes.Wormholes;
import com.volmit.wormholes.util.GList;
import com.volmit.wormholes.util.P;
import com.volmit.wormholes.util.ParticleEffect;
import com.volmit.wormholes.util.VectorMath;
import com.volmit.wormholes.util.VersionBukkit;
import com.volmit.wormholes.wrapper.WrapperPlayServerAnimation;
import com.volmit.wormholes.wrapper.WrapperPlayServerEntityDestroy;
import com.volmit.wormholes.wrapper.WrapperPlayServerEntityHeadRotation;
import com.volmit.wormholes.wrapper.WrapperPlayServerEntityMetadata;
import com.volmit.wormholes.wrapper.WrapperPlayServerNamedEntitySpawn;
import com.volmit.wormholes.wrapper.WrapperPlayServerPlayerInfo;
import com.volmit.wormholes.wrapper.WrapperPlayServerRelEntityMove;
import com.volmit.wormholes.wrapper.WrapperPlayServerRelEntityMoveLook;

public class VirtualPlayer
{
	private Player viewer;
	private UUID uuid;
	private Integer id;
	private String name;
	private String displayName;
	private Location location;
	private Location nextLocation;
	private Boolean onGround;
	private Boolean missingSkin;
	
	public VirtualPlayer(Player viewer, UUID uuid, Integer id, String name, String displayName)
	{
		this.viewer = viewer;
		this.uuid = uuid;
		this.id = id;
		this.name = name;
		this.displayName = displayName;
		location = null;
		nextLocation = null;
		onGround = false;
		missingSkin = true;
	}
	
	public void spawn(Location location)
	{
		viewer.sendMessage("Spawning?");
		
		try
		{
			this.location = location;
			nextLocation = location;
			sendPlayerInfo();
			sendNamedEntitySpawn();
			sendEntityMetadata();
		}
		
		catch(Exception e)
		{
			e.printStackTrace();
		}
		
		viewer.sendMessage("Spawned?");
	}
	
	public void despawn()
	{
		sendPlayerInfoRemove();
		sendEntityDestroy();
	}
	
	public void tick()
	{
		onGround = nextLocation.getY() == nextLocation.getBlock().getLocation().add(0.5, 1, 0.5).getY();
		sendEntityMove();
		location = nextLocation;
		
		if(missingSkin && Wormholes.skin.hasProperties(uuid))
		{
			despawn();
			spawn(getLocation());
			
			for(double i = 0.0; i < 1.9; i += 0.15)
			{
				ParticleEffect.SPELL_WITCH.display(0.5f, 12, getLocation().clone().add(0, i, 0), viewer);
			}
		}
	}
	
	public void move(Location location)
	{
		nextLocation = location.clone();
	}
	
	public void teleport(Location location)
	{
		despawn();
		spawn(location);
	}
	
	public Player getViewer()
	{
		return viewer;
	}
	
	public UUID getUuid()
	{
		return uuid;
	}
	
	public Integer getId()
	{
		return id;
	}
	
	public String getName()
	{
		return name;
	}
	
	public String getDisplayName()
	{
		return displayName;
	}
	
	public Location getLocation()
	{
		return location;
	}
	
	public Location getNextLocation()
	{
		return nextLocation;
	}
	
	public Boolean getOnGround()
	{
		return onGround;
	}
	
	private String mark()
	{
		return name;
	}
	
	private void sendPlayerInfoRemove()
	{
		for(Player i : P.onlinePlayers())
		{
			if(i.getUniqueId().equals(uuid))
			{
				return;
			}
		}
		
		WrapperPlayServerPlayerInfo w = new WrapperPlayServerPlayerInfo();
		w.setAction(PlayerInfoAction.REMOVE_PLAYER);
		GList<PlayerInfoData> l = new GList<PlayerInfoData>();
		WrappedGameProfile profile = new WrappedGameProfile(uuid, mark());
		PlayerInfoData pid = new PlayerInfoData(profile, 1, NativeGameMode.SURVIVAL, WrappedChatComponent.fromText(mark()));
		l.add(pid);
		w.setData(l);
		w.sendPacket(viewer);
	}
	
	private void sendPlayerInfo()
	{
		WrapperPlayServerPlayerInfo w = new WrapperPlayServerPlayerInfo();
		w.setAction(PlayerInfoAction.ADD_PLAYER);
		GList<PlayerInfoData> l = new GList<PlayerInfoData>();
		WrappedGameProfile profile = new WrappedGameProfile(uuid, mark());
		
		if(Wormholes.skin.hasProperties(uuid))
		{
			missingSkin = false;
			profile.getProperties().put("textures", Wormholes.skin.getProperty(uuid).sign());
		}
		
		else
		{
			Wormholes.skin.requestProperties(uuid);
		}
		
		PlayerInfoData pid = new PlayerInfoData(profile, 1, NativeGameMode.SURVIVAL, WrappedChatComponent.fromText(mark()));
		l.add(pid);
		w.setData(l);
		w.sendPacket(viewer);
	}
	
	private void sendNamedEntitySpawn18()
	{
		WrapperPlayServerNamedEntitySpawn w = new WrapperPlayServerNamedEntitySpawn();
		w.setEntityID(id);
		w.setPlayerUUID(uuid);
		w.setYaw(location.getYaw());
		w.setPitch(location.getPitch());
		w.setX(location.getX());
		w.setY(location.getY());
		w.setZ(location.getZ());
		WrappedDataWatcher wd = new WrappedDataWatcher();
		w.setMetadata(wd);
		w.sendPacket(viewer);
	}
	
	private void sendNamedEntitySpawn()
	{
		if(VersionBukkit.get().equals(VersionBukkit.V8))
		{
			sendNamedEntitySpawn18();
			return;
		}
		
		WrapperPlayServerNamedEntitySpawn w = new WrapperPlayServerNamedEntitySpawn();
		w.setEntityID(id);
		w.setPlayerUUID(uuid);
		w.setYaw(location.getYaw());
		w.setPitch(location.getPitch());
		w.setX(location.getX());
		w.setY(location.getY());
		w.setZ(location.getZ());
		WrappedDataWatcher wd = new WrappedDataWatcher();
		w.setMetadata(wd);
		w.sendPacket(viewer);
	}
	
	public void animationSwingMainArm()
	{
		sendAnimation(0);
	}
	
	public void animationTakeDamage()
	{
		sendAnimation(1);
	}
	
	private void sendAnimation(int animation)
	{
		WrapperPlayServerAnimation w = new WrapperPlayServerAnimation();
		w.setAnimation(animation);
		w.setEntityID(id);
		w.sendPacket(viewer);
	}
	
	private void sendEntityMetadata()
	{
		WrapperPlayServerEntityMetadata w = new WrapperPlayServerEntityMetadata();
		GList<WrappedWatchableObject> watch = new GList<WrappedWatchableObject>();
		w.setEntityID(id);
		w.setMetadata(watch);
		w.sendPacket(viewer);
	}
	
	public void animationSneaking(boolean sneaking)
	{
		sendEntityMetadataSneaking(sneaking);
	}
	
	private void sendEntityMetadataSneaking(boolean sneaking)
	{
		WrapperPlayServerEntityMetadata w = new WrapperPlayServerEntityMetadata();
		GList<WrappedWatchableObject> watch = new GList<WrappedWatchableObject>();
		watch.add(new WrappedWatchableObject(new WrappedDataWatcher.WrappedDataWatcherObject(0, WrappedDataWatcher.Registry.get(Byte.class)), sneaking ? (byte) 2 : (byte) 0));
		w.setEntityID(id);
		w.setMetadata(watch);
		w.sendPacket(viewer);
	}
	
	private void sendEntityDestroy()
	{
		WrapperPlayServerEntityDestroy w = new WrapperPlayServerEntityDestroy();
		w.setEntityIds(new int[] {id});
		w.sendPacket(viewer);
	}
	
	private void sendEntityMove()
	{
		boolean pit = false;
		boolean mov = false;
		Vector dir = new Vector(0, 0, 0);
		
		if(location.getYaw() != nextLocation.getYaw() || location.getPitch() != nextLocation.getPitch())
		{
			pit = true;
		}
		
		if(location.getX() != nextLocation.getX() || location.getY() != nextLocation.getY() || location.getZ() != nextLocation.getZ())
		{
			mov = true;
			dir = VectorMath.direction(location, nextLocation).multiply(location.distance(nextLocation));
		}
		
		if(mov && !pit)
		{
			sendEntityRelativeMove(dir);
		}
		
		else if(pit)
		{
			sendEntityRelativeMoveLook(dir);
		}
	}
	
	private void sendEntityRelativeMove(Vector velocity)
	{
		WrapperPlayServerRelEntityMove w = new WrapperPlayServerRelEntityMove();
		w.setEntityID(id);
		w.setDx(getCompressedDiff(location.getX(), location.getX() + velocity.getX()));
		w.setDy(getCompressedDiff(location.getY(), location.getY() + velocity.getY()));
		w.setDz(getCompressedDiff(location.getZ(), location.getZ() + velocity.getZ()));
		w.setOnGround(onGround);
		w.sendPacket(viewer);
	}
	
	private void send(PacketContainer p)
	{
		try
		{
			ProtocolLibrary.getProtocolManager().sendServerPacket(viewer, p);
		}
		
		catch(InvocationTargetException e)
		{
			
		}
	}
	
	private void sendEntityRelativeMoveLook18(Vector velocity)
	{
		PacketContainer p = new PacketContainer(PacketType.Play.Server.REL_ENTITY_MOVE_LOOK);
		p.getIntegers().write(0, id);
		p.getBytes().write(0, (byte) getCompressedDiff(location.getX(), location.getX() + velocity.getX()));
		p.getBytes().write(1, (byte) getCompressedDiff(location.getY(), location.getY() + velocity.getY()));
		p.getBytes().write(2, (byte) getCompressedDiff(location.getZ(), location.getZ() + velocity.getZ()));
		p.getBooleans().write(0, onGround);
		p.getBytes().write(3, (byte) (nextLocation.getYaw() * 256.0F / 360.0F));
		p.getBytes().write(4, (byte) (nextLocation.getPitch() * 256.0F / 360.0F));
		send(p);
	}
	
	private void sendEntityRelativeMoveLook(Vector velocity)
	{
		if(VersionBukkit.get().equals(VersionBukkit.V8))
		{
			sendEntityRelativeMoveLook18(velocity);
			return;
		}
		
		WrapperPlayServerRelEntityMoveLook wa = new WrapperPlayServerRelEntityMoveLook();
		wa.setEntityID(id);
		wa.setDx(getCompressedDiff(location.getX(), location.getX() + velocity.getX()));
		wa.setDy(getCompressedDiff(location.getY(), location.getY() + velocity.getY()));
		wa.setDz(getCompressedDiff(location.getZ(), location.getZ() + velocity.getZ()));
		wa.setOnGround(onGround);
		wa.setYaw(nextLocation.getYaw());
		wa.setPitch(nextLocation.getPitch());
		wa.sendPacket(viewer);
		sendEntityHeadLook();
	}
	
	private void sendEntityHeadLook()
	{
		WrapperPlayServerEntityHeadRotation w = new WrapperPlayServerEntityHeadRotation();
		w.setEntityID(id);
		w.setHeadYaw((byte) (nextLocation.getYaw() * 256.0F / 360.0F));
		w.sendPacket(viewer);
	}
	
	private int getCompressedDiff(double from, double to)
	{
		return (int) (((to * 32) - (from * 32)) * 128);
	}
	
	private int getCompressedDiff8(double from, double to)
	{
		return (int) (((to * 32) - (from * 32)) * 128);
	}
	
	@Override
	public int hashCode()
	{
		final int prime = 31;
		int result = 1;
		result = prime * result + ((displayName == null) ? 0 : displayName.hashCode());
		result = prime * result + ((id == null) ? 0 : id.hashCode());
		result = prime * result + ((location == null) ? 0 : location.hashCode());
		result = prime * result + ((name == null) ? 0 : name.hashCode());
		result = prime * result + ((nextLocation == null) ? 0 : nextLocation.hashCode());
		result = prime * result + ((onGround == null) ? 0 : onGround.hashCode());
		result = prime * result + ((uuid == null) ? 0 : uuid.hashCode());
		result = prime * result + ((viewer == null) ? 0 : viewer.hashCode());
		return result;
	}
	
	@Override
	public boolean equals(Object obj)
	{
		if(this == obj)
		{
			return true;
		}
		if(obj == null)
		{
			return false;
		}
		if(getClass() != obj.getClass())
		{
			return false;
		}
		VirtualPlayer other = (VirtualPlayer) obj;
		if(displayName == null)
		{
			if(other.displayName != null)
			{
				return false;
			}
		}
		else if(!displayName.equals(other.displayName))
		{
			return false;
		}
		if(id == null)
		{
			if(other.id != null)
			{
				return false;
			}
		}
		else if(!id.equals(other.id))
		{
			return false;
		}
		if(location == null)
		{
			if(other.location != null)
			{
				return false;
			}
		}
		else if(!location.equals(other.location))
		{
			return false;
		}
		if(name == null)
		{
			if(other.name != null)
			{
				return false;
			}
		}
		else if(!name.equals(other.name))
		{
			return false;
		}
		if(nextLocation == null)
		{
			if(other.nextLocation != null)
			{
				return false;
			}
		}
		else if(!nextLocation.equals(other.nextLocation))
		{
			return false;
		}
		if(onGround == null)
		{
			if(other.onGround != null)
			{
				return false;
			}
		}
		else if(!onGround.equals(other.onGround))
		{
			return false;
		}
		if(uuid == null)
		{
			if(other.uuid != null)
			{
				return false;
			}
		}
		else if(!uuid.equals(other.uuid))
		{
			return false;
		}
		if(viewer == null)
		{
			if(other.viewer != null)
			{
				return false;
			}
		}
		else if(!viewer.equals(other.viewer))
		{
			return false;
		}
		return true;
	}
}
