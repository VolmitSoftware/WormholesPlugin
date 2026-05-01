package art.arcane.wormholes.geometry;

import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.util.NumberConversions;

public class GeoPoint
{

	private double x;
	private double y;
	private double z;

	public double getX()
	{
		return x;
	}

	public void setX(double x)
	{
		this.x = x;
	}

	public double getY()
	{
		return y;
	}

	public void setY(double y)
	{
		this.y = y;
	}

	public double getZ()
	{
		return z;
	}

	public void setZ(double z)
	{
		this.z = z;
	}

	public GeoPoint()
	{
	}

	public GeoPoint(double x, double y, double z)
	{
		this.x = x;
		this.y = y;
		this.z = z;
	}

	public Location toLocation(World w)
	{
		return new Location(w, x, y, z);
	}

	public double distanceSquared(GeoPoint o)
	{
		return NumberConversions.square(this.x - o.x) + NumberConversions.square(this.y - o.y) + NumberConversions.square(this.z - o.z);
	}

	public GeoPoint(Location l)
	{
		this(l.getX(), l.getY(), l.getZ());
	}

	public static GeoPoint Add(GeoPoint p0, GeoPoint p1)
	{
		return new GeoPoint(p0.x + p1.x, p0.y + p1.y, p0.z + p1.z);
	}
}