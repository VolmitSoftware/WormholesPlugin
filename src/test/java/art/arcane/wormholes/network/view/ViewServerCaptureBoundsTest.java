package art.arcane.wormholes.network.view;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

import org.bukkit.util.BoundingBox;
import org.junit.jupiter.api.Test;

public final class ViewServerCaptureBoundsTest
{
	@Test
	public void clippedBoundsNeverCrossTheirOwningChunk()
	{
		BoundingBox bounds = new BoundingBox(-20.0D, -64.0D, -20.0D, 40.0D, 320.0D, 40.0D);
		for(int chunkX = -2; chunkX <= 2; chunkX++)
		{
			for(int chunkZ = -2; chunkZ <= 2; chunkZ++)
			{
				BoundingBox clipped = ViewServer.captureBoundsForChunk(bounds, chunkX, chunkZ);
				assertNotNull(clipped);
				assertEquals(chunkX, blockToChunk(clipped.getMinX()));
				assertEquals(chunkX, blockToChunk(clipped.getMaxX()));
				assertEquals(chunkZ, blockToChunk(clipped.getMinZ()));
				assertEquals(chunkZ, blockToChunk(clipped.getMaxZ()));
				assertEquals(-64.0D, clipped.getMinY());
				assertEquals(320.0D, clipped.getMaxY());
			}
		}
	}

	@Test
	public void exactPositiveAndNegativeBoundariesStayOnTheirRequestedSides()
	{
		BoundingBox bounds = new BoundingBox(-16.0D, 0.0D, 0.0D, 32.0D, 1.0D, 16.0D);

		assertEquals(-1, blockToChunk(ViewServer.captureBoundsForChunk(bounds, -1, 0).getMaxX()));
		assertEquals(0, blockToChunk(ViewServer.captureBoundsForChunk(bounds, 0, 0).getMaxX()));
		assertEquals(1, blockToChunk(ViewServer.captureBoundsForChunk(bounds, 1, 0).getMaxX()));
		assertNull(ViewServer.captureBoundsForChunk(bounds, 2, 0));
	}

	private static int blockToChunk(double coordinate)
	{
		return ((int) Math.floor(coordinate)) >> 4;
	}
}
