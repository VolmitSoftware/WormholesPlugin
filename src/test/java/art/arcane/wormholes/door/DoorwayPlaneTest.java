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

		assertEquals(0.5D, crossing.segmentFraction(), 1.0E-9D);
		assertEquals(new DoorVec3(10.5D, 65.0D, -3.5D), crossing.point());
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
		assertEquals(new DoorVec3(-1.5D, 20.0D, 7.5D), crossing.point());
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

		assertFalse(plane.crossing(
			new DoorVec3(0.5D, 64.0D, 0.5D),
			new DoorVec3(0.5D, 64.0D, 1.5D)).isPresent());
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
	public void arrivalSidesFollowCrossingDirectionForEveryFacing()
	{
		for(BlockFace facing : new BlockFace[]{BlockFace.NORTH, BlockFace.SOUTH, BlockFace.EAST, BlockFace.WEST})
		{
			DoorwayPlane plane = new DoorwayPlane(10, 64, -4, facing);
			for(DoorwayCrossing.Direction direction : DoorwayCrossing.Direction.values())
			{
				DoorVec3 entry = plane.entrySidePoint(direction, 1.0D);
				DoorVec3 exit = plane.exitSidePoint(direction, 1.0D);

				assertEquals(direction.entrySideSign(), plane.signedDistance(entry), 1.0E-9D);
				assertEquals(direction.exitSideSign(), plane.signedDistance(exit), 1.0E-9D);
				assertEquals(0.5D, entry.x() - Math.floor(entry.x()), 1.0E-9D);
				assertEquals(0.5D, entry.z() - Math.floor(entry.z()), 1.0E-9D);
				assertEquals(0.5D, exit.x() - Math.floor(exit.x()), 1.0E-9D);
				assertEquals(0.5D, exit.z() - Math.floor(exit.z()), 1.0E-9D);
				assertEquals(64.0D, entry.y(), 0.0D);
				assertEquals(64.0D, exit.y(), 0.0D);
			}
		}
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
		assertThrows(IllegalArgumentException.class,
			() -> new DoorTransit(plane, DoorwayCrossing.Direction.FRONT_TO_BACK, Float.NaN, 0.0F));
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
