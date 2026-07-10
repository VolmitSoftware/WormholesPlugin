package art.arcane.wormholes.portal;

import java.util.Arrays;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.util.Vector;

import art.arcane.wormholes.Settings;
import art.arcane.wormholes.util.AxisAlignedBB;
import art.arcane.wormholes.util.Cuboid;
import art.arcane.wormholes.util.Direction;
import art.arcane.wormholes.util.JSONArray;
import art.arcane.volmlib.util.collection.KList;
import art.arcane.volmlib.util.collection.KMap;
import art.arcane.volmlib.util.collection.KSet;
import art.arcane.wormholes.util.JSONObject;

public class PortalStructure implements IWritable
{
	private AxisAlignedBB captureZone;
	private AxisAlignedBB area;
	private AxisAlignedBB box;
	private World world;
	private KMap<Direction, AxisAlignedBB> faceCache = new KMap<>();
	private final ConcurrentHashMap<Direction, List<AxisAlignedBB>> apertureFaceCache = new ConcurrentHashMap<>();
	private KSet<Location> cornerCache;
	private volatile Location centerCache;
	private long[] blockKeys = new long[0];
	private int blockKeyCount;
	private final KList<Vector> blockPositions = new KList<Vector>();

	@Override
	public void saveJSON(JSONObject j)
	{
		j.put("world", world.getName());
		j.put("area", area.toJSON());
		JSONArray blocks = new JSONArray();
		for(Vector block : blockPositions)
		{
			JSONObject blockJson = new JSONObject();
			blockJson.put("x", block.getBlockX());
			blockJson.put("y", block.getBlockY());
			blockJson.put("z", block.getBlockZ());
			blocks.put(blockJson);
		}
		j.put("blocks", blocks);
	}

	@Override
	public void loadJSON(JSONObject j)
	{
		area = new AxisAlignedBB(0, 0, 0, 0, 0, 0);
		area.loadJSON(j.getJSONObject("area"));
		setWorld(Bukkit.getWorld(j.getString("world")));
		clearBlockCells();
		if(j.has("blocks"))
		{
			loadBlockCells(j.getJSONArray("blocks"));
		}
		else
		{
			setBlockCellsFromAreaBounds();
		}
		rebuildCaptureZone();
		invalidateCache();
	}

	@Override
	public JSONObject toJSON()
	{
		JSONObject o = new JSONObject();
		saveJSON(o);

		return o;
	}

	public World getWorld()
	{
		return world;
	}

	public KSet<Block> toBlocks()
	{
		KList<Block> list = new KList<Block>();
		if(getWorld() == null)
		{
			return new KSet<Block>(list);
		}

		for(Vector block : blockPositions)
		{
			list.add(getWorld().getBlockAt(block.getBlockX(), block.getBlockY(), block.getBlockZ()));
		}

		return new KSet<Block>(list);
	}

	public AxisAlignedBB getBox()
	{
		if(box == null)
		{
			Location min = corner(Direction.W, Direction.D, Direction.N);
			Location max = corner(Direction.E, Direction.U, Direction.S);
			box = new AxisAlignedBB(min.getX(), max.getX(), min.getY(), max.getY(), min.getZ(), max.getZ());
		}

		return box;
	}

	public Location getCenter()
	{
		Location cached = centerCache;
		if(cached == null)
		{
			Location min = corner(Direction.W, Direction.D, Direction.N);
			Location max = corner(Direction.E, Direction.U, Direction.S);
			cached = min.clone().add(max.clone().subtract(min).toVector().multiply(0.5));
			centerCache = cached;
		}
		return cached.clone();
	}

	public Location randomLocation()
	{
		if(!blockPositions.isEmpty())
		{
			Vector block = blockPositions.getRandom();
			return new Location(getWorld(), block.getBlockX() + Math.random(), block.getBlockY() + Math.random(), block.getBlockZ() + Math.random());
		}

		return getArea().random().toLocation(getWorld());
	}

	public void setWorld(World world)
	{
		this.world = world;
		centerCache = null;
	}

	public Set<Location> getCorners()
	{
		if(cornerCache == null)
		{
			cornerCache = new KSet<Location>();
			cornerCache.add(corner(Direction.W, Direction.U, Direction.N));
			cornerCache.add(corner(Direction.W, Direction.U, Direction.S));
			cornerCache.add(corner(Direction.W, Direction.D, Direction.N));
			cornerCache.add(corner(Direction.W, Direction.D, Direction.S));
			cornerCache.add(corner(Direction.E, Direction.U, Direction.N));
			cornerCache.add(corner(Direction.E, Direction.U, Direction.S));
			cornerCache.add(corner(Direction.E, Direction.D, Direction.N));
			cornerCache.add(corner(Direction.E, Direction.D, Direction.S));
		}

		return cornerCache;
	}

	private Location corner(Direction x, Direction y, Direction z)
	{
		Vector v = getArea().getCornerVector(x, y, z);
		return new Location(getWorld(), v.getX(), v.getY(), v.getZ());
	}

	public AxisAlignedBB getFace(Direction face)
	{
		if(!faceCache.containsKey(face))
		{
			faceCache.put(face, getArea().getFace(face));
		}

		return faceCache.get(face);
	}

	public AxisAlignedBB getArea()
	{
		return area;
	}

	public void setArea(Cuboid area)
	{
		this.area = new AxisAlignedBB(area);
		setBlockCellsFromAreaBounds();
		rebuildCaptureZone();
		invalidateCache();
	}

	public void setBlocks(Set<Block> blocks)
	{
		if(blocks == null || blocks.isEmpty())
		{
			return;
		}

		Cuboid bounds = null;
		World blockWorld = null;
		clearBlockCells();

		for(Block block : blocks)
		{
			if(block == null || block.getWorld() == null)
			{
				continue;
			}

			blockWorld = block.getWorld();
			addBlockCell(block.getX(), block.getY(), block.getZ());
			Cuboid cell = new Cuboid(block.getLocation());
			bounds = bounds == null ? cell : bounds.getBoundingCuboid(cell);
		}

		if(bounds == null)
		{
			return;
		}

		setWorld(blockWorld);
		this.area = new AxisAlignedBB(bounds);
		rebuildCaptureZone();
		invalidateCache();
	}

	public boolean contains(Location location)
	{
		if(location == null || getArea() == null || !getArea().contains(location))
		{
			return false;
		}

		if(getWorld() != null && location.getWorld() != null && !getWorld().equals(location.getWorld()))
		{
			return false;
		}

		return containsBlock(location.getBlockX(), location.getBlockY(), location.getBlockZ());
	}

	public boolean containsBlock(int x, int y, int z)
	{
		if(blockKeyCount == 0)
		{
			return getArea() != null && getArea().containsPrimitive(x + 0.5D, y + 0.5D, z + 0.5D);
		}

		return Arrays.binarySearch(blockKeys, 0, blockKeyCount, packBlockKey(x, y, z)) >= 0;
	}

	public KList<Vector> getBlockPositions()
	{
		KList<Vector> copy = new KList<Vector>();
		for(Vector block : blockPositions)
		{
			copy.add(block.clone());
		}
		return copy;
	}

	public KList<AxisAlignedBB> getApertureFaces(Direction face)
	{
		KList<AxisAlignedBB> copy = new KList<AxisAlignedBB>();
		for(AxisAlignedBB apertureFace : getCachedApertureFaces(face))
		{
			copy.add(new AxisAlignedBB(apertureFace));
		}
		return copy;
	}

	public List<AxisAlignedBB> getCachedApertureFaces(Direction face)
	{
		List<AxisAlignedBB> cached = apertureFaceCache.get(face);
		if(cached != null)
		{
			return cached;
		}

		KList<AxisAlignedBB> faces = new KList<AxisAlignedBB>();
		if(blockPositions.isEmpty() || isFullCuboid())
		{
			faces.add(getArea().getFace(face));
		}
		else
		{
			for(Vector block : blockPositions)
			{
				faces.add(getBlockBox(block.getBlockX(), block.getBlockY(), block.getBlockZ()).getFace(face));
			}
		}

		List<AxisAlignedBB> immutable = List.copyOf(faces);
		List<AxisAlignedBB> raced = apertureFaceCache.putIfAbsent(face, immutable);
		return raced == null ? immutable : raced;
	}

	public boolean isFullCuboid()
	{
		return blockKeyCount > 0 && blockKeyCount == getBoundingBlockVolume();
	}

	private void invalidateCache()
	{
		faceCache.clear();
		apertureFaceCache.clear();
		cornerCache = null;
		box = null;
		centerCache = null;
	}

	public double getSize()
	{
		return getArea().volume();
	}

	public AxisAlignedBB getCaptureZone()
	{
		return captureZone;
	}

	private void rebuildCaptureZone()
	{
		captureZone = new AxisAlignedBB(getArea().min().add(new Vector(-Settings.CAPTURE_ZONE_RADIUS, -Settings.CAPTURE_ZONE_RADIUS, -Settings.CAPTURE_ZONE_RADIUS)), getArea().max().add(new Vector(Settings.CAPTURE_ZONE_RADIUS, Settings.CAPTURE_ZONE_RADIUS, Settings.CAPTURE_ZONE_RADIUS)));
	}

	private void clearBlockCells()
	{
		blockKeyCount = 0;
		blockPositions.clear();
	}

	private void loadBlockCells(JSONArray blocks)
	{
		for(int i = 0; i < blocks.length(); i++)
		{
			JSONObject block = blocks.getJSONObject(i);
			addBlockCell(block.getInt("x"), block.getInt("y"), block.getInt("z"));
		}
	}

	private void setBlockCellsFromAreaBounds()
	{
		clearBlockCells();
		int volume = getBoundingBlockVolume();
		if(blockKeys.length < volume)
		{
			blockKeys = new long[volume];
		}
		int xa = (int) Math.floor(getArea().getXa());
		int ya = (int) Math.floor(getArea().getYa());
		int za = (int) Math.floor(getArea().getZa());
		int xb = (int) Math.floor(getArea().getXb());
		int yb = (int) Math.floor(getArea().getYb());
		int zb = (int) Math.floor(getArea().getZb());

		for(int x = xa; x <= xb; x++)
		{
			for(int y = ya; y <= yb; y++)
			{
				for(int z = za; z <= zb; z++)
				{
					addBlockCell(x, y, z);
				}
			}
		}
	}

	private void addBlockCell(int x, int y, int z)
	{
		long key = packBlockKey(x, y, z);
		int index = Arrays.binarySearch(blockKeys, 0, blockKeyCount, key);
		if(index >= 0)
		{
			return;
		}

		int insertion = -(index + 1);
		if(blockKeyCount == blockKeys.length)
		{
			blockKeys = Arrays.copyOf(blockKeys, Math.max(16, blockKeys.length * 2));
		}
		System.arraycopy(blockKeys, insertion, blockKeys, insertion + 1, blockKeyCount - insertion);
		blockKeys[insertion] = key;
		blockKeyCount++;
		blockPositions.add(new Vector(x, y, z));
	}

	private int getBoundingBlockVolume()
	{
		int xa = (int) Math.floor(getArea().getXa());
		int ya = (int) Math.floor(getArea().getYa());
		int za = (int) Math.floor(getArea().getZa());
		int xb = (int) Math.floor(getArea().getXb());
		int yb = (int) Math.floor(getArea().getYb());
		int zb = (int) Math.floor(getArea().getZb());
		return Math.max(0, (xb - xa + 1) * (yb - ya + 1) * (zb - za + 1));
	}

	private AxisAlignedBB getBlockBox(int x, int y, int z)
	{
		return new AxisAlignedBB(x, x + 0.999D, y, y + 0.999D, z, z + 0.999D);
	}

	static long packBlockKey(int x, int y, int z)
	{
		return (((long) x & 0x3FFFFFFL) << 38) | ((((long) y) & 0xFFFL) << 26) | (((long) z) & 0x3FFFFFFL);
	}

	static int unpackBlockX(long key)
	{
		long raw = (key >> 38) & 0x3FFFFFFL;
		return (int) ((raw << 38) >> 38);
	}

	static int unpackBlockY(long key)
	{
		long raw = (key >> 26) & 0xFFFL;
		return (int) ((raw << 52) >> 52);
	}

	static int unpackBlockZ(long key)
	{
		long raw = key & 0x3FFFFFFL;
		return (int) ((raw << 38) >> 38);
	}
}
