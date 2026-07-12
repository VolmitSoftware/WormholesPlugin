package art.arcane.wormholes.util;

import art.arcane.volmlib.util.bukkit.WorldIdentity;

import java.io.Serializable;
import org.bukkit.Chunk;
import org.bukkit.Location;

/**
 * A Chunk object which is serializable
 * 
 * @author cyberpwn
 */
public class GChunk implements Serializable
{
	private static final long serialVersionUID = 2L;
	
	private Integer x;
	private Integer z;
	private String worldKey;
	
	/**
	 * Create a gchunk from an existing chunk
	 * 
	 * @param chunk
	 */
	public GChunk(Chunk chunk)
	{
		this(chunk.getX(), chunk.getZ(), WorldIdentity.serialize(chunk.getWorld()));
	}
	
	/**
	 * Get a GChunk from an existing location
	 * 
	 * @param location
	 */
	public GChunk(Location location)
	{
		this(location.getChunk().getX(), location.getChunk().getZ(), WorldIdentity.serialize(location.getChunk().getWorld()));
	}
	
	/**
	 * Create a GChunk from data
	 * 
	 * @param x
	 *            the x
	 * @param z
	 *            the z
	 * @param worldKey
	 *            the fully qualified world key
	 */
	public GChunk(int x, int z, String worldKey)
	{
		this.x = x;
		this.z = z;
		this.worldKey = WorldIdentity.parse(worldKey).toString();
	}
	
	@Override
	public int hashCode()
	{
		final int prime = 31;
		int result = 1;
		result = prime * result + ((worldKey == null) ? 0 : worldKey.hashCode());
		result = prime * result + ((x == null) ? 0 : x.hashCode());
		result = prime * result + ((z == null) ? 0 : z.hashCode());
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
		
		GChunk other = (GChunk) obj;
		
		if(worldKey == null)
		{
			if(other.worldKey != null)
			{
				return false;
			}
		}
		
		else if(!worldKey.equals(other.worldKey))
		{
			return false;
		}
		
		if(x == null)
		{
			if(other.x != null)
			{
				return false;
			}
		}
		
		else if(!x.equals(other.x))
		{
			return false;
		}
		
		if(z == null)
		{
			if(other.z != null)
			{
				return false;
			}
		}
		
		else if(!z.equals(other.z))
		{
			return false;
		}
		
		return true;
	}
	
	/**
	 * Is this chunk equal to an actual chunk
	 * 
	 * @param c
	 *            the chunk
	 * @return true if they are the same place
	 */
	public boolean isChunk(Chunk c)
	{
		if(x == c.getX() && z == c.getZ() && worldKey.equals(WorldIdentity.serialize(c.getWorld())))
		{
			return true;
		}
		
		return false;
	}
	
	/**
	 * Get the x
	 * 
	 * @return x
	 */
	public Integer getX()
	{
		return x;
	}
	
	/**
	 * Set the x
	 * 
	 * @param x
	 *            the x
	 */
	public void setX(Integer x)
	{
		this.x = x;
	}
	
	/**
	 * Get the z
	 * 
	 * @return the z
	 */
	public Integer getZ()
	{
		return z;
	}
	
	/**
	 * Set the z
	 * 
	 * @param z
	 *            the z
	 */
	public void setZ(Integer z)
	{
		this.z = z;
	}
	
	/**
	 * Get the world
	 * 
	 * @return the world key
	 */
	public String getWorldKey()
	{
		return worldKey;
	}
	
	/**
	 * Set the world
	 * 
	 * @param worldKey
	 *            the world key
	 */
	public void setWorldKey(String worldKey)
	{
		this.worldKey = WorldIdentity.parse(worldKey).toString();
	}
	
	/**
	 * Get a chunk object out of this gchunk
	 * 
	 * @return the chunk
	 */
	public Chunk toChunk()
	{
		return WorldIdentity.resolve(worldKey)
			.orElseThrow(() -> new IllegalStateException("World is not loaded: " + worldKey))
			.getChunkAt(x, z);
	}
	
	/**
	 * String rep
	 */
	@Override
	public String toString()
	{
		return "Chunk: " + worldKey + " @ [" + x + "," + z + "]";
	}
}
