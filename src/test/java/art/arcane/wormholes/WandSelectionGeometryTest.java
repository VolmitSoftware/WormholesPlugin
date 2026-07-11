package art.arcane.wormholes;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

public final class WandSelectionGeometryTest
{
	@Test
	public void selectionBoundsNormalizeCornerOrder()
	{
		int[] a = new int[] { 10, 70, -5 };
		int[] b = new int[] { 4, 64, 3 };
		assertArrayEquals(new int[] { 4, 64, -5 }, WandSelectionManager.selectionMin(a, b));
		assertArrayEquals(new int[] { 10, 70, 3 }, WandSelectionManager.selectionMax(a, b));
	}

	@Test
	public void flatAxisFindsThePlaneNormal()
	{
		assertEquals(2, WandSelectionManager.flatAxis(new int[] { 0, 64, 8 }, new int[] { 5, 70, 8 }));
		assertEquals(1, WandSelectionManager.flatAxis(new int[] { 0, 64, 0 }, new int[] { 5, 64, 9 }));
		assertEquals(0, WandSelectionManager.flatAxis(new int[] { 3, 60, 0 }, new int[] { 3, 70, 9 }));
		assertEquals(-1, WandSelectionManager.flatAxis(new int[] { 0, 64, 0 }, new int[] { 5, 70, 9 }));
	}

	@Test
	public void cellCountCoversInclusiveBounds()
	{
		assertEquals(1L, WandSelectionManager.cellCount(new int[] { 1, 1, 1 }, new int[] { 1, 1, 1 }));
		assertEquals(15L, WandSelectionManager.cellCount(new int[] { 0, 64, 8 }, new int[] { 4, 66, 8 }));
	}

	@Test
	public void paneBoxIsThinOnTheNormalAndStretchedOnThePlane()
	{
		float[] box = WandSelectionManager.paneBox(new int[] { 0, 64, 8 }, new int[] { 4, 66, 8 }, 2, 0.12f, 0.02f);
		assertEquals(5.0f - 0.04f, box[0], 0.0001f);
		assertEquals(3.0f - 0.04f, box[1], 0.0001f);
		assertEquals(0.12f, box[2], 0.0001f);
		assertEquals(0.02f, box[3], 0.0001f);
		assertEquals(0.02f, box[4], 0.0001f);
		assertEquals(0.44f, box[5], 0.0001f);
	}

	@Test
	public void paneBoxWithoutNormalCoversTheWholeRegion()
	{
		float[] box = WandSelectionManager.paneBox(new int[] { 0, 0, 0 }, new int[] { 1, 2, 3 }, -1, 0.12f, 0.02f);
		assertEquals(2.0f - 0.04f, box[0], 0.0001f);
		assertEquals(3.0f - 0.04f, box[1], 0.0001f);
		assertEquals(4.0f - 0.04f, box[2], 0.0001f);
	}

	@Test
	public void rayHitsBoxStraightOn()
	{
		assertTrue(WandSelectionManager.rayIntersectsBox(0.5D, 65.0D, 0.0D, 0.0D, 0.0D, 1.0D, 0.0D, 64.0D, 8.0D, 5.0D, 67.0D, 9.0D, 64.0D));
	}

	@Test
	public void rayMissesBoxPointingAway()
	{
		assertFalse(WandSelectionManager.rayIntersectsBox(0.5D, 65.0D, 0.0D, 0.0D, 0.0D, -1.0D, 0.0D, 64.0D, 8.0D, 5.0D, 67.0D, 9.0D, 64.0D));
	}

	@Test
	public void rayMissesBoxBeyondRange()
	{
		assertFalse(WandSelectionManager.rayIntersectsBox(0.5D, 65.0D, 0.0D, 0.0D, 0.0D, 1.0D, 0.0D, 64.0D, 80.0D, 5.0D, 67.0D, 81.0D, 64.0D));
	}

	@Test
	public void rayParallelOutsideSlabMisses()
	{
		assertFalse(WandSelectionManager.rayIntersectsBox(0.5D, 70.0D, 0.0D, 0.0D, 0.0D, 1.0D, 0.0D, 64.0D, 8.0D, 5.0D, 67.0D, 9.0D, 64.0D));
	}
}
