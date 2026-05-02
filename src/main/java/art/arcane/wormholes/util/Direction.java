package art.arcane.wormholes.util;

import org.bukkit.util.Vector;

import art.arcane.wormholes.util.Cuboid.CuboidDirection;
import art.arcane.volmlib.util.collection.KList;

/**
 * Directions
 *
 * @author cyberpwn
 */
public enum Direction
{
	U(0, 1, 0, CuboidDirection.Up),
	D(0, -1, 0, CuboidDirection.Down),
	N(0, 0, -1, CuboidDirection.North),
	S(0, 0, 1, CuboidDirection.South),
	E(1, 0, 0, CuboidDirection.East),
	W(-1, 0, 0, CuboidDirection.West);

	private int x;
	private int y;
	private int z;
	private CuboidDirection f;

	public float toYaw()
	{
		switch(this)
		{
			case S:
				return 0f;
			case W:
				return 90f;
			case N:
				return 180f;
			case E:
				return -90f;
			default:
				return 0f;
		}
	}

	public float yawDeltaTo(Direction other)
	{
		float delta = other.toYaw() - this.toYaw();
		while (delta > 180f) delta -= 360f;
		while (delta < -180f) delta += 360f;
		return delta;
	}

	@Override
	public String toString()
	{
		switch(this)
		{
			case D:
				return "Down";
			case E:
				return "East";
			case N:
				return "North";
			case S:
				return "South";
			case U:
				return "Up";
			case W:
				return "West";
		}

		return "?";
	}

	public boolean isVertical()
	{
		return equals(D) || equals(U);
	}

	public static Direction closest(Vector v)
	{
		return closest(v.getX(), v.getY(), v.getZ());
	}

	public static Direction closest(double x, double y, double z)
	{
		double ax = Math.abs(x);
		double ay = Math.abs(y);
		double az = Math.abs(z);

		if(ay >= ax && ay >= az)
		{
			return y >= 0.0D ? U : D;
		}

		if(ax >= az)
		{
			return x >= 0.0D ? E : W;
		}

		return z >= 0.0D ? S : N;
	}

	public static Direction closestSlow(Vector v)
	{
		double m = Double.MAX_VALUE;
		Direction s = null;

		for(Direction i : values())
		{
			Vector x = i.toVector();
			double g = x.distance(v);

			if(g < m)
			{
				m = g;
				s = i;
			}
		}

		return s;
	}

	public static Direction closest(Vector v, Direction... d)
	{
		double m = Double.MAX_VALUE;
		Direction s = null;

		for(Direction i : d)
		{
			Vector x = i.toVector();
			double g = x.distance(v);

			if(g < m)
			{
				m = g;
				s = i;
			}
		}

		return s;
	}

	public static Direction closest(Vector v, KList<Direction> d)
	{
		double m = Double.MAX_VALUE;
		Direction s = null;

		for(Direction i : d)
		{
			Vector x = i.toVector();
			double g = x.distance(v);

			if(g < m)
			{
				m = g;
				s = i;
			}
		}

		return s;
	}

	public Vector toVector()
	{
		return new Vector(x, y, z);
	}

	public boolean isCrooked(Direction to)
	{
		if(equals(to.reverse()))
		{
			return false;
		}

		if(equals(to))
		{
			return false;
		}

		return true;
	}

	private Direction(int x, int y, int z, CuboidDirection f)
	{
		this.x = x;
		this.y = y;
		this.z = z;
		this.f = f;
	}

	public Vector angle(Vector initial, Direction d)
	{
		double[] out = new double[3];
		rotateInto(initial.getX(), initial.getY(), initial.getZ(), d, out);
		return new Vector(out[0], out[1], out[2]);
	}

	public Direction reverse()
	{
		switch(this)
		{
			case D:
				return U;
			case E:
				return W;
			case N:
				return S;
			case S:
				return N;
			case U:
				return D;
			case W:
				return E;
			default:
				break;
		}

		return null;
	}

	public int x()
	{
		return x;
	}

	public int y()
	{
		return y;
	}

	public int z()
	{
		return z;
	}

	public CuboidDirection f()
	{
		return f;
	}

	public static KList<Direction> news()
	{
		return new KList<Direction>().qadd(N).qadd(E).qadd(W).qadd(S);
	}

	public static Direction getDirection(Vector v)
	{
		Vector k = VectorMath.triNormalize(v.clone().normalize());

		for(Direction i : udnews())
		{
			if(i.x == k.getBlockX() && i.y == k.getBlockY() && i.z == k.getBlockZ())
			{
				return i;
			}
		}

		return Direction.N;
	}

	public static KList<Direction> udnews()
	{
		return new KList<Direction>().qadd(U).qadd(D).qadd(N).qadd(E).qadd(W).qadd(S);
	}

	/**
	 * Get the directional value from the given byte from common directional blocks
	 * (MUST BE BETWEEN 0 and 5 INCLUSIVE)
	 *
	 * @param b
	 *            the byte
	 * @return the direction or null if the byte is outside of the inclusive range
	 *         0-5
	 */
	public static Direction fromByte(byte b)
	{
		if(b > 5 || b < 0)
		{
			return null;
		}

		if(b == 0)
		{
			return D;
		}

		else if(b == 1)
		{
			return U;
		}

		else if(b == 2)
		{
			return N;
		}

		else if(b == 3)
		{
			return S;
		}

		else if(b == 4)
		{
			return W;
		}

		else
		{
			return E;
		}
	}

	/**
	 * Get the byte value represented in some directional blocks
	 *
	 * @return the byte value
	 */
	public byte byteValue()
	{
		switch(this)
		{
			case D:
				return 0;
			case E:
				return 5;
			case N:
				return 2;
			case S:
				return 3;
			case U:
				return 1;
			case W:
				return 4;
			default:
				break;
		}

		return -1;
	}

	public Axis getAxis()
	{
		switch(this)
		{
			case D:
				return Axis.Y;
			case E:
				return Axis.X;
			case N:
				return Axis.Z;
			case S:
				return Axis.Z;
			case U:
				return Axis.Y;
			case W:
				return Axis.X;
		}

		return null;
	}

	public void rotateInto(double x, double y, double z, Direction to, double[] out3)
	{
		double right = x * rightX() + y * rightY() + z * rightZ();
		double up = x * upX() + y * upY() + z * upZ();
		double forward = x * this.x + y * this.y + z * this.z;
		out3[0] = (right * to.rightX()) + (up * to.upX()) + (forward * to.x);
		out3[1] = (right * to.rightY()) + (up * to.upY()) + (forward * to.y);
		out3[2] = (right * to.rightZ()) + (up * to.upZ()) + (forward * to.z);
	}

	private int rightX()
	{
		switch(this)
		{
			case N:
				return 1;
			case S:
			case U:
			case D:
				return -1;
			default:
				return 0;
		}
	}

	private int rightY()
	{
		return 0;
	}

	private int rightZ()
	{
		switch(this)
		{
			case E:
				return 1;
			case W:
				return -1;
			default:
				return 0;
		}
	}

	private int upX()
	{
		return 0;
	}

	private int upY()
	{
		return isVertical() ? 0 : 1;
	}

	private int upZ()
	{
		switch(this)
		{
			case U:
				return -1;
			case D:
				return 1;
			default:
				return 0;
		}
	}
}
