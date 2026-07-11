package art.arcane.wormholes.door;

import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.block.data.Bisected;
import org.bukkit.block.data.type.Door;

/**
 * A single observation of a real vanilla door.
 *
 * <p>{@link Door#isOpen()} is the only authority for whether the doorway is
 * currently usable. The powered flag is retained for diagnostics and lifecycle
 * handling; it never grants traversal by itself.</p>
 */
public record VanillaDoorSnapshot(
	UUID worldId,
	DoorwayPlane plane,
	Door.Hinge hinge,
	boolean open,
	boolean powered)
{
	public VanillaDoorSnapshot
	{
		Objects.requireNonNull(worldId, "worldId");
		Objects.requireNonNull(plane, "plane");
		Objects.requireNonNull(hinge, "hinge");
	}

	public static VanillaDoorSnapshot fromBlockData(
		UUID worldId,
		int blockX,
		int blockY,
		int blockZ,
		Door door)
	{
		Objects.requireNonNull(door, "door");
		return new VanillaDoorSnapshot(
			worldId,
			DoorwayPlane.fromBlockData(blockX, blockY, blockZ, door),
			door.getHinge(),
			door.isOpen(),
			door.isPowered());
	}

	/**
	 * Captures both halves of an in-world door and normalizes the result to its
	 * lower block. An incomplete or mismatched two-block structure is rejected.
	 */
	public static Optional<VanillaDoorSnapshot> capture(Block candidate)
	{
		Objects.requireNonNull(candidate, "candidate");
		if(!(candidate.getBlockData() instanceof Door candidateDoor))
		{
			return Optional.empty();
		}

		Block lowerBlock = candidateDoor.getHalf() == Bisected.Half.TOP
			? candidate.getRelative(BlockFace.DOWN)
			: candidate;
		if(!(lowerBlock.getBlockData() instanceof Door lowerDoor)
			|| lowerDoor.getHalf() != Bisected.Half.BOTTOM)
		{
			return Optional.empty();
		}

		Block upperBlock = lowerBlock.getRelative(BlockFace.UP);
		if(upperBlock.getType() != lowerBlock.getType()
			|| !(upperBlock.getBlockData() instanceof Door upperDoor)
			|| upperDoor.getHalf() != Bisected.Half.TOP)
		{
			return Optional.empty();
		}

		DoorwayPlane plane = new DoorwayPlane(
			lowerBlock.getX(),
			lowerBlock.getY(),
			lowerBlock.getZ(),
			lowerDoor.getFacing());
		return Optional.of(new VanillaDoorSnapshot(
			lowerBlock.getWorld().getUID(),
			plane,
			upperDoor.getHinge(),
			lowerDoor.isOpen(),
			upperDoor.isPowered()));
	}
}
