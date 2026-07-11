package art.arcane.wormholes.door;

import java.util.Objects;
import java.util.Optional;

import org.bukkit.block.BlockFace;
import org.bukkit.block.data.Bisected;
import org.bukkit.block.data.type.Door;

/**
 * The two-block-high aperture through the center of a vanilla door block.
 *
 * <p>The plane intentionally uses the door's closed facing instead of its open
 * slab position. A door may swing left or right, but the opening that a player
 * walks through remains the same one-block-wide threshold.</p>
 */
public record DoorwayPlane(int blockX, int blockY, int blockZ, BlockFace facing)
{
	private static final double EPSILON = 1.0E-7D;

	public DoorwayPlane
	{
		Objects.requireNonNull(facing, "facing");
		if(!isCardinal(facing))
		{
			throw new IllegalArgumentException("A doorway must face north, south, east, or west");
		}
	}

	/**
	 * Builds a plane from live vanilla {@link Door} block data. The supplied
	 * coordinates may point at either half; the result is always anchored to the
	 * lower half.
	 */
	public static DoorwayPlane fromBlockData(int blockX, int blockY, int blockZ, Door door)
	{
		Objects.requireNonNull(door, "door");
		int lowerY = door.getHalf() == Bisected.Half.TOP ? blockY - 1 : blockY;
		return new DoorwayPlane(blockX, lowerY, blockZ, door.getFacing());
	}

	public DoorVec3 center()
	{
		return new DoorVec3(blockX + 0.5D, blockY + 1.0D, blockZ + 0.5D);
	}

	/**
	 * Intersects a player's movement segment with the doorway aperture.
	 *
	 * <p>Merely moving along the plane is not a crossing. Starting exactly on the
	 * plane and moving away is also ignored: the movement that arrived at the
	 * plane is the crossing event, which prevents stationary players from being
	 * pulled through when a door opens around them.</p>
	 */
	public Optional<DoorwayCrossing> crossing(DoorVec3 from, DoorVec3 to)
	{
		Objects.requireNonNull(from, "from");
		Objects.requireNonNull(to, "to");

		DoorVec3 center = center();
		double normalX = facing.getModX();
		double normalZ = facing.getModZ();
		double fromDistance = signedDistance(from, center, normalX, normalZ);
		double toDistance = signedDistance(to, center, normalX, normalZ);
		double normalTravel = toDistance - fromDistance;

		if(Math.abs(normalTravel) <= EPSILON)
		{
			return Optional.empty();
		}

		double fraction = -fromDistance / normalTravel;
		if(fraction <= EPSILON || fraction > 1.0D + EPSILON)
		{
			return Optional.empty();
		}

		fraction = Math.min(1.0D, fraction);
		DoorVec3 point = from.interpolate(to, fraction);
		double tangentX = -normalZ;
		double tangentZ = normalX;
		double lateralOffset = ((point.x() - center.x()) * tangentX) + ((point.z() - center.z()) * tangentZ);
		double verticalOffset = point.y() - blockY;

		if(Math.abs(lateralOffset) > 0.5D + EPSILON
			|| verticalOffset < -EPSILON
			|| verticalOffset > 2.0D + EPSILON)
		{
			return Optional.empty();
		}

		DoorwayCrossing.Direction direction = fromDistance > 0.0D
			? DoorwayCrossing.Direction.FRONT_TO_BACK
			: DoorwayCrossing.Direction.BACK_TO_FRONT;
		return Optional.of(new DoorwayCrossing(point, fraction, lateralOffset, verticalOffset, direction));
	}

	public double signedDistance(DoorVec3 point)
	{
		Objects.requireNonNull(point, "point");
		DoorVec3 center = center();
		return signedDistance(point, center, facing.getModX(), facing.getModZ());
	}

	public DoorVec3 entrySidePoint(DoorwayCrossing.Direction direction, double offset)
	{
		Objects.requireNonNull(direction, "direction");
		return sidePoint(direction.entrySideSign(), offset);
	}

	public DoorVec3 exitSidePoint(DoorwayCrossing.Direction direction, double offset)
	{
		Objects.requireNonNull(direction, "direction");
		return sidePoint(direction.exitSideSign(), offset);
	}

	public float rotateYawTo(DoorwayPlane destination, float yaw)
	{
		Objects.requireNonNull(destination, "destination");
		if(!Float.isFinite(yaw))
		{
			throw new IllegalArgumentException("Yaw must be finite");
		}

		return normalizeYaw(yaw + facingYaw(destination.facing) - facingYaw(facing));
	}

	private DoorVec3 sidePoint(int sign, double offset)
	{
		if(!Double.isFinite(offset) || offset <= 0.0D)
		{
			throw new IllegalArgumentException("Doorway side offset must be finite and positive");
		}

		return new DoorVec3(
			blockX + 0.5D + (facing.getModX() * offset * sign),
			blockY,
			blockZ + 0.5D + (facing.getModZ() * offset * sign));
	}

	private static float facingYaw(BlockFace face)
	{
		return switch(face)
		{
			case SOUTH -> 0.0F;
			case WEST -> 90.0F;
			case NORTH -> 180.0F;
			case EAST -> -90.0F;
			default -> throw new IllegalArgumentException("A doorway must face north, south, east, or west");
		};
	}

	private static float normalizeYaw(float yaw)
	{
		float normalized = yaw % 360.0F;
		if(normalized >= 180.0F)
		{
			normalized -= 360.0F;
		}
		else if(normalized < -180.0F)
		{
			normalized += 360.0F;
		}
		return normalized;
	}

	private static double signedDistance(DoorVec3 point, DoorVec3 center, double normalX, double normalZ)
	{
		return ((point.x() - center.x()) * normalX) + ((point.z() - center.z()) * normalZ);
	}

	private static boolean isCardinal(BlockFace facing)
	{
		return facing == BlockFace.NORTH
			|| facing == BlockFace.SOUTH
			|| facing == BlockFace.EAST
			|| facing == BlockFace.WEST;
	}
}
