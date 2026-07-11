package art.arcane.wormholes.door;

import java.util.Objects;
import java.util.UUID;

/**
 * Stable lookup key for a pocket. PERSONAL keys are player UUIDs; IRON keys
 * are immutable door-item UUIDs.
 */
public record PocketBinding(PocketBindingKind kind, UUID bindingId) {
    public PocketBinding {
        Objects.requireNonNull(kind, "kind");
        Objects.requireNonNull(bindingId, "bindingId");
    }

    public static PocketBinding personal(UUID travelerId) {
        return new PocketBinding(PocketBindingKind.PERSONAL, travelerId);
    }

    public static PocketBinding iron(UUID doorItemId) {
        return new PocketBinding(PocketBindingKind.IRON, doorItemId);
    }
}
