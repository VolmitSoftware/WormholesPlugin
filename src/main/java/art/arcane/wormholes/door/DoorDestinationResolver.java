package art.arcane.wormholes.door;

import java.util.Objects;
import java.util.UUID;

public final class DoorDestinationResolver {
    private DoorDestinationResolver() {
    }

    public static DoorDestination resolve(DoorItemIdentity door, UUID travelerId) {
        Objects.requireNonNull(door, "door");
        Objects.requireNonNull(travelerId, "travelerId");

        return switch (door.kind()) {
            case PAIRED -> new PairedDoorDestination(door.pairId(), door.pairEndpoint().other());
            case PERSONAL -> new PocketDoorDestination(PocketBinding.personal(travelerId));
            case IRON -> new PocketDoorDestination(PocketBinding.iron(door.itemId()));
            case RETURN -> new ReturnDoorDestination(door.spaceId(), travelerId);
        };
    }
}
