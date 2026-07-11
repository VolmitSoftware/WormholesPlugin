package art.arcane.wormholes.door;

import java.util.Objects;
import java.util.UUID;

/** The durable A/B membership minted for one paired-door kit. */
public record DoorPairIdentity(UUID pairId, UUID endpointAItemId, UUID endpointBItemId) {
    public DoorPairIdentity {
        Objects.requireNonNull(pairId, "pairId");
        Objects.requireNonNull(endpointAItemId, "endpointAItemId");
        Objects.requireNonNull(endpointBItemId, "endpointBItemId");
        if (endpointAItemId.equals(endpointBItemId)) {
            throw new IllegalArgumentException("paired endpoints must have different item IDs");
        }
    }

    public static DoorPairIdentity create() {
        return new DoorPairIdentity(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID());
    }

    public DoorItemIdentity endpoint(PairEndpoint endpoint) {
        Objects.requireNonNull(endpoint, "endpoint");
        return DoorItemIdentity.paired(
            endpoint == PairEndpoint.A ? endpointAItemId : endpointBItemId,
            pairId,
            endpoint
        );
    }

    public UUID itemId(PairEndpoint endpoint) {
        return endpoint(endpoint).itemId();
    }

    public boolean contains(UUID itemId) {
        return endpointAItemId.equals(itemId) || endpointBItemId.equals(itemId);
    }
}
