package com.volmit.wormholes.portal;

import java.util.Set;

import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.util.Vector;

import com.volmit.wormholes.Settings;
import com.volmit.wormholes.util.AxisAlignedBB;
import com.volmit.wormholes.util.Cuboid;
import com.volmit.wormholes.util.Direction;
import com.volmit.wormholes.util.GList;
import com.volmit.wormholes.util.GMap;
import com.volmit.wormholes.util.GSet;
import com.volmit.wormholes.util.JSONObject;

public class PortalStructure implements IWritable
{
	private AxisAlignedBB captureZone;
	private AxisAlignedBB area;
	private AxisAlignedBB box;
	private World world;
	private GMap<Direction, AxisAlignedBB> faceCache = new GMap<>();
	private GSet<Location> cornerCache;

	@Override
	public void saveJSON(JSONObject j)
	{
		j.put("world", world.getName());
		j.put("area", area.toJSON());
	}

	@Override
	public void loadJSON(JSONObject j)
	{
		area = new AxisAlignedBB(0, 0, 0, 0, 0, 0);
		area.loadJSON(j.getJSONObject("area"));
		setWorld(Bukkit.getWorld(j.getString("world")));
		captureZone = new AxisAlignedBB(getArea().min().add(new Vector(-Settings.CAPTURE_ZONE_RADIUS, -Settings.CAPTURE_ZONE_RADIUS, -Settings.CAPTURE_ZONE_RADIUS)), getArea().max().add(new Vector(Settings.CAPTURE_ZONE_RADIUS, Settings.CAPTURE_ZONE_RADIUS, Settings.CAPTURE_ZONE_RADIUS)));
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

	public GSet<Block> toBlocks()
	{
		return new GSet<>(new GList<>(getArea().toCuboid(getWorld()).iterator()));
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
		Location min = corner(Direction.W, Direction.D, Direction.N);
		Location max = corner(Direction.E, Direction.U, Direction.S);
		return min.clone().add(max.clone().subtract(min).toVector().multiply(0.5));
	}

	public Location randomLocation()
	{
		return getArea().random().toLocation(getWorld());
	}

	public void setWorld(World world)
	{
		this.world = world;
	}

	public Set<Location> getCorners()
	{
		if(cornerCache == null)
		{
			cornerCache = new GSet<Location>();
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
		captureZone = new AxisAlignedBB(getArea().min().add(new Vector(-Settings.CAPTURE_ZONE_RADIUS, -Settings.CAPTURE_ZONE_RADIUS, -Settings.CAPTURE_ZONE_RADIUS)), getArea().max().add(new Vector(Settings.CAPTURE_ZONE_RADIUS, Settings.CAPTURE_ZONE_RADIUS, Settings.CAPTURE_ZONE_RADIUS)));
		invalidateCache();
	}

	private void invalidateCache()
	{
		faceCache.clear();
		cornerCache = null;
	}

	public double getSize()
	{
		return getArea().volume();
	}

	public AxisAlignedBB getCaptureZone()
	{
		return captureZone;
	}
}
