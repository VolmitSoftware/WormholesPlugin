package art.arcane.wormholes.portal;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;

import java.util.EnumSet;
import java.util.Locale;
import java.util.Set;

import org.junit.jupiter.api.Test;

public final class BlackoutColorTest
{
	@Test
	public void nextCyclesThroughAllSixteenColorsExactlyOnce()
	{
		assertEquals(16, BlackoutColor.values().length);

		for(BlackoutColor start : BlackoutColor.values())
		{
			Set<BlackoutColor> visited = EnumSet.noneOf(BlackoutColor.class);
			BlackoutColor current = start;

			for(int i = 0; i < BlackoutColor.values().length; i++)
			{
				assertEquals(true, visited.add(current));
				current = current.next();
			}

			assertEquals(BlackoutColor.values().length, visited.size());
			assertSame(start, current);
		}
	}

	@Test
	public void blockStateFollowsConcreteNamingConvention()
	{
		for(BlackoutColor color : BlackoutColor.values())
		{
			assertEquals("minecraft:" + color.name().toLowerCase(Locale.ROOT) + "_concrete", color.blockState());
			assertEquals(color.name() + "_CONCRETE", color.materialName());
		}
	}

	@Test
	public void fromNameParsesNamesCaseInsensitivelyAndFallsBack()
	{
		assertSame(BlackoutColor.BLACK, BlackoutColor.fromName("black", BlackoutColor.WHITE));
		assertSame(BlackoutColor.LIME, BlackoutColor.fromName(" Lime ", BlackoutColor.WHITE));
		assertSame(BlackoutColor.RED, BlackoutColor.fromName("nope", BlackoutColor.RED));
		assertSame(BlackoutColor.RED, BlackoutColor.fromName(null, BlackoutColor.RED));
	}

	@Test
	public void displayNameIsHumanReadable()
	{
		assertEquals("Black Concrete", BlackoutColor.BLACK.displayName());
		assertEquals("Light Blue Concrete", BlackoutColor.LIGHT_BLUE.displayName());
	}
}
