package art.arcane.wormholes.door;

import java.util.Objects;
import java.util.UUID;

/**
 * The lower block of a placed two-block door. The world name is retained for
 * diagnostics and delayed world lookup; the UUID and block coordinates are
 * the canonical placement key.
 */
public record DoorPosition(UUID worldId, String worldName, int x, int y, int z) {
    public DoorPosition {
        Objects.requireNonNull(worldId, "worldId");
        worldName = Objects.requireNonNull(worldName, "worldName").trim();
        if (worldName.isEmpty()) {
            throw new IllegalArgumentException("worldName cannot be blank");
        }
    }

    DoorBlockKey blockKey() {
        return new DoorBlockKey(worldId, x, y, z);
    }

    record DoorBlockKey(UUID worldId, int x, int y, int z) {
    }
}
