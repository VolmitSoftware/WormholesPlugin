package art.arcane.wormholes.door;

/**
 * Immutable, server-independent point/vector used by dimensional-door geometry.
 */
public record DoorVec3(double x, double y, double z)
{
	public DoorVec3
	{
		if(!Double.isFinite(x) || !Double.isFinite(y) || !Double.isFinite(z))
		{
			throw new IllegalArgumentException("Door coordinates must be finite");
		}
	}

	public DoorVec3 interpolate(DoorVec3 other, double fraction)
	{
		if(other == null)
		{
			throw new IllegalArgumentException("Other point cannot be null");
		}
		if(!Double.isFinite(fraction))
		{
			throw new IllegalArgumentException("Interpolation fraction must be finite");
		}

		return new DoorVec3(
			x + ((other.x - x) * fraction),
			y + ((other.y - y) * fraction),
			z + ((other.z - z) * fraction));
	}
}
