package art.arcane.wormholes.geometry;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.List;

import org.bukkit.Location;
import org.bukkit.World;
import org.junit.jupiter.api.Test;

public final class RaycastTest
{
	@Test
	public void samplesBothEndpointsWithoutExceedingJumpSize()
	{
		World world = world();
		Location source = new Location(world, 1.0D, 2.0D, 3.0D);
		Location destination = new Location(world, 3.5D, 4.0D, 6.0D);
		List<Location> samples = new ArrayList<Location>();

		Raycast raycast = new Raycast(source, destination, 1.0D)
		{
			@Override
			public boolean shouldContinue(Location location)
			{
				samples.add(location.clone());
				return true;
			}
		};

		assertTrue(raycast.hadSuccess());
		assertEquals(source, samples.get(0));
		assertEquals(destination, samples.get(samples.size() - 1));
		for(int i = 1; i < samples.size(); i++)
		{
			assertTrue(samples.get(i - 1).distance(samples.get(i)) <= 1.0D + 1.0E-9D);
		}
	}

	@Test
	public void zeroLengthSegmentSamplesItsSinglePointOnce()
	{
		Location point = new Location(world(), 7.0D, 8.0D, 9.0D);
		List<Location> samples = new ArrayList<Location>();

		Raycast raycast = new Raycast(point, point.clone(), 0.5D)
		{
			@Override
			public boolean shouldContinue(Location location)
			{
				samples.add(location.clone());
				return true;
			}
		};

		assertTrue(raycast.hadSuccess());
		assertEquals(1, samples.size());
		assertEquals(point, samples.get(0));
	}

	@Test
	public void rejectsNonPositiveJumpSizes()
	{
		World world = world();
		Location source = new Location(world, 0.0D, 0.0D, 0.0D);
		Location destination = new Location(world, 1.0D, 0.0D, 0.0D);

		assertThrows(IllegalArgumentException.class, () -> new RecordingRaycast(source, destination, 0.0D));
		assertThrows(IllegalArgumentException.class, () -> new RecordingRaycast(source, destination, -0.25D));
	}

	private static World world()
	{
		return (World) Proxy.newProxyInstance(World.class.getClassLoader(), new Class<?>[] { World.class }, (proxy, method, arguments) -> switch(method.getName())
		{
			case "toString" -> "RaycastTestWorld";
			case "hashCode" -> Integer.valueOf(System.identityHashCode(proxy));
			case "equals" -> Boolean.valueOf(proxy == arguments[0]);
			default -> throw new UnsupportedOperationException(method.getName());
		});
	}

	private static final class RecordingRaycast extends Raycast
	{
		private RecordingRaycast(Location source, Location destination, double jumpSize)
		{
			super(source, destination, jumpSize);
		}

		@Override
		public boolean shouldContinue(Location location)
		{
			return true;
		}
	}
}
