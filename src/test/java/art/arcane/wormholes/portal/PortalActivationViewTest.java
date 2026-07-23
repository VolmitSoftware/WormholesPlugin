package art.arcane.wormholes.portal;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.Test;

import art.arcane.wormholes.Settings;
import art.arcane.wormholes.util.AxisAlignedBB;
import art.arcane.wormholes.util.Cuboid;

public final class PortalActivationViewTest
{
	@Test
	public void overrideExpandsViewByPerPortalRange()
	{
		LocalPortal portal = localPortal();
		portal.setActivationRange(96);

		AxisAlignedBB area = portal.getStructure().getArea();
		AxisAlignedBB view = portal.getView();

		assertEquals(area.getXa() - 96.0D, view.getXa(), 1.0E-9D);
		assertEquals(area.getXb() + 96.0D, view.getXb(), 1.0E-9D);
		assertEquals(area.getYa() - 96.0D, view.getYa(), 1.0E-9D);
		assertEquals(area.getYb() + 96.0D, view.getYb(), 1.0E-9D);
		assertEquals(area.getZa() - 96.0D, view.getZa(), 1.0E-9D);
		assertEquals(area.getZb() + 96.0D, view.getZb(), 1.0E-9D);
	}

	@Test
	public void sentinelDefersToGlobalProjectionRange()
	{
		double previous = Settings.PROJECTION_RANGE;
		try
		{
			Settings.PROJECTION_RANGE = 48.0D;
			LocalPortal portal = localPortal();
			AxisAlignedBB area = portal.getStructure().getArea();

			AxisAlignedBB view = portal.getView();
			assertEquals(area.getXa() - 48.0D, view.getXa(), 1.0E-9D);
			assertEquals(area.getXb() + 48.0D, view.getXb(), 1.0E-9D);

			Settings.PROJECTION_RANGE = 80.0D;
			AxisAlignedBB recomputed = portal.getView();
			assertEquals(area.getXa() - 80.0D, recomputed.getXa(), 1.0E-9D);
			assertEquals(area.getXb() + 80.0D, recomputed.getXb(), 1.0E-9D);
		}
		finally
		{
			Settings.PROJECTION_RANGE = previous;
		}
	}

	@Test
	public void overrideIgnoresGlobalChanges()
	{
		double previous = Settings.PROJECTION_RANGE;
		try
		{
			Settings.PROJECTION_RANGE = 32.0D;
			LocalPortal portal = localPortal();
			portal.setActivationRange(64);
			AxisAlignedBB area = portal.getStructure().getArea();

			AxisAlignedBB view = portal.getView();
			assertEquals(area.getXa() - 64.0D, view.getXa(), 1.0E-9D);

			Settings.PROJECTION_RANGE = 200.0D;
			AxisAlignedBB unchanged = portal.getView();
			assertEquals(area.getXa() - 64.0D, unchanged.getXa(), 1.0E-9D);
			assertEquals(area.getXb() + 64.0D, unchanged.getXb(), 1.0E-9D);
		}
		finally
		{
			Settings.PROJECTION_RANGE = previous;
		}
	}

	private static LocalPortal localPortal()
	{
		Map<String, Object> values = new HashMap<String, Object>();
		values.put("worldKey", "minecraft:overworld");
		values.put("x1", Integer.valueOf(0));
		values.put("y1", Integer.valueOf(64));
		values.put("z1", Integer.valueOf(0));
		values.put("x2", Integer.valueOf(0));
		values.put("y2", Integer.valueOf(66));
		values.put("z2", Integer.valueOf(2));
		PortalStructure structure = new PortalStructure();
		structure.setArea(new Cuboid(values));
		return new LocalPortal(UUID.randomUUID(), PortalType.WORMHOLE, structure);
	}
}
