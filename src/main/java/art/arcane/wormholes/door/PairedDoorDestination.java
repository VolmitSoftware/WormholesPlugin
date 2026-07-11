package art.arcane.wormholes.door;

import java.util.Objects;
import java.util.UUID;

/** The opposite physical endpoint of a paired door. */
public record PairedDoorDestination(UUID pairId, PairEndpoint endpoint) implements DoorDestination {
    public PairedDoorDestination {
        Objects.requireNonNull(pairId, "pairId");
        Objects.requireNonNull(endpoint, "endpoint");
    }
}
