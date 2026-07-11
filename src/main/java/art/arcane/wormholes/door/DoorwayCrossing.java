package art.arcane.wormholes.door;

/**
 * The exact point where a movement segment intersects a dimensional doorway.
 */
public record DoorwayCrossing(
	DoorVec3 point,
	double segmentFraction,
	double lateralOffset,
	double verticalOffset,
	Direction direction)
{
	public DoorwayCrossing
	{
		if(point == null || direction == null)
		{
			throw new IllegalArgumentException("Crossing point and direction are required");
		}
		if(!Double.isFinite(segmentFraction) || segmentFraction < 0.0D || segmentFraction > 1.0D)
		{
			throw new IllegalArgumentException("Segment fraction must be between zero and one");
		}
		if(!Double.isFinite(lateralOffset) || !Double.isFinite(verticalOffset))
		{
			throw new IllegalArgumentException("Crossing offsets must be finite");
		}
	}

	public enum Direction
	{
		FRONT_TO_BACK(1),
		BACK_TO_FRONT(-1);

		private final int entrySideSign;

		Direction(int entrySideSign)
		{
			this.entrySideSign = entrySideSign;
		}

		public int entrySideSign()
		{
			return entrySideSign;
		}

		public int exitSideSign()
		{
			return -entrySideSign;
		}
	}
}
