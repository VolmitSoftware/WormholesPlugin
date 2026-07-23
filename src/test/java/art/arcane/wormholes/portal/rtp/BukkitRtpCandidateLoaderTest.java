package art.arcane.wormholes.portal.rtp;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import java.util.function.IntPredicate;

import org.junit.jupiter.api.Test;

public final class BukkitRtpCandidateLoaderTest
{
	@Test
	public void netherColumnResolvesShelteredGroundBelowRoof()
	{
		IntPredicate support = y -> y == 100 || y >= 122;
		IntPredicate open = y -> y > 100 && y < 122;

		assertEquals(Integer.valueOf(101), BukkitRtpCandidateLoader.descendingSurfaceFeetY(121, 1, support, open));
	}

	@Test
	public void lavaSurfacesAreNeverSelected()
	{
		IntPredicate support = y -> y == 30;
		IntPredicate open = y -> y > 40 && y < 122;

		assertNull(BukkitRtpCandidateLoader.descendingSurfaceFeetY(121, 1, support, open));
	}

	@Test
	public void openColumnWithoutGroundResolvesNothing()
	{
		IntPredicate support = y -> false;
		IntPredicate open = y -> true;

		assertNull(BukkitRtpCandidateLoader.descendingSurfaceFeetY(121, 1, support, open));
	}

	@Test
	public void groundAboveTheProbeCeilingIsIgnored()
	{
		IntPredicate support = y -> y == 124;
		IntPredicate open = y -> y > 124;

		assertNull(BukkitRtpCandidateLoader.descendingSurfaceFeetY(121, 1, support, open));
	}

	@Test
	public void headroomMustBeTwoOpenBlocks()
	{
		IntPredicate support = y -> y == 100 || y == 102;
		IntPredicate open = y -> y == 101 || y > 102 && y < 122;

		assertEquals(Integer.valueOf(103), BukkitRtpCandidateLoader.descendingSurfaceFeetY(121, 1, support, open));
	}

	@Test
	public void probeRespectsTheLowerBound()
	{
		IntPredicate support = y -> y == 4;
		IntPredicate open = y -> y > 4;

		assertNull(BukkitRtpCandidateLoader.descendingSurfaceFeetY(121, 10, support, open));
	}
}
