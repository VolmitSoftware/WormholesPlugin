package art.arcane.wormholes.door;

import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DoorIdentityTest {
    @Test
    void pairedKitMintsDistinctMatchingEndpoints() {
        DoorPairIdentity pair = new DoorPairIdentity(id(1), id(2), id(3));

        DoorItemIdentity endpointA = pair.endpoint(PairEndpoint.A);
        DoorItemIdentity endpointB = pair.endpoint(PairEndpoint.B);

        assertEquals(DoorKind.PAIRED, endpointA.kind());
        assertEquals(pair.pairId(), endpointA.pairId());
        assertEquals(PairEndpoint.A, endpointA.pairEndpoint());
        assertEquals(PairEndpoint.B, endpointA.pairEndpoint().other());
        assertNotEquals(endpointA.itemId(), endpointB.itemId());
        assertTrue(pair.contains(endpointA.itemId()));
        assertTrue(pair.contains(endpointB.itemId()));
    }

    @Test
    void pairedDestinationIsAlwaysTheOtherEndpoint() {
        DoorPairIdentity pair = new DoorPairIdentity(id(10), id(11), id(12));

        assertEquals(
            new PairedDoorDestination(pair.pairId(), PairEndpoint.B),
            DoorDestinationResolver.resolve(pair.endpoint(PairEndpoint.A), id(13))
        );
        assertEquals(
            new PairedDoorDestination(pair.pairId(), PairEndpoint.A),
            DoorDestinationResolver.resolve(pair.endpoint(PairEndpoint.B), id(13))
        );
    }

    @Test
    void personalDestinationDependsOnTravelerNotDoorItem() {
        UUID traveler = id(20);
        DoorDestination firstDoor = DoorDestinationResolver.resolve(DoorItemIdentity.personal(id(21)), traveler);
        DoorDestination secondDoor = DoorDestinationResolver.resolve(DoorItemIdentity.personal(id(22)), traveler);
        DoorDestination anotherTraveler = DoorDestinationResolver.resolve(DoorItemIdentity.personal(id(21)), id(23));

        assertEquals(new PocketDoorDestination(PocketBinding.personal(traveler)), firstDoor);
        assertEquals(firstDoor, secondDoor);
        assertNotEquals(firstDoor, anotherTraveler);
    }

    @Test
    void ironDestinationDependsOnImmutableItemIdentity() {
        DoorItemIdentity ironDoor = DoorItemIdentity.iron(id(30));

        DoorDestination beforeMoving = DoorDestinationResolver.resolve(ironDoor, id(31));
        DoorDestination afterMoving = DoorDestinationResolver.resolve(ironDoor, id(32));
        DoorDestination separatelyCrafted = DoorDestinationResolver.resolve(DoorItemIdentity.iron(id(33)), id(31));

        assertEquals(new PocketDoorDestination(PocketBinding.iron(ironDoor.itemId())), beforeMoving);
        assertEquals(beforeMoving, afterMoving, "traveler must not affect an iron door's pocket");
        assertNotEquals(beforeMoving, separatelyCrafted);
    }

    @Test
    void returnDestinationCarriesSpaceAndTraveler() {
        DoorItemIdentity exit = DoorItemIdentity.returnDoor(id(40), id(41));
        assertEquals(
            new ReturnDoorDestination(id(41), id(42)),
            DoorDestinationResolver.resolve(exit, id(42))
        );
    }

    @Test
    void malformedIdentityCombinationsAreRejected() {
        assertThrows(NullPointerException.class,
            () -> DoorItemIdentity.paired(id(50), null, PairEndpoint.A));
        assertThrows(NullPointerException.class,
            () -> DoorItemIdentity.paired(id(50), id(51), null));
        assertThrows(IllegalArgumentException.class,
            () -> new DoorItemIdentity(id(50), DoorKind.IRON, id(51), null, null));
        assertThrows(NullPointerException.class,
            () -> new DoorItemIdentity(id(50), DoorKind.RETURN, null, null, null));
        assertThrows(IllegalArgumentException.class,
            () -> new DoorPairIdentity(id(50), id(51), id(51)));
    }

    private static UUID id(long value) {
        return new UUID(0, value);
    }
}
