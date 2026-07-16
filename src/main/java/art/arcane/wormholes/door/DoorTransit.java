package art.arcane.wormholes.door;

import java.util.Objects;

public record DoorTransit(
	DoorwayPlane sourcePlane,
	DoorwayCrossing.Direction direction,
	float yaw,
	float pitch,
	double halfWidth,
	double height)
{
	public DoorTransit(DoorwayPlane sourcePlane, DoorwayCrossing.Direction direction, float yaw, float pitch)
	{
		this(sourcePlane, direction, yaw, pitch, 0.3D, 1.8D);
	}

	public DoorTransit
	{
		Objects.requireNonNull(sourcePlane, "sourcePlane");
		Objects.requireNonNull(direction, "direction");
		if(!Float.isFinite(yaw) || !Float.isFinite(pitch))
		{
			throw new IllegalArgumentException("Transit orientation must be finite");
		}
		if(!Double.isFinite(halfWidth) || halfWidth <= 0.0D
			|| !Double.isFinite(height) || height <= 0.0D)
		{
			throw new IllegalArgumentException("Traveler dimensions must be finite and positive");
		}
	}
}
