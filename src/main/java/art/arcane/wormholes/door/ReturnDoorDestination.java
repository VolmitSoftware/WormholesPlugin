package art.arcane.wormholes.door;

import java.util.Objects;
import java.util.UUID;

/** Resolve through this traveler's persisted return ticket for the source pocket. */
public record ReturnDoorDestination(UUID spaceId, UUID travelerId) implements DoorDestination {
    public ReturnDoorDestination {
        Objects.requireNonNull(spaceId, "spaceId");
        Objects.requireNonNull(travelerId, "travelerId");
    }
}
