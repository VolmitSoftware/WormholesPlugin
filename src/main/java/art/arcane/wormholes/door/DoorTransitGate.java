package art.arcane.wormholes.door;

import java.util.Objects;
import java.util.Optional;

final class DoorTransitGate
{
	private static final double MOVEMENT_PROXIMITY = 2.25D;

	private DoorTransitGate()
	{
	}

	static Optional<DoorwayCrossing> detect(DoorwayPlane plane, DoorVec3 from, DoorVec3 to)
	{
		Objects.requireNonNull(from, "from");
		Objects.requireNonNull(to, "to");
		if(plane == null || !nearThreshold(plane, from, to))
		{
			return Optional.empty();
		}
		return plane.crossing(from, to);
	}

	static boolean claim(DoorOpenCycle cycle, boolean openAtCrossing, boolean liveOpen)
	{
		DoorOpenCycle requiredCycle = Objects.requireNonNull(cycle, "cycle");
		if(!openAtCrossing)
		{
			requiredCycle.observe(liveOpen);
			return false;
		}
		return requiredCycle.tryBegin(liveOpen);
	}

	private static boolean nearThreshold(DoorwayPlane plane, DoorVec3 from, DoorVec3 to)
	{
		DoorVec3 center = plane.center();
		return Math.abs(from.x() - center.x()) <= MOVEMENT_PROXIMITY
			&& Math.abs(from.z() - center.z()) <= MOVEMENT_PROXIMITY
			&& Math.abs(to.x() - center.x()) <= MOVEMENT_PROXIMITY
			&& Math.abs(to.z() - center.z()) <= MOVEMENT_PROXIMITY;
	}
}
