package art.arcane.wormholes.door;

import java.util.Objects;

/** A dimensional-door identity bound to the lower half of a physical door. */
public record PlacedDoorEndpoint(DoorPosition position, DoorItemIdentity identity) {
    public PlacedDoorEndpoint {
        Objects.requireNonNull(position, "position");
        Objects.requireNonNull(identity, "identity");
    }
}
