package art.arcane.wormholes.door;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class DoorTravelerPolicyTest
{
	@Test
	void playersCanEnterEveryDoorKind()
	{
		for(DoorKind kind : DoorKind.values())
		{
			assertTrue(DoorTravelerPolicy.canEnter(
				kind, true, false, false, false, false, 0.6D, 1.8D), kind.name());
		}
	}

	@Test
	void fittingOrdinaryMobCanEnterPairDoor()
	{
		assertTrue(DoorTravelerPolicy.canEnter(
			DoorKind.PAIR, false, true, false, false, false, 0.6D, 1.8D));
	}

	@Test
	void nonPlayersCannotEnterPocketOrReturnDoors()
	{
		assertFalse(DoorTravelerPolicy.canEnter(
			DoorKind.PERSONAL, false, true, false, false, false, 0.6D, 1.8D));
		assertFalse(DoorTravelerPolicy.canEnter(
			DoorKind.PUBLIC, false, true, false, false, false, 0.6D, 1.8D));
		assertFalse(DoorTravelerPolicy.canEnter(
			DoorKind.RETURN, false, true, false, false, false, 0.6D, 1.8D));
	}

	@Test
	void bossesCannotEnterPairDoor()
	{
		assertFalse(DoorTravelerPolicy.canEnter(
			DoorKind.PAIR, false, true, true, false, false, 0.6D, 1.8D));
	}

	@Test
	void complexEntitiesCannotEnterPairDoor()
	{
		assertFalse(DoorTravelerPolicy.canEnter(
			DoorKind.PAIR, false, true, false, true, false, 0.6D, 1.8D));
	}

	@Test
	void constrainedEntitiesCannotEnterPairDoor()
	{
		assertFalse(DoorTravelerPolicy.canEnter(
			DoorKind.PAIR, false, true, false, false, true, 0.6D, 1.8D));
	}

	@Test
	void oversizedEntitiesCannotEnterPairDoor()
	{
		assertFalse(DoorTravelerPolicy.canEnter(
			DoorKind.PAIR, false, true, false, false, false, 1.01D, 1.8D));
		assertFalse(DoorTravelerPolicy.canEnter(
			DoorKind.PAIR, false, true, false, false, false, 0.6D, 2.01D));
	}

	@Test
	void nonFiniteEntitiesCannotEnterPairDoor()
	{
		assertFalse(DoorTravelerPolicy.canEnter(
			DoorKind.PAIR, false, true, false, false, false, Double.NaN, 1.8D));
		assertFalse(DoorTravelerPolicy.canEnter(
			DoorKind.PAIR, false, true, false, false, false, 0.6D, Double.POSITIVE_INFINITY));
	}
}
