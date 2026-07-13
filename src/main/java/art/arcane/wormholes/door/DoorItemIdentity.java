package art.arcane.wormholes.door;

import java.util.Objects;
import java.util.UUID;

/**
 * Durable identity written to a dimensional-door item. Runtime state such as
 * placement or whether the physical door is open deliberately does not belong
 * here.
 */
public record DoorItemIdentity(
    UUID itemId,
    DoorKind kind,
    UUID pairId,
    PairEndpoint pairEndpoint,
    UUID spaceId
) {
    public DoorItemIdentity {
        Objects.requireNonNull(itemId, "itemId");
        Objects.requireNonNull(kind, "kind");

        switch (kind) {
            case PAIR -> {
                Objects.requireNonNull(pairId, "paired doors require pairId");
                Objects.requireNonNull(pairEndpoint, "paired doors require pairEndpoint");
                requireNull(spaceId, "paired doors cannot carry spaceId");
            }
            case PERSONAL, PUBLIC -> {
                requireNull(pairId, kind + " doors cannot carry pairId");
                requireNull(pairEndpoint, kind + " doors cannot carry pairEndpoint");
                requireNull(spaceId, kind + " doors cannot carry spaceId");
            }
            case RETURN -> {
                requireNull(pairId, "return doors cannot carry pairId");
                requireNull(pairEndpoint, "return doors cannot carry pairEndpoint");
                Objects.requireNonNull(spaceId, "return doors require spaceId");
            }
        }
    }

    public static DoorItemIdentity paired(UUID itemId, UUID pairId, PairEndpoint endpoint) {
        return new DoorItemIdentity(itemId, DoorKind.PAIR, pairId, endpoint, null);
    }

    public static DoorItemIdentity personal(UUID itemId) {
        return new DoorItemIdentity(itemId, DoorKind.PERSONAL, null, null, null);
    }

    public static DoorItemIdentity publicDoor(UUID itemId) {
        return new DoorItemIdentity(itemId, DoorKind.PUBLIC, null, null, null);
    }

    public static DoorItemIdentity returnDoor(UUID itemId, UUID spaceId) {
        return new DoorItemIdentity(itemId, DoorKind.RETURN, null, null, spaceId);
    }

    public static DoorItemIdentity newPersonal() {
        return personal(UUID.randomUUID());
    }

    public static DoorItemIdentity newPublic() {
        return publicDoor(UUID.randomUUID());
    }

    public static DoorItemIdentity newReturn(UUID spaceId) {
        return returnDoor(UUID.randomUUID(), spaceId);
    }

    private static void requireNull(Object value, String message) {
        if (value != null) {
            throw new IllegalArgumentException(message);
        }
    }
}
