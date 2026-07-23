package art.arcane.wormholes.door;

import org.bukkit.block.BlockFace;
import org.bukkit.block.data.type.Door;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DoorPortalAnimationTest
{
	private static final float EPSILON = 0.0001F;
	private static final BlockFace[] CARDINALS =
		{BlockFace.NORTH, BlockFace.SOUTH, BlockFace.EAST, BlockFace.WEST};

	@Test
	void frameNeverTouchesTheNormalAxis()
	{
		for(BlockFace facing : CARDINALS)
		{
			DoorPortalVisualService.PortalPlaneGeometry base = overlay(facing);
			for(int tick = 0; tick <= 288; tick += DoorPortalAnimation.FRAME_PERIOD_TICKS)
			{
				DoorPortalVisualService.PortalPlaneGeometry frame =
					DoorPortalAnimation.frame(base, facing, tick);
				if(facing == BlockFace.NORTH || facing == BlockFace.SOUTH)
				{
					assertEquals(base.translationZ(), frame.translationZ(), EPSILON);
					assertEquals(base.scaleZ(), frame.scaleZ(), EPSILON);
				}
				else
				{
					assertEquals(base.translationX(), frame.translationX(), EPSILON);
					assertEquals(base.scaleX(), frame.scaleX(), EPSILON);
				}
			}
		}
	}

	@Test
	void framePulsesInsideTheBaseEnvelopeAndStaysCentered()
	{
		for(BlockFace facing : CARDINALS)
		{
			DoorPortalVisualService.PortalPlaneGeometry base = overlay(facing);
			float baseLateralScale = lateralScale(base, facing);
			float baseLateralCenter = lateralTranslation(base, facing) + (baseLateralScale / 2.0F);
			float baseVerticalCenter = base.translationY() + (base.scaleY() / 2.0F);
			for(int tick = 0; tick <= 288; tick += DoorPortalAnimation.FRAME_PERIOD_TICKS)
			{
				DoorPortalVisualService.PortalPlaneGeometry frame =
					DoorPortalAnimation.frame(base, facing, tick);
				float lateralScale = lateralScale(frame, facing);
				float verticalScale = frame.scaleY();

				assertTrue(lateralScale <= baseLateralScale + EPSILON);
				assertTrue(lateralScale >= (baseLateralScale * (1.0F - DoorPortalAnimation.PULSE_DEPTH)) - EPSILON);
				assertTrue(verticalScale <= base.scaleY() + EPSILON);
				assertTrue(verticalScale >= (base.scaleY() * (1.0F - DoorPortalAnimation.PULSE_DEPTH)) - EPSILON);
				assertEquals(
					baseLateralCenter,
					lateralTranslation(frame, facing) + (lateralScale / 2.0F),
					DoorPortalAnimation.SWAY_AMPLITUDE + EPSILON);
				assertEquals(baseVerticalCenter, frame.translationY() + (verticalScale / 2.0F), EPSILON);
			}
		}
	}

	@Test
	void frameActuallyAnimatesBetweenTicks()
	{
		for(BlockFace facing : CARDINALS)
		{
			DoorPortalVisualService.PortalPlaneGeometry base = overlay(facing);
			DoorPortalVisualService.PortalPlaneGeometry first =
				DoorPortalAnimation.frame(base, facing, 0);
			boolean moved = false;
			for(int tick = DoorPortalAnimation.FRAME_PERIOD_TICKS; tick <= 48; tick += DoorPortalAnimation.FRAME_PERIOD_TICKS)
			{
				if(!DoorPortalAnimation.frame(base, facing, tick).equals(first))
				{
					moved = true;
					break;
				}
			}
			assertTrue(moved, facing + " frame never changed");
		}
	}

	@Test
	void orbitPointsStayOnTheVisiblePanel()
	{
		for(BlockFace facing : CARDINALS)
		{
			DoorPortalVisualService.PortalPlaneGeometry base = overlay(facing);
			for(int tick = 0; tick <= 288; tick += DoorPortalAnimation.FRAME_PERIOD_TICKS)
			{
				for(int arm = 0; arm < DoorPortalAnimation.ORBIT_ARMS; arm++)
				{
					assertOnPanel(base, facing, DoorPortalAnimation.orbitPoint(base, facing, tick, arm));
				}
			}
		}
	}

	@Test
	void counterRotatingArmsDiverge()
	{
		DoorPortalVisualService.PortalPlaneGeometry base = overlay(BlockFace.NORTH);
		boolean diverged = false;
		for(int tick = 0; tick <= 44; tick += DoorPortalAnimation.FRAME_PERIOD_TICKS)
		{
			double[] first = DoorPortalAnimation.orbitPoint(base, BlockFace.NORTH, tick, 0);
			double[] second = DoorPortalAnimation.orbitPoint(base, BlockFace.NORTH, tick, 1);
			if(Math.abs(first[0] - second[0]) > 0.05D || Math.abs(first[1] - second[1]) > 0.05D)
			{
				diverged = true;
				break;
			}
		}
		assertTrue(diverged);
	}

	@Test
	void scatterPointsCoverThePanelWithoutLeavingIt()
	{
		for(BlockFace facing : CARDINALS)
		{
			DoorPortalVisualService.PortalPlaneGeometry base = overlay(facing);
			assertOnPanel(base, facing, DoorPortalAnimation.scatterPoint(base, facing, 0.0D, 0.0D));
			assertOnPanel(base, facing, DoorPortalAnimation.scatterPoint(base, facing, 0.999D, 0.999D));
			double[] low = DoorPortalAnimation.scatterPoint(base, facing, 0.05D, 0.05D);
			double[] high = DoorPortalAnimation.scatterPoint(base, facing, 0.95D, 0.95D);
			assertNotEquals(low[1], high[1]);
		}
	}

	@Test
	void invalidInputsAreRejected()
	{
		DoorPortalVisualService.PortalPlaneGeometry base = overlay(BlockFace.NORTH);
		assertThrows(IllegalArgumentException.class,
			() -> DoorPortalAnimation.frame(base, BlockFace.UP, 0));
		assertThrows(NullPointerException.class,
			() -> DoorPortalAnimation.frame(null, BlockFace.NORTH, 0));
		assertThrows(IllegalArgumentException.class,
			() -> DoorPortalAnimation.orbitPoint(base, BlockFace.NORTH, 0, DoorPortalAnimation.ORBIT_ARMS));
		assertThrows(IllegalArgumentException.class,
			() -> DoorPortalAnimation.orbitPoint(base, BlockFace.NORTH, 0, -1));
		assertThrows(IllegalArgumentException.class,
			() -> DoorPortalAnimation.scatterPoint(base, BlockFace.NORTH, 1.0D, 0.5D));
		assertThrows(IllegalArgumentException.class,
			() -> DoorPortalAnimation.scatterPoint(base, BlockFace.NORTH, 0.5D, -0.1D));
	}

	private static DoorPortalVisualService.PortalPlaneGeometry overlay(BlockFace facing)
	{
		return DoorPortalVisualService.overlayGeometry(
			DoorPortalVisualService.geometry(facing, Door.Hinge.LEFT), facing);
	}

	private static void assertOnPanel(
		DoorPortalVisualService.PortalPlaneGeometry base,
		BlockFace facing,
		double[] point)
	{
		assertTrue(point[1] >= base.translationY() - EPSILON);
		assertTrue(point[1] <= base.translationY() + base.scaleY() + EPSILON);
		if(facing == BlockFace.NORTH || facing == BlockFace.SOUTH)
		{
			assertTrue(point[0] >= base.translationX() - EPSILON);
			assertTrue(point[0] <= base.translationX() + base.scaleX() + EPSILON);
			assertEquals(base.translationZ() + (base.scaleZ() / 2.0D), point[2], EPSILON);
		}
		else
		{
			assertTrue(point[2] >= base.translationZ() - EPSILON);
			assertTrue(point[2] <= base.translationZ() + base.scaleZ() + EPSILON);
			assertEquals(base.translationX() + (base.scaleX() / 2.0D), point[0], EPSILON);
		}
	}

	private static float lateralScale(
		DoorPortalVisualService.PortalPlaneGeometry geometry,
		BlockFace facing)
	{
		return facing == BlockFace.NORTH || facing == BlockFace.SOUTH
			? geometry.scaleX()
			: geometry.scaleZ();
	}

	private static float lateralTranslation(
		DoorPortalVisualService.PortalPlaneGeometry geometry,
		BlockFace facing)
	{
		return facing == BlockFace.NORTH || facing == BlockFace.SOUTH
			? geometry.translationX()
			: geometry.translationZ();
	}
}
