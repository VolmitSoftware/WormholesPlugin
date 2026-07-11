package art.arcane.wormholes.door;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DoorRegistryTest {
    @Test
    void placementLookupUsesWorldUuidAndLowerCoordinates() {
        UUID world = id(1);
        PlacedDoorEndpoint endpoint = placed(world, "world", 4, 64, -9, DoorItemIdentity.iron(id(2)));
        DoorRegistry registry = new DoorRegistry();

        assertTrue(registry.register(endpoint));
        assertFalse(registry.register(endpoint), "replaying the same placement is idempotent");
        assertEquals(endpoint, registry.at(world, 4, 64, -9).orElseThrow());
        assertEquals(endpoint,
            registry.at(new DoorPosition(world, "renamed-world", 4, 64, -9)).orElseThrow(),
            "world name is diagnostic and must not invalidate a stable UUID placement"
        );
        assertTrue(registry.at(world, 4, 65, -9).isEmpty(), "upper half is not the placement key");
        assertTrue(registry.at(id(3), 4, 64, -9).isEmpty());
    }

    @Test
    void duplicateBlockItemAndPairSideAreRejected() {
        UUID world = id(10);
        DoorPairIdentity pair = new DoorPairIdentity(id(11), id(12), id(13));
        DoorRegistry registry = new DoorRegistry();
        registry.register(placed(world, "world", 0, 64, 0, pair.endpoint(PairEndpoint.A)));

        assertThrows(IllegalStateException.class,
            () -> registry.register(placed(world, "world", 0, 64, 0, DoorItemIdentity.iron(id(14)))));
        assertThrows(IllegalStateException.class,
            () -> registry.register(placed(world, "world", 1, 64, 0, pair.endpoint(PairEndpoint.A))));
        assertThrows(IllegalStateException.class,
            () -> registry.register(placed(world, "world", 2, 64, 0,
                DoorItemIdentity.paired(id(15), pair.pairId(), PairEndpoint.A))));
    }

    @Test
    void mateLookupActivatesOnlyWhenBothExactPairSidesArePlaced() {
        UUID world = id(20);
        DoorPairIdentity pair = new DoorPairIdentity(id(21), id(22), id(23));
        PlacedDoorEndpoint a = placed(world, "world", 0, 64, 0, pair.endpoint(PairEndpoint.A));
        PlacedDoorEndpoint b = placed(id(24), "world_nether", 8, 70, 8, pair.endpoint(PairEndpoint.B));
        DoorRegistry registry = new DoorRegistry(List.of(a));

        assertTrue(registry.mateOf(a.identity()).isEmpty());
        registry.register(b);
        assertEquals(b, registry.mateOf(a.identity()).orElseThrow());
        assertEquals(a, registry.mateOf(b.identity()).orElseThrow());

        assertEquals(b, registry.remove(b.position()).orElseThrow());
        assertTrue(registry.mateOf(a.identity()).isEmpty());
        assertEquals(1, registry.size());
    }

    @Test
    void nonPairedDoorsNeverHaveRegistryMates() {
        DoorRegistry registry = new DoorRegistry();
        assertTrue(registry.mateOf(DoorItemIdentity.personal(id(30))).isEmpty());
        assertTrue(registry.mateOf(DoorItemIdentity.iron(id(31))).isEmpty());
    }

    private static PlacedDoorEndpoint placed(
        UUID worldId,
        String worldName,
        int x,
        int y,
        int z,
        DoorItemIdentity identity
    ) {
        return new PlacedDoorEndpoint(new DoorPosition(worldId, worldName, x, y, z), identity);
    }

    private static UUID id(long value) {
        return new UUID(0, value);
    }
}
