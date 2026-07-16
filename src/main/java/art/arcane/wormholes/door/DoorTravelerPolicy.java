package art.arcane.wormholes.door;

final class DoorTravelerPolicy
{
	private static final double MAX_WIDTH = 1.0D;
	private static final double MAX_HEIGHT = 2.0D;

	private DoorTravelerPolicy()
	{
	}

	static boolean canEnter(
		DoorKind kind,
		boolean player,
		boolean mobileEntity,
		boolean boss,
		boolean complex,
		boolean constrained,
		double width,
		double height)
	{
		if(player)
		{
			return true;
		}
		return kind == DoorKind.PAIR
			&& mobileEntity
			&& !boss
			&& !complex
			&& !constrained
			&& Double.isFinite(width)
			&& width > 0.0D
			&& width <= MAX_WIDTH
			&& Double.isFinite(height)
			&& height > 0.0D
			&& height <= MAX_HEIGHT;
	}
}
