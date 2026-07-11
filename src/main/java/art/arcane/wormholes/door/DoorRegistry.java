package art.arcane.wormholes.door;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/** In-memory uniqueness and lookup index for placed physical doors. */
public final class DoorRegistry {
    private final Map<DoorPosition.DoorBlockKey, PlacedDoorEndpoint> byBlock = new LinkedHashMap<>();
    private final Map<UUID, PlacedDoorEndpoint> byItem = new LinkedHashMap<>();
    private final Map<PairKey, PlacedDoorEndpoint> byPairEndpoint = new LinkedHashMap<>();

    public DoorRegistry() {
    }

    public DoorRegistry(Collection<PlacedDoorEndpoint> endpoints) {
        for (PlacedDoorEndpoint endpoint : List.copyOf(Objects.requireNonNull(endpoints, "endpoints"))) {
            register(endpoint);
        }
    }

    /**
     * @return true when newly registered, false for an identical idempotent registration
     * @throws IllegalStateException when a position, item ID, or pair side is already claimed
     */
    public synchronized boolean register(PlacedDoorEndpoint endpoint) {
        Objects.requireNonNull(endpoint, "endpoint");
        DoorPosition.DoorBlockKey blockKey = endpoint.position().blockKey();
        PlacedDoorEndpoint atBlock = byBlock.get(blockKey);
        if (endpoint.equals(atBlock)) {
            return false;
        }
        if (atBlock != null) {
            throw new IllegalStateException("door position is already occupied: " + endpoint.position());
        }

        PlacedDoorEndpoint withItem = byItem.get(endpoint.identity().itemId());
        if (withItem != null) {
            throw new IllegalStateException("door item is already placed: " + endpoint.identity().itemId());
        }

        PairKey pairKey = pairKey(endpoint.identity());
        if (pairKey != null && byPairEndpoint.containsKey(pairKey)) {
            throw new IllegalStateException("paired endpoint is already placed: " + pairKey);
        }

        byBlock.put(blockKey, endpoint);
        byItem.put(endpoint.identity().itemId(), endpoint);
        if (pairKey != null) {
            byPairEndpoint.put(pairKey, endpoint);
        }
        return true;
    }

    public synchronized Optional<PlacedDoorEndpoint> at(DoorPosition position) {
        return Optional.ofNullable(byBlock.get(Objects.requireNonNull(position, "position").blockKey()));
    }

    public synchronized Optional<PlacedDoorEndpoint> at(UUID worldId, int x, int y, int z) {
        return Optional.ofNullable(byBlock.get(new DoorPosition.DoorBlockKey(
            Objects.requireNonNull(worldId, "worldId"), x, y, z
        )));
    }

    public synchronized Optional<PlacedDoorEndpoint> byItemId(UUID itemId) {
        return Optional.ofNullable(byItem.get(Objects.requireNonNull(itemId, "itemId")));
    }

    public synchronized Optional<PlacedDoorEndpoint> pairedEndpoint(UUID pairId, PairEndpoint endpoint) {
        return Optional.ofNullable(byPairEndpoint.get(new PairKey(
            Objects.requireNonNull(pairId, "pairId"),
            Objects.requireNonNull(endpoint, "endpoint")
        )));
    }

    public synchronized Optional<PlacedDoorEndpoint> mateOf(DoorItemIdentity identity) {
        Objects.requireNonNull(identity, "identity");
        if (identity.kind() != DoorKind.PAIRED) {
            return Optional.empty();
        }
        return pairedEndpoint(identity.pairId(), identity.pairEndpoint().other());
    }

    public synchronized Optional<PlacedDoorEndpoint> remove(DoorPosition position) {
        Objects.requireNonNull(position, "position");
        PlacedDoorEndpoint removed = byBlock.remove(position.blockKey());
        if (removed == null) {
            return Optional.empty();
        }
        byItem.remove(removed.identity().itemId());
        PairKey pairKey = pairKey(removed.identity());
        if (pairKey != null) {
            byPairEndpoint.remove(pairKey);
        }
        return Optional.of(removed);
    }

    public synchronized List<PlacedDoorEndpoint> endpoints() {
        return List.copyOf(new ArrayList<>(byBlock.values()));
    }

    public synchronized int size() {
        return byBlock.size();
    }

    private static PairKey pairKey(DoorItemIdentity identity) {
        return identity.kind() == DoorKind.PAIRED
            ? new PairKey(identity.pairId(), identity.pairEndpoint())
            : null;
    }

    private record PairKey(UUID pairId, PairEndpoint endpoint) {
    }
}
