package art.arcane.wormholes.door;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/** Complete immutable DTO persisted by {@link DimensionalDoorRepository}. */
public record DoorStoreSnapshot(
    int schema,
    long nextPocketSlot,
    List<DoorPairIdentity> pairs,
    List<PlacedDoorEndpoint> endpoints,
    List<PocketSpace> spaces,
    List<ReturnTicket> returnTickets
) {
    public static final int CURRENT_SCHEMA = 1;

    public DoorStoreSnapshot {
        if (schema != CURRENT_SCHEMA) {
            throw new IllegalArgumentException("unsupported dimensional-door schema " + schema);
        }
        if (nextPocketSlot < 0) {
            throw new IllegalArgumentException("nextPocketSlot cannot be negative");
        }
        pairs = List.copyOf(Objects.requireNonNull(pairs, "pairs"));
        endpoints = List.copyOf(Objects.requireNonNull(endpoints, "endpoints"));
        spaces = List.copyOf(Objects.requireNonNull(spaces, "spaces"));
        returnTickets = List.copyOf(Objects.requireNonNull(returnTickets, "returnTickets"));
        validate(pairs, endpoints, spaces, returnTickets, nextPocketSlot);
    }

    public static DoorStoreSnapshot empty() {
        return new DoorStoreSnapshot(CURRENT_SCHEMA, 0, List.of(), List.of(), List.of(), List.of());
    }

    public Optional<ReturnTicket> returnTicket(UUID playerId) {
        Objects.requireNonNull(playerId, "playerId");
        return returnTickets.stream().filter(ticket -> ticket.playerId().equals(playerId)).findFirst();
    }

    public DoorStoreSnapshot withReturnTicket(ReturnTicket ticket) {
        Objects.requireNonNull(ticket, "ticket");
        ArrayList<ReturnTicket> updated = new ArrayList<>(returnTickets.size() + 1);
        for (ReturnTicket current : returnTickets) {
            if (!current.playerId().equals(ticket.playerId())) {
                updated.add(current);
            }
        }
        updated.add(ticket);
        return new DoorStoreSnapshot(schema, nextPocketSlot, pairs, endpoints, spaces, updated);
    }

    public DoorStoreSnapshot withoutReturnTicket(UUID playerId) {
        Objects.requireNonNull(playerId, "playerId");
        List<ReturnTicket> updated = returnTickets.stream()
            .filter(ticket -> !ticket.playerId().equals(playerId))
            .toList();
        return updated.size() == returnTickets.size()
            ? this
            : new DoorStoreSnapshot(schema, nextPocketSlot, pairs, endpoints, spaces, updated);
    }

    private static void validate(
        List<DoorPairIdentity> pairs,
        List<PlacedDoorEndpoint> endpoints,
        List<PocketSpace> spaces,
        List<ReturnTicket> returnTickets,
        long nextPocketSlot
    ) {
        Map<UUID, DoorPairIdentity> pairsById = new HashMap<>();
        Set<UUID> pairedItemIds = new HashSet<>();
        for (DoorPairIdentity pair : pairs) {
            if (pairsById.putIfAbsent(pair.pairId(), pair) != null) {
                throw new IllegalArgumentException("duplicate pair ID " + pair.pairId());
            }
            if (!pairedItemIds.add(pair.endpointAItemId()) || !pairedItemIds.add(pair.endpointBItemId())) {
                throw new IllegalArgumentException("door item appears in more than one pair");
            }
        }

        DoorRegistry registry = new DoorRegistry(endpoints);
        if (registry.size() != endpoints.size()) {
            throw new IllegalArgumentException("duplicate placed endpoint");
        }
        for (PlacedDoorEndpoint endpoint : endpoints) {
            DoorItemIdentity identity = endpoint.identity();
            if (identity.kind() != DoorKind.PAIRED) {
                if (pairedItemIds.contains(identity.itemId())) {
                    throw new IllegalArgumentException("paired item ID reused by another door identity");
                }
                continue;
            }
            DoorPairIdentity pair = pairsById.get(identity.pairId());
            if (pair == null || !pair.itemId(identity.pairEndpoint()).equals(identity.itemId())) {
                throw new IllegalArgumentException("placed pair endpoint does not match its pair identity");
            }
        }

        Set<UUID> spaceIds = new HashSet<>();
        Set<PocketBinding> bindings = new HashSet<>();
        Set<Long> slots = new HashSet<>();
        long highestSlot = -1;
        for (PocketSpace space : spaces) {
            if (!spaceIds.add(space.spaceId())) {
                throw new IllegalArgumentException("duplicate space ID " + space.spaceId());
            }
            if (!bindings.add(space.binding())) {
                throw new IllegalArgumentException("duplicate pocket binding " + space.binding());
            }
            if (!slots.add(space.slot())) {
                throw new IllegalArgumentException("duplicate pocket slot " + space.slot());
            }
            highestSlot = Math.max(highestSlot, space.slot());
        }
        if (nextPocketSlot <= highestSlot) {
            throw new IllegalArgumentException("nextPocketSlot must be greater than every allocated slot");
        }

        Set<UUID> players = new HashSet<>();
        for (ReturnTicket ticket : returnTickets) {
            if (!players.add(ticket.playerId())) {
                throw new IllegalArgumentException("duplicate return ticket for player " + ticket.playerId());
            }
        }
    }
}
