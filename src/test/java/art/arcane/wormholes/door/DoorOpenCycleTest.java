package art.arcane.wormholes.door;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

public final class DoorOpenCycleTest
{
	@Test
	public void closedDoorCannotBeginTransit()
	{
		DoorOpenCycle cycle = new DoorOpenCycle();

		assertFalse(cycle.tryBegin(false));
		assertEquals(DoorOpenCycle.Phase.CLOSED, cycle.phase());
	}

	@Test
	public void successfulTransitConsumesHeldOpenRedstoneCycle()
	{
		DoorOpenCycle cycle = new DoorOpenCycle();

		assertTrue(cycle.tryBegin(true));
		assertEquals(DoorOpenCycle.Phase.IN_TRANSIT, cycle.phase());
		assertEquals(DoorOpenCycle.Phase.CONSUMED, cycle.complete(true, true));
		assertFalse(cycle.tryBegin(true));
		assertFalse(cycle.tryBegin(true));
		assertEquals(DoorOpenCycle.Phase.CONSUMED, cycle.phase());
	}

	@Test
	public void realCloseThenOpenStartsExactlyOneNewCycle()
	{
		DoorOpenCycle cycle = new DoorOpenCycle();
		assertTrue(cycle.tryBegin(true));
		cycle.complete(true, true);

		assertEquals(DoorOpenCycle.Phase.CLOSED, cycle.observe(false));
		assertEquals(DoorOpenCycle.Phase.ARMED, cycle.observe(true));
		assertTrue(cycle.tryBegin(true));
		assertFalse(cycle.tryBegin(true));
	}

	@Test
	public void failedTeleportMayRetryWhileDoorRemainsPhysicallyOpen()
	{
		DoorOpenCycle cycle = new DoorOpenCycle();
		assertTrue(cycle.tryBegin(true));

		assertEquals(DoorOpenCycle.Phase.ARMED, cycle.complete(false, true));
		assertTrue(cycle.tryBegin(true));
	}

	@Test
	public void closingDuringFailedTransitRequiresAnotherOpen()
	{
		DoorOpenCycle cycle = new DoorOpenCycle();
		assertTrue(cycle.tryBegin(true));

		assertEquals(DoorOpenCycle.Phase.CLOSED, cycle.complete(false, false));
		assertFalse(cycle.tryBegin(false));
		assertTrue(cycle.tryBegin(true));
	}

	@Test
	public void completionWithoutClaimIsRejected()
	{
		DoorOpenCycle cycle = new DoorOpenCycle();

		assertThrows(IllegalStateException.class, () -> cycle.complete(true, false));
	}
}
