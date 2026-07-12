package art.arcane.wormholes.door;

import org.bukkit.block.BlockFace;
import org.junit.jupiter.api.Test;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

public final class DoorTransitGateTest
{
	@Test
	public void newlyPlacedPairNeedsOnlySourceLiveOpenState()
	{
		DoorOpenCycle source = new DoorOpenCycle();
		DoorOpenCycle mate = new DoorOpenCycle();
		source.observe(false);
		mate.observe(false);

		Optional<DoorwayCrossing> crossing = DoorTransitGate.detect(
			new DoorwayPlane(0, 64, 0, BlockFace.NORTH),
			new DoorVec3(0.5D, 65.0D, 1.0D),
			new DoorVec3(0.5D, 65.0D, 0.0D));

		assertTrue(crossing.isPresent());
		assertTrue(DoorTransitGate.claim(source, true, true));
		assertEquals(DoorOpenCycle.Phase.IN_TRANSIT, source.phase());
		assertEquals(DoorOpenCycle.Phase.CLOSED, mate.phase());
	}

	@Test
	public void closedSourceCannotClaimDetectedCrossing()
	{
		DoorOpenCycle source = new DoorOpenCycle();
		Optional<DoorwayCrossing> crossing = DoorTransitGate.detect(
			new DoorwayPlane(0, 64, 0, BlockFace.NORTH),
			new DoorVec3(0.5D, 65.0D, 1.0D),
			new DoorVec3(0.5D, 65.0D, 0.0D));

		assertTrue(crossing.isPresent());
		assertFalse(DoorTransitGate.claim(source, false, false));
		assertEquals(DoorOpenCycle.Phase.CLOSED, source.phase());
	}

	@Test
	public void closedAtCrossingCannotClaimAfterDoorOpens()
	{
		DoorOpenCycle source = new DoorOpenCycle();

		assertFalse(DoorTransitGate.claim(source, false, true));
		assertEquals(DoorOpenCycle.Phase.ARMED, source.phase());
	}

	@Test
	public void openAtCrossingCannotClaimAfterDoorCloses()
	{
		DoorOpenCycle source = new DoorOpenCycle();

		assertFalse(DoorTransitGate.claim(source, true, false));
		assertEquals(DoorOpenCycle.Phase.CLOSED, source.phase());
	}

	@Test
	public void movementFarFromDoorIsNotAdmitted()
	{
		Optional<DoorwayCrossing> crossing = DoorTransitGate.detect(
			new DoorwayPlane(0, 64, 0, BlockFace.NORTH),
			new DoorVec3(10.5D, 65.0D, 1.0D),
			new DoorVec3(10.5D, 65.0D, 0.0D));

		assertTrue(crossing.isEmpty());
	}
}
