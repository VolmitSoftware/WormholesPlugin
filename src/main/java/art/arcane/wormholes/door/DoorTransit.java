package art.arcane.wormholes.door;

import java.util.Objects;

public record DoorTransit(
	DoorwayPlane sourcePlane,
	DoorwayCrossing.Direction direction,
	float yaw,
	float pitch)
{
	public DoorTransit
	{
		Objects.requireNonNull(sourcePlane, "sourcePlane");
		Objects.requireNonNull(direction, "direction");
		if(!Float.isFinite(yaw) || !Float.isFinite(pitch))
		{
			throw new IllegalArgumentException("Transit orientation must be finite");
		}
	}
}
