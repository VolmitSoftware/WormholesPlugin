package art.arcane.wormholes.door;

import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PocketEscapePolicyTest
{
	private static final PocketLayout LAYOUT = new PocketLayout(new PocketSpace(
		UUID.randomUUID(),
		PocketBinding.personal(UUID.randomUUID()),
		0L,
		8,
		128,
		8));

	@Test
	void everyInteriorBlockIsSafe()
	{
		for(int x = LAYOUT.minX(); x <= LAYOUT.maxX(); x += 7)
		{
			for(int y = LAYOUT.minY(); y <= LAYOUT.maxY(); y += 7)
			{
				for(int z = LAYOUT.minZ(); z <= LAYOUT.maxZ(); z += 7)
				{
					assertFalse(PocketEscapePolicy.isEscaped(LAYOUT, x, y, z));
				}
			}
		}
		assertFalse(PocketEscapePolicy.isEscaped(LAYOUT, LAYOUT.maxX(), LAYOUT.maxY(), LAYOUT.maxZ()));
	}

	@Test
	void doorwayOvershootWithinTheLateralMarginIsForgiven()
	{
		int centerY = LAYOUT.minY() + 2;
		assertFalse(PocketEscapePolicy.isEscaped(LAYOUT, LAYOUT.maxX() + 1, centerY, LAYOUT.maxZ()));
		assertFalse(PocketEscapePolicy.isEscaped(LAYOUT, LAYOUT.minX() - 1, centerY, LAYOUT.minZ()));
		assertFalse(PocketEscapePolicy.isEscaped(LAYOUT, LAYOUT.minX(), centerY, LAYOUT.maxZ() + 1));
		assertFalse(PocketEscapePolicy.isEscaped(LAYOUT, LAYOUT.minX(), centerY, LAYOUT.minZ() - 1));
	}

	@Test
	void clearingTheLateralMarginIsAnEscape()
	{
		int centerY = LAYOUT.minY() + 2;
		assertTrue(PocketEscapePolicy.isEscaped(LAYOUT, LAYOUT.maxX() + 2, centerY, LAYOUT.maxZ()));
		assertTrue(PocketEscapePolicy.isEscaped(LAYOUT, LAYOUT.minX() - 2, centerY, LAYOUT.minZ()));
		assertTrue(PocketEscapePolicy.isEscaped(LAYOUT, LAYOUT.minX(), centerY, LAYOUT.maxZ() + 2));
		assertTrue(PocketEscapePolicy.isEscaped(LAYOUT, LAYOUT.minX(), centerY, LAYOUT.minZ() - 2));
	}

	@Test
	void verticalBreachesAreImmediateEscapes()
	{
		int centerX = LAYOUT.minX() + 16;
		int centerZ = LAYOUT.minZ() + 16;
		assertTrue(PocketEscapePolicy.isEscaped(LAYOUT, centerX, LAYOUT.maxY() + 1, centerZ));
		assertTrue(PocketEscapePolicy.isEscaped(LAYOUT, centerX, LAYOUT.minY() - 1, centerZ));
		assertFalse(PocketEscapePolicy.isEscaped(LAYOUT, centerX, LAYOUT.maxY(), centerZ));
		assertFalse(PocketEscapePolicy.isEscaped(LAYOUT, centerX, LAYOUT.minY(), centerZ));
	}

	@Test
	void farVoidPositionsAreEscapes()
	{
		assertTrue(PocketEscapePolicy.isEscaped(LAYOUT, LAYOUT.maxX() + 500, 128, LAYOUT.maxZ() + 500));
		assertTrue(PocketEscapePolicy.isEscaped(LAYOUT, LAYOUT.minX(), -64, LAYOUT.minZ()));
	}

	@Test
	void nullLayoutIsRejected()
	{
		assertThrows(NullPointerException.class, () -> PocketEscapePolicy.isEscaped(null, 0, 0, 0));
	}
}
