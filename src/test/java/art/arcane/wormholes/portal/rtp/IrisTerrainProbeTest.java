package art.arcane.wormholes.portal.rtp;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import org.bukkit.World;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

public final class IrisTerrainProbeTest
{
	@BeforeEach
	public void resetFakes()
	{
		FakeIrisToolbelt.generator = new FakeIrisGenerator();
		FakeIrisToolbelt.generator.engine = new FakeIrisEngine();
		FakeIrisToolbelt.generator.engine.mantle = new FakeIrisMantle();
	}

	@Test
	public void underwaterColumnIsReported()
	{
		FakeIrisToolbelt.generator.engine.mantle.underwater = true;

		IrisTerrainProbe probe = fakeProbe();

		assertEquals(Boolean.TRUE, probe.isUnderwater(null, 128, -256));
		assertEquals(128, FakeIrisToolbelt.generator.engine.mantle.lastX);
		assertEquals(-256, FakeIrisToolbelt.generator.engine.mantle.lastZ);
	}

	@Test
	public void dryColumnIsReported()
	{
		FakeIrisToolbelt.generator.engine.mantle.underwater = false;

		assertEquals(Boolean.FALSE, fakeProbe().isUnderwater(null, 0, 0));
	}

	@Test
	public void missingToolbeltClassIsUnavailable()
	{
		IrisTerrainProbe probe = new IrisTerrainProbe(
				"art.arcane.wormholes.portal.rtp.DoesNotExist",
				() -> IrisTerrainProbeTest.class.getClassLoader());

		assertNull(probe.isUnderwater(null, 0, 0));
	}

	@Test
	public void missingClassLoaderIsUnavailable()
	{
		IrisTerrainProbe probe = new IrisTerrainProbe(FakeIrisToolbelt.class.getName(), () -> null);

		assertNull(probe.isUnderwater(null, 0, 0));
	}

	@Test
	public void missingGeneratorIsUnavailable()
	{
		FakeIrisToolbelt.generator = null;

		assertNull(fakeProbe().isUnderwater(null, 0, 0));
	}

	@Test
	public void missingEngineIsUnavailable()
	{
		FakeIrisToolbelt.generator.engine = null;

		assertNull(fakeProbe().isUnderwater(null, 0, 0));
	}

	@Test
	public void closedEngineIsUnavailable()
	{
		FakeIrisToolbelt.generator.engine.closed = true;

		assertNull(fakeProbe().isUnderwater(null, 0, 0));
	}

	private IrisTerrainProbe fakeProbe()
	{
		return new IrisTerrainProbe(FakeIrisToolbelt.class.getName(), () -> FakeIrisToolbelt.class.getClassLoader());
	}

	public static final class FakeIrisToolbelt
	{
		static FakeIrisGenerator generator;

		public static FakeIrisGenerator access(World world)
		{
			return generator;
		}
	}

	public static final class FakeIrisGenerator
	{
		FakeIrisEngine engine;

		public FakeIrisEngine getEngine()
		{
			return engine;
		}
	}

	public static final class FakeIrisEngine
	{
		boolean closed;
		FakeIrisMantle mantle;

		public boolean isClosed()
		{
			return closed;
		}

		public FakeIrisMantle getMantle()
		{
			return mantle;
		}
	}

	public static final class FakeIrisMantle
	{
		boolean underwater;
		int lastX;
		int lastZ;

		public boolean isUnderwater(int x, int z)
		{
			lastX = x;
			lastZ = z;
			return underwater;
		}
	}
}
