package art.arcane.wormholes.door;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.reflect.Proxy;
import java.util.UUID;

import org.bukkit.block.BlockFace;
import org.bukkit.block.data.Bisected;
import org.bukkit.block.data.type.Door;
import org.junit.jupiter.api.Test;

public final class DoorwayPlaneTest
{
	@Test
	public void northFacingDoorDetectsFastDiagonalCrossingInsideAperture()
	{
		DoorwayPlane plane = new DoorwayPlane(10, 64, -4, BlockFace.NORTH);

		DoorwayCrossing crossing = plane.crossing(
			new DoorVec3(10.2D, 64.25D, -4.5D),
			new DoorVec3(10.8D, 65.75D, -2.5D)).orElseThrow();

		assertEquals(0.0D, plane.signedDistance(crossing.point()), 1.0E-9D);
		assertEquals(plane.center().z(), crossing.point().z(), 1.0E-9D);
		assertEquals(DoorwayCrossing.Direction.FRONT_TO_BACK, crossing.direction());
	}

	@Test
	public void eastFacingDoorDetectsCrossingInEitherDirection()
	{
		DoorwayPlane plane = new DoorwayPlane(-2, 20, 7, BlockFace.EAST);

		DoorwayCrossing crossing = plane.crossing(
			new DoorVec3(-0.5D, 20.0D, 7.5D),
			new DoorVec3(-3.5D, 20.0D, 7.5D)).orElseThrow();

		assertEquals(DoorwayCrossing.Direction.FRONT_TO_BACK, crossing.direction());
		assertEquals(plane.center().x(), crossing.point().x(), 1.0E-9D);
		assertEquals(7.5D, crossing.point().z(), 1.0E-9D);
	}

	@Test
	public void apertureIncludesPhysicalEdgesButRejectsOutsideAndCoplanarMotion()
	{
		DoorwayPlane plane = new DoorwayPlane(0, 64, 0, BlockFace.SOUTH);

		assertTrue(plane.crossing(
			new DoorVec3(0.0D, 66.0D, 0.0D),
			new DoorVec3(0.0D, 66.0D, 1.0D)).isPresent());
		assertFalse(plane.crossing(
			new DoorVec3(-0.01D, 65.0D, 0.0D),
			new DoorVec3(-0.01D, 65.0D, 1.0D)).isPresent());
		assertFalse(plane.crossing(
			new DoorVec3(0.25D, 66.01D, 0.0D),
			new DoorVec3(0.25D, 66.01D, 1.0D)).isPresent());
		assertFalse(plane.crossing(
			new DoorVec3(0.0D, 65.0D, 0.5D),
			new DoorVec3(1.0D, 65.0D, 0.5D)).isPresent());
	}

	@Test
	public void movementStartingOnPlaneDoesNotPullPlayerThrough()
	{
		DoorwayPlane plane = new DoorwayPlane(0, 64, 0, BlockFace.NORTH);
		DoorVec3 center = plane.center();

		assertFalse(plane.crossing(
			new DoorVec3(center.x(), 64.0D, center.z()),
			new DoorVec3(center.x(), 64.0D, center.z() + 1.0D)).isPresent());
	}

	@Test
	public void recessedThresholdAcceptsNormalStepHeightApproachAtTheSecondEndpoint()
	{
		DoorwayPlane plane = new DoorwayPlane(-284, 69, 166, BlockFace.SOUTH);

		DoorwayCrossing crossing = plane.crossing(
			new DoorVec3(-283.79D, 68.875D, 164.36D),
			new DoorVec3(-283.79D, 68.875D, 166.20D)).orElseThrow();

		assertEquals(plane.center().z(), crossing.point().z(), 1.0E-9D);
		assertEquals(DoorwayCrossing.Direction.BACK_TO_FRONT, crossing.direction());
		assertFalse(plane.crossing(
			new DoorVec3(-283.79D, 68.39D, 164.36D),
			new DoorVec3(-283.79D, 68.39D, 166.20D)).isPresent());
	}

	@Test
	public void vanillaTopHalfNormalizesToLowerDoorAndOpenableStateIsAuthoritative()
	{
		UUID worldId = UUID.randomUUID();
		Door door = doorData(BlockFace.WEST, Bisected.Half.TOP, Door.Hinge.RIGHT, true, false);

		VanillaDoorSnapshot snapshot = VanillaDoorSnapshot.fromBlockData(worldId, 3, 81, 9, door);

		assertEquals(worldId, snapshot.worldId());
		assertEquals(new DoorwayPlane(3, 80, 9, BlockFace.WEST), snapshot.plane());
		assertEquals(Door.Hinge.RIGHT, snapshot.hinge());
		assertTrue(snapshot.open());
		assertFalse(snapshot.powered());
	}

	@Test
	public void invalidFacingAndNonFiniteCoordinatesAreRejected()
	{
		assertThrows(IllegalArgumentException.class,
			() -> new DoorwayPlane(0, 0, 0, BlockFace.UP));
		assertThrows(IllegalArgumentException.class,
			() -> new DoorVec3(Double.NaN, 0.0D, 0.0D));
	}

	@Test
	public void arrivalSidesStaySymmetricAroundPhysicalDoorForEveryFacing()
	{
		for(BlockFace facing : new BlockFace[]{BlockFace.NORTH, BlockFace.SOUTH, BlockFace.EAST, BlockFace.WEST})
		{
			DoorwayPlane plane = new DoorwayPlane(10, 64, -4, facing);
			for(DoorwayCrossing.Direction direction : DoorwayCrossing.Direction.values())
			{
				DoorVec3 entry = plane.entrySidePoint(direction, 1.0D);
				DoorVec3 exit = plane.exitSidePoint(direction, 1.0D);

				assertEquals(direction.entrySideSign(), physicalNormalOffset(plane, entry), 1.0E-9D);
				assertEquals(direction.exitSideSign(), physicalNormalOffset(plane, exit), 1.0E-9D);
				assertTrue(plane.signedDistance(entry) * direction.entrySideSign() > 0.0D);
				assertTrue(plane.signedDistance(exit) * direction.exitSideSign() > 0.0D);
				assertEquals(0.5D, entry.x() - Math.floor(entry.x()), 1.0E-9D);
				assertEquals(0.5D, entry.z() - Math.floor(entry.z()), 1.0E-9D);
				assertEquals(0.5D, exit.x() - Math.floor(exit.x()), 1.0E-9D);
				assertEquals(0.5D, exit.z() - Math.floor(exit.z()), 1.0E-9D);
				assertEquals(64.0D, entry.y(), 0.0D);
				assertEquals(64.0D, exit.y(), 0.0D);
			}
		}
	}

	private static double physicalNormalOffset(DoorwayPlane plane, DoorVec3 point)
	{
		return ((point.x() - (plane.blockX() + 0.5D)) * plane.facing().getModX())
			+ ((point.z() - (plane.blockZ() + 0.5D)) * plane.facing().getModZ());
	}

	@Test
	public void enteredClosedDoorFaceMapsToMatchingDestinationFaceAcrossFacingsAndHinges()
	{
		for(BlockFace sourceFacing : new BlockFace[]{BlockFace.NORTH, BlockFace.SOUTH, BlockFace.EAST, BlockFace.WEST})
		{
			for(Door.Hinge sourceHinge : new Door.Hinge[]{Door.Hinge.LEFT, Door.Hinge.RIGHT})
			{
				DoorwayPlane source = DoorwayPlane.fromBlockData(
					0, 64, 0, doorData(sourceFacing, Bisected.Half.BOTTOM, sourceHinge, true, false));
				for(BlockFace destinationFacing : new BlockFace[]{BlockFace.NORTH, BlockFace.SOUTH, BlockFace.EAST, BlockFace.WEST})
				{
					for(Door.Hinge destinationHinge : new Door.Hinge[]{Door.Hinge.LEFT, Door.Hinge.RIGHT})
					{
						DoorwayPlane destination = DoorwayPlane.fromBlockData(
							100,
							70,
							100,
							doorData(destinationFacing, Bisected.Half.BOTTOM, destinationHinge, true, false));
						for(DoorwayCrossing.Direction direction : DoorwayCrossing.Direction.values())
						{
							float sourceYaw = vectorYaw(
								sourceFacing.getModX() * direction.exitSideSign(),
								sourceFacing.getModZ() * direction.exitSideSign());
							float expectedYaw = vectorYaw(
								destinationFacing.getModX() * direction.entrySideSign(),
								destinationFacing.getModZ() * direction.entrySideSign());
							DoorTransit transit = new DoorTransit(source, direction, sourceYaw, 0.0F);
							DoorVec3 arrival = DimensionalDoorManager.arrivalPoint(destination, transit);
							String scenario = sourceFacing + " " + sourceHinge + " -> "
								+ destinationFacing + " " + destinationHinge + " " + direction;

							assertEquals(destination.entrySidePoint(direction, 1.0D), arrival, scenario);
							assertEquals(direction.entrySideSign(), physicalNormalOffset(destination, arrival), 1.0E-9D, scenario);
							assertTrue(destination.signedDistance(arrival) * direction.entrySideSign() > 0.0D, scenario);
							assertEquals(expectedYaw, DimensionalDoorManager.arrivalYaw(source, destination, transit), 1.0E-6F, scenario);
						}
					}
				}
			}
		}
	}

	@Test
	public void destinationArrivalCanStepDownWithoutChangingDoorSide()
	{
		DoorwayPlane plane = new DoorwayPlane(-132, 68, 56, BlockFace.EAST);
		DoorVec3 nominal = plane.entrySidePoint(DoorwayCrossing.Direction.BACK_TO_FRONT, 1.0D);
		DoorVec3 selected = DimensionalDoorManager.findSafeVerticalDoorStanding(
			nominal,
			candidate -> candidate.y() == 67.0D).orElseThrow();

		assertEquals(nominal.x(), selected.x(), 0.0D);
		assertEquals(67.0D, selected.y(), 0.0D);
		assertEquals(nominal.z(), selected.z(), 0.0D);
		assertEquals(plane.signedDistance(nominal), plane.signedDistance(selected), 0.0D);
	}

	@Test
	public void destinationArrivalPrefersTheDoorBaseHeight()
	{
		DoorVec3 nominal = new DoorVec3(4.5D, 70.0D, -2.5D);

		DoorVec3 selected = DimensionalDoorManager.findSafeVerticalDoorStanding(
			nominal,
			candidate -> candidate.y() == 70.0D || candidate.y() == 69.0D).orElseThrow();

		assertEquals(nominal, selected);
	}

	@Test
	public void yawRotationPreservesTravelDirectionAcrossEveryFacingPair()
	{
		for(BlockFace sourceFacing : new BlockFace[]{BlockFace.NORTH, BlockFace.SOUTH, BlockFace.EAST, BlockFace.WEST})
		{
			DoorwayPlane source = new DoorwayPlane(0, 64, 0, sourceFacing);
			for(BlockFace targetFacing : new BlockFace[]{BlockFace.NORTH, BlockFace.SOUTH, BlockFace.EAST, BlockFace.WEST})
			{
				DoorwayPlane target = new DoorwayPlane(100, 70, 100, targetFacing);
				for(DoorwayCrossing.Direction direction : DoorwayCrossing.Direction.values())
				{
					int sourceSign = direction.exitSideSign();
					int targetSign = direction.exitSideSign();
					float sourceYaw = vectorYaw(
						sourceFacing.getModX() * sourceSign,
						sourceFacing.getModZ() * sourceSign);
					float expectedYaw = vectorYaw(
						targetFacing.getModX() * targetSign,
						targetFacing.getModZ() * targetSign);

					assertEquals(expectedYaw, source.rotateYawTo(target, sourceYaw), 1.0E-6F,
						sourceFacing + " -> " + targetFacing + " " + direction);
				}
			}
		}
	}

	@Test
	public void arrivalGeometryRejectsInvalidOffsetsAndYaw()
	{
		DoorwayPlane plane = new DoorwayPlane(0, 64, 0, BlockFace.SOUTH);
		assertThrows(IllegalArgumentException.class,
			() -> plane.entrySidePoint(DoorwayCrossing.Direction.FRONT_TO_BACK, 0.0D));
		assertThrows(IllegalArgumentException.class,
			() -> plane.exitSidePoint(DoorwayCrossing.Direction.FRONT_TO_BACK, Double.NaN));
		assertThrows(IllegalArgumentException.class,
			() -> plane.rotateYawTo(plane, Float.NaN));
		assertThrows(NullPointerException.class,
			() -> plane.rotateYawToMatchingSide(null, 0.0F));
		assertThrows(IllegalArgumentException.class,
			() -> new DoorTransit(plane, DoorwayCrossing.Direction.FRONT_TO_BACK, Float.NaN, 0.0F));
		assertThrows(IllegalArgumentException.class,
			() -> new DoorTransit(
				plane, DoorwayCrossing.Direction.FRONT_TO_BACK, 0.0F, 0.0F, 0.0D, 1.8D));
	}

	private static float vectorYaw(int x, int z)
	{
		float yaw = (float) Math.toDegrees(Math.atan2(-x, z));
		return yaw >= 180.0F ? yaw - 360.0F : yaw;
	}

	private static Door doorData(
		BlockFace facing,
		Bisected.Half half,
		Door.Hinge hinge,
		boolean open,
		boolean powered)
	{
		return (Door) Proxy.newProxyInstance(
			Door.class.getClassLoader(),
			new Class<?>[]{Door.class},
			(proxy, method, arguments) -> switch(method.getName())
			{
				case "getFacing" -> facing;
				case "getHalf" -> half;
				case "getHinge" -> hinge;
				case "isOpen" -> open;
				case "isPowered" -> powered;
				case "toString" -> "TestDoorData";
				case "hashCode" -> System.identityHashCode(proxy);
				case "equals" -> proxy == arguments[0];
				default -> throw new UnsupportedOperationException(method.getName());
			});
	}
}
