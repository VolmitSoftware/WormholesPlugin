package art.arcane.wormholes.util;

import java.util.Collection;

import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.entity.Entity;
import org.bukkit.util.Vector;

import art.arcane.wormholes.geometry.GeoPolygonProc;
import art.arcane.wormholes.portal.IWritable;
import art.arcane.volmlib.util.collection.KList;

public class AxisAlignedBB implements IWritable
{
	private double xa;
	private double xb;
	private double ya;
	private double yb;
	private double za;
	private double zb;

	@Override
	public JSONObject toJSON()
	{
		JSONObject o = new JSONObject();
		saveJSON(o);

		return o;
	}

	@Override
	public void loadJSON(JSONObject j)
	{
		xa = j.getDouble("xa");
		xb = j.getDouble("xb");
		ya = j.getDouble("ya");
		yb = j.getDouble("yb");
		za = j.getDouble("za");
		zb = j.getDouble("zb");
	}

	@Override
	public void saveJSON(JSONObject j)
	{
		j.put("xa", xa);
		j.put("xb", xb);
		j.put("ya", ya);
		j.put("yb", yb);
		j.put("za", za);
		j.put("zb", zb);
	}

	public AxisAlignedBB(double xa, double xb, double ya, double yb, double za, double zb)
	{
		this.xa = Math.min(xa, xb);
		this.xb = Math.max(xa, xb);
		this.ya = Math.min(ya, yb);
		this.yb = Math.max(ya, yb);
		this.za = Math.min(za, zb);
		this.zb = Math.max(za, zb);
	}

	public AxisAlignedBB(Cuboid c)
	{
		this(new AlignedPoint(c.getCornerVector(Direction.W, Direction.D, Direction.N)), new AlignedPoint(c.getCornerVector(Direction.E, Direction.U, Direction.S)));
	}

	public AxisAlignedBB(AlignedPoint a, AlignedPoint b)
	{
		this(a.getX(), b.getX(), a.getY(), b.getY(), a.getZ(), b.getZ());
	}

	public AxisAlignedBB(GeoPolygonProc poly)
	{
		this(poly.getX0(), poly.getX1(), poly.getY0(), poly.getY1(), poly.getZ0(), poly.getZ1());
	}

	public AxisAlignedBB(KList<Location> rPoints)
	{
		this(new AlignedPoint(rPoints.get(0).toVector()), new AlignedPoint(rPoints.get(0).toVector()));

		for(Location i : rPoints)
		{
			xa = i.getX() < xa ? i.getX() : xa;
			ya = i.getY() < ya ? i.getY() : ya;
			za = i.getZ() < za ? i.getZ() : za;
			xb = i.getX() > xb ? i.getX() : xb;
			yb = i.getY() > yb ? i.getY() : yb;
			zb = i.getZ() > zb ? i.getZ() : zb;
		}
	}

	public Axis getThinAxis()
	{
		if(sizeX() < sizeZ() && sizeX() < sizeY())
		{
			return Axis.X;
		}

		if(sizeY() < sizeZ() && sizeY() < sizeX())
		{
			return Axis.Y;
		}

		if(sizeZ() < sizeX() && sizeZ() < sizeY())
		{
			return Axis.Z;
		}

		return null;
	}

	public AxisAlignedBB(Location location)
	{
		this(location.toVector(), location.toVector());
	}

	public AxisAlignedBB(AxisAlignedBB region)
	{
		this(region.min(), region.max());
	}

	public AxisAlignedBB(Vector min, Vector max)
	{
		this(new AlignedPoint(min), new AlignedPoint(max));
	}

	public void encapsulate(AxisAlignedBB b)
	{
		encapsulate(new KList<Vector>().qadd(b.min()).qadd(b.max()));
	}

	public Collection<Entity> getEntities(World w)
	{
		return w.getNearbyEntities(center().toLocation(w), sizeX() / 2D, sizeZ() / 2D, sizeZ() / 2D);
	}

	public void encapsulate(KList<Vector> b)
	{
		for(Vector i : b)
		{
			xa = i.getX() < xa ? i.getX() : xa;
			ya = i.getY() < ya ? i.getY() : ya;
			za = i.getZ() < za ? i.getZ() : za;
			xb = i.getX() > xb ? i.getX() : xb;
			yb = i.getY() > yb ? i.getY() : yb;
			zb = i.getZ() > zb ? i.getZ() : zb;
		}
	}

	public Vector getCornerVector(Direction x, Direction y, Direction z)
	{
		assert x.getAxis().equals(Axis.X) : " X direction must be on the X axis.";
		assert x.getAxis().equals(Axis.Y) : " Y direction must be on the Y axis.";
		assert x.getAxis().equals(Axis.Z) : " Z direction must be on the Z axis.";
		return new Vector(x.x() == 1 ? xb : xa, y.y() == 1 ? yb : ya, z.z() == 1 ? zb : za);
	}

	public Cuboid toCuboid(World world)
	{
		return new Cuboid(min().toLocation(world), max().toLocation(world));
	}

	public Vector random()
	{
		return new Vector(M.rand(xa, xb), M.rand(ya, yb), M.rand(za, zb));
	}

	public Vector center()
	{
		return min().add(max().subtract(min()).multiply(0.5));
	}

	public Vector max()
	{
		return new Vector(xb, yb, zb);
	}

	public Vector min()
	{
		return new Vector(xa, ya, za);
	}

	public AxisAlignedBB getFace(Direction d)
	{
		return getFace(d, 0);
	}

	public AxisAlignedBB getFace(Direction d, double depth)
	{
		switch(d.getAxis())
		{
			case X:
				return new AxisAlignedBB(d.x() == 1 ? xb : xa, d.x() == 1 ? xb : xa, ya, yb, za, zb);
			case Y:
				return new AxisAlignedBB(xa, xb, d.y() == 1 ? yb : ya, d.y() == 1 ? yb : ya, za, zb);
			case Z:
				return new AxisAlignedBB(xa, xb, ya, yb, d.z() == 1 ? zb : za, d.z() == 1 ? zb : za);
		}

		return this;
	}

	public boolean contains(Location p)
	{
		return p.getX() >= xa && p.getX() <= xb && p.getY() >= ya && p.getY() <= yb && p.getZ() >= za && p.getZ() <= zb;
	}

	public boolean contains(Vector p)
	{
		return p.getX() >= xa && p.getX() <= xb && p.getY() >= ya && p.getY() <= yb && p.getZ() >= za && p.getZ() <= zb;
	}

	public boolean contains(AlignedPoint p)
	{
		return p.getX() >= xa && p.getX() <= xb && p.getY() >= ya && p.getY() <= yb && p.getZ() >= za && p.getZ() <= zb;
	}

	public boolean intersects(AxisAlignedBB s)
	{
		return this.xb >= s.xa && this.yb >= s.ya && this.zb >= s.za && s.xb >= this.xa && s.yb >= this.ya && s.zb >= this.za;
	}

	public double sizeX()
	{
		return xb - xa;
	}

	public double sizeY()
	{
		return yb - ya;
	}

	public double sizeZ()
	{
		return zb - za;
	}

	public double volume()
	{
		return sizeX() * sizeY() * sizeZ();
	}
}
