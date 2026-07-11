package art.arcane.wormholes.door;

import java.util.Objects;

public record PocketDoorDestination(PocketBinding binding) implements DoorDestination {
    public PocketDoorDestination {
        Objects.requireNonNull(binding, "binding");
    }
}
