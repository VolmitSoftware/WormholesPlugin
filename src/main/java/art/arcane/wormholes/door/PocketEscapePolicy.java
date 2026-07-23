package art.arcane.wormholes.door;

import java.util.Objects;

/**
 * Decides when a traveler inside the pocket world has broken out of a pocket
 * room and must be ejected back to their return point.
 */
public final class PocketEscapePolicy
{
	public static final int LATERAL_MARGIN_BLOCKS = 1;
	public static final int VERTICAL_MARGIN_BLOCKS = 0;

	private PocketEscapePolicy()
	{
	}

	public static boolean isEscaped(PocketLayout layout, int x, int y, int z)
	{
		Objects.requireNonNull(layout, "layout");
		return x < layout.minX() - LATERAL_MARGIN_BLOCKS
			|| x > layout.maxX() + LATERAL_MARGIN_BLOCKS
			|| y < layout.minY() - VERTICAL_MARGIN_BLOCKS
			|| y > layout.maxY() + VERTICAL_MARGIN_BLOCKS
			|| z < layout.minZ() - LATERAL_MARGIN_BLOCKS
			|| z > layout.maxZ() + LATERAL_MARGIN_BLOCKS;
	}
}
