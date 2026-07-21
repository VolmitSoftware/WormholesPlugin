package art.arcane.wormholes.portal.rtp;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.reflect.Proxy;
import java.util.List;
import java.util.UUID;

import org.bukkit.NamespacedKey;
import org.bukkit.World;
import org.junit.jupiter.api.Test;

public final class RtpSamplerTest
{
	@Test
	public void samplingIsDeterministicForSeedGenerationAndAttempt()
	{
		RtpSettings settings = settings(100, 500, RtpVerticalMode.SURFACE, -20, 80, 30);
		RtpSampler sampler = new RtpSampler(128.25D, -64.75D, 0x5A17C0DEL);

		RtpDestination first = sampler.sample(settings, 7L, 3);
		RtpDestination repeated = sampler.sample(settings, 7L, 3);
		RtpDestination nextAttempt = sampler.sample(settings, 7L, 4);

		assertEquals(first, repeated);
		assertNotEquals(first, nextAttempt);
		assertEquals(7L, first.generation());
		assertEquals(3, first.attempt());
	}

	@Test
	public void sampledColumnsStayInsideAreaUniformAnnulus()
	{
		int minimumRadius = 1_000;
		int maximumRadius = 2_000;
		RtpSettings settings = settings(minimumRadius, maximumRadius, RtpVerticalMode.SURFACE, -20, 80, 30);
		double centerX = 12_345.25D;
		double centerZ = -54_321.75D;
		RtpSampler sampler = new RtpSampler(centerX, centerZ, 991_827_341L);
		double normalizedSquaredRadiusTotal = 0.0D;
		int samples = 10_000;

		for(int attempt = 0; attempt < samples; attempt++)
		{
			RtpDestination destination = sampler.sample(settings, 19L, attempt);
			double x = destination.blockX() + 0.5D - centerX;
			double z = destination.blockZ() + 0.5D - centerZ;
			double squaredRadius = x * x + z * z;
			double radius = Math.sqrt(squaredRadius);
			assertTrue(radius >= minimumRadius - 1.0D);
			assertTrue(radius <= maximumRadius + 1.0D);
			normalizedSquaredRadiusTotal += (squaredRadius - (double) minimumRadius * minimumRadius)
					/ ((double) maximumRadius * maximumRadius - (double) minimumRadius * minimumRadius);
		}

		double mean = normalizedSquaredRadiusTotal / samples;
		assertTrue(mean > 0.48D && mean < 0.52D, "normalized squared-radius mean was " + mean);
	}

	@Test
	public void explicitCenterAndTargetWorldArePreserved()
	{
		World source = world("overworld", -64, 320, 63);
		World target = world("the_end", 0, 256, 63);
		RtpSettings settings = RtpSettings.builder(source)
				.targetWorld(target)
				.radii(32, 64)
				.build();
		RtpSampler sampler = new RtpSampler(2_000.5D, -3_000.5D, 42L);

		RtpDestination destination = sampler.sample(settings, 2L, 1);
		double distance = Math.hypot(destination.blockX() + 0.5D - 2_000.5D, destination.blockZ() + 0.5D + 3_000.5D);

		assertEquals("minecraft:the_end", destination.worldKey());
		assertTrue(distance >= 31.0D && distance <= 65.0D);
	}

	@Test
	public void surfaceSamplingStartsAtUpperBoundAndUsesBoundedDescendingProbes()
	{
		RtpSettings settings = settings(32, 64, RtpVerticalMode.SURFACE, 10, 15, 12);
		RtpSampler sampler = new RtpSampler(0.0D, 0.0D, 1L);

		RtpDestination destination = sampler.sample(settings, 0L, 0);
		List<Integer> probes = sampler.feetYProbeOrder(settings, 13);
		List<Integer> clampedHighProbes = sampler.feetYProbeOrder(settings, 100);
		List<Integer> clampedLowProbes = sampler.feetYProbeOrder(settings, -100);

		assertEquals(15, destination.feetY());
		assertEquals(List.of(13, 12, 11, 10), probes);
		assertEquals(List.of(15, 14, 13, 12, 11, 10), clampedHighProbes);
		assertEquals(List.of(10), clampedLowProbes);
	}

	@Test
	public void preferredAverageAlternatesAroundClampedPreferredYWithinHardBounds()
	{
		RtpSettings settings = settings(32, 64, RtpVerticalMode.PREFERRED_AVERAGE, 10, 15, 12);
		RtpSettings clampedSettings = settings(32, 64, RtpVerticalMode.PREFERRED_AVERAGE, 10, 15, 100);
		RtpSampler sampler = new RtpSampler(0.0D, 0.0D, 1L);

		RtpDestination destination = sampler.sample(settings, 0L, 0);
		RtpDestination clampedDestination = sampler.sample(clampedSettings, 0L, 0);
		List<Integer> probes = sampler.feetYProbeOrder(settings, 14);
		List<Integer> clampedProbes = sampler.feetYProbeOrder(clampedSettings, 10);

		assertEquals(12, destination.feetY());
		assertEquals(List.of(12, 13, 11, 14, 10, 15), probes);
		assertEquals(15, clampedDestination.feetY());
		assertEquals(List.of(15, 14, 13, 12, 11, 10), clampedProbes);
	}

	private static RtpSettings settings(int minimumRadius, int maximumRadius, RtpVerticalMode verticalMode,
			int lowerY, int upperY, int preferredY)
	{
		World world = world("overworld", -64, 320, 63);
		return RtpSettings.builder(world)
				.radii(minimumRadius, maximumRadius)
				.verticalMode(verticalMode)
				.yBounds(lowerY, upperY)
				.preferredY(preferredY)
				.build();
	}

	private static World world(String key, int minimumHeight, int maximumHeight, int seaLevel)
	{
		NamespacedKey namespacedKey = NamespacedKey.minecraft(key);
		return (World) Proxy.newProxyInstance(World.class.getClassLoader(), new Class<?>[] { World.class }, (proxy, method, arguments) -> switch(method.getName())
		{
			case "getName" -> key;
			case "getKey" -> namespacedKey;
			case "getUID" -> UUID.nameUUIDFromBytes(namespacedKey.toString().getBytes());
			case "getMinHeight" -> Integer.valueOf(minimumHeight);
			case "getMaxHeight" -> Integer.valueOf(maximumHeight);
			case "getSeaLevel" -> Integer.valueOf(seaLevel);
			case "toString" -> "RtpSamplerTestWorld[" + namespacedKey + "]";
			case "hashCode" -> Integer.valueOf(System.identityHashCode(proxy));
			case "equals" -> Boolean.valueOf(proxy == arguments[0]);
			default -> throw new UnsupportedOperationException(method.getName());
		});
	}
}
