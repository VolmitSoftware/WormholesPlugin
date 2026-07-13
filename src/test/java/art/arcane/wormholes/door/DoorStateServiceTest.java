package art.arcane.wormholes.door;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DoorStateServiceTest {
    @TempDir
    Path temporaryDirectory;

    @Test
    void loadsAndIndexesCompletePersistedSnapshot() throws Exception {
        DoorPairIdentity pair = pair(1);
        PlacedDoorEndpoint endpointA = placed(id(10), "minecraft:overworld", 1, 64, 2, pair.endpoint(PairEndpoint.A));
        PocketAllocator allocator = new PocketAllocator();
        PocketSpace pocket = allocator.getOrAllocate(PocketBinding.personal(id(11)));
        ReturnTicket ticket = ticket(id(12), endpointA.identity().itemId());
        DoorStoreSnapshot persisted = new DoorStoreSnapshot(
            DoorStoreSnapshot.CURRENT_SCHEMA,
            allocator.nextSlot(),
            List.of(pair),
            List.of(endpointA),
            List.of(pocket),
            List.of(ticket)
        );
        DimensionalDoorRepository repository = repository();
        repository.save(persisted);

        DoorStateService service = DoorStateService.load(new DimensionalDoorRepository(repository.stateFile()));

        assertEquals(pair, service.findPair(pair.pairId()).orElseThrow());
        assertEquals(endpointA, service.findEndpoint(endpointA.position()).orElseThrow());
        assertEquals(endpointA, service.findEndpointByItem(endpointA.identity().itemId()).orElseThrow());
        assertEquals(pocket, service.findPocket(pocket.binding()).orElseThrow());
        assertEquals(ticket, service.getReturnTicket(ticket.playerId()).orElseThrow());
        assertEquals(persisted, service.snapshot());
    }

    @Test
    void pairAndEndpointMutationsPersistAsOneCompleteState() throws Exception {
        DoorStateService service = DoorStateService.load(repository());
        DoorPairIdentity pair = pair(20);
        PlacedDoorEndpoint endpointA = placed(id(30), "minecraft:overworld", 0, 64, 0, pair.endpoint(PairEndpoint.A));
        PlacedDoorEndpoint endpointB = placed(id(31), "minecraft:the_nether", 5, 70, 5, pair.endpoint(PairEndpoint.B));

        assertTrue(service.registerPair(pair));
        assertFalse(service.registerPair(pair));
        assertTrue(service.registerEndpoint(endpointA));
        assertTrue(service.registerEndpoint(endpointB));
        assertEquals(endpointB, service.findMate(endpointA.identity()).orElseThrow());
        assertThrows(IllegalStateException.class, () -> service.removePair(pair.pairId()));

        assertEquals(endpointB, service.removeEndpoint(endpointB.position()).orElseThrow());
        assertEquals(endpointA, service.removeEndpoint(endpointA.position()).orElseThrow());
        assertEquals(pair, service.removePair(pair.pairId()).orElseThrow());

        DoorStateService restarted = DoorStateService.load(new DimensionalDoorRepository(service.repository().stateFile()));
        assertTrue(restarted.pairs().isEmpty());
        assertTrue(restarted.endpoints().isEmpty());
        assertEquals(service.snapshot(), restarted.snapshot());
    }

    @Test
    void pairedEndpointCannotBeRegisteredBeforeItsPairIdentity() throws Exception {
        DoorStateService service = DoorStateService.load(repository());
        DoorPairIdentity pair = pair(40);
        PlacedDoorEndpoint endpoint = placed(id(45), "minecraft:overworld", 0, 64, 0, pair.endpoint(PairEndpoint.A));

        assertThrows(IllegalArgumentException.class, () -> service.registerEndpoint(endpoint));
        assertTrue(service.endpoints().isEmpty());
        assertEquals(DoorStoreSnapshot.empty(), service.snapshot());
    }

    @Test
    void personalAndPublicPocketsResolveAndSurviveRestart() throws Exception {
        DoorStateService service = DoorStateService.load(repository());
        DoorItemIdentity personalA = DoorItemIdentity.personal(id(50));
        DoorItemIdentity personalB = DoorItemIdentity.personal(id(51));
        DoorItemIdentity publicDoor = DoorItemIdentity.publicDoor(id(52));
        UUID traveler = id(53);

        PocketSpace personal = service.getOrAllocatePocket(personalA, traveler);
        assertSame(personal, service.getOrAllocatePocket(personalB, traveler));
        PocketSpace publicPocket = service.getOrAllocatePocket(publicDoor, id(54));
        assertSame(publicPocket, service.getOrAllocatePocket(publicDoor, id(55)));
        assertEquals(0, personal.slot());
        assertEquals(1, publicPocket.slot());
        assertThrows(IllegalArgumentException.class,
            () -> service.getOrAllocatePocket(pair(56).endpoint(PairEndpoint.A), traveler));

        DoorStateService restarted = DoorStateService.load(new DimensionalDoorRepository(service.repository().stateFile()));
        assertEquals(personal, restarted.getOrAllocatePocket(personalA, traveler));
        assertEquals(publicPocket, restarted.getOrAllocatePocket(publicDoor, id(57)));
        assertEquals(2, restarted.snapshot().nextPocketSlot());
    }

    @Test
    void allocatorRestoresMonotonicNextSlotAndNeverFillsOldGaps() throws Exception {
        PocketBinding existingBinding = PocketBinding.personal(id(60));
        PocketSpace existing = new PocketSpace(
            PocketAllocator.spaceIdFor(existingBinding),
            existingBinding,
            0,
            PocketAllocator.CHUNK_CENTER_OFFSET,
            PocketAllocator.DEFAULT_CENTER_Y,
            PocketAllocator.CHUNK_CENTER_OFFSET
        );
        DoorStoreSnapshot stateWithRetiredGap = new DoorStoreSnapshot(
            DoorStoreSnapshot.CURRENT_SCHEMA, 4, List.of(), List.of(), List.of(existing), List.of()
        );
        DimensionalDoorRepository repository = repository();
        repository.save(stateWithRetiredGap);
        DoorStateService service = DoorStateService.load(new DimensionalDoorRepository(repository.stateFile()));

        PocketSpace allocated = service.getOrAllocatePocket(PocketBinding.publicDoor(id(61)));

        assertEquals(4, allocated.slot());
        assertEquals(5, service.snapshot().nextPocketSlot());
        assertEquals(5,
            DoorStateService.load(new DimensionalDoorRepository(repository.stateFile())).snapshot().nextPocketSlot());
    }

    @Test
    void ticketMutationsReplacePerPlayerAndPersistAcrossRestart() throws Exception {
        DoorStateService service = DoorStateService.load(repository());
        ReturnTicket first = ticket(id(70), id(71));
        ReturnTicket replacement = new ReturnTicket(
            first.playerId(), id(72), id(73), "minecraft:the_end", 9, 80, -9, 180, 0
        );

        service.putReturnTicket(first);
        service.putReturnTicket(replacement);
        assertEquals(List.of(replacement), service.returnTickets());
        assertEquals(replacement,
            DoorStateService.load(new DimensionalDoorRepository(service.repository().stateFile()))
                .getReturnTicket(first.playerId()).orElseThrow());

        assertEquals(replacement, service.removeReturnTicket(first.playerId()).orElseThrow());
        assertTrue(service.removeReturnTicket(first.playerId()).isEmpty());
        assertTrue(DoorStateService.load(new DimensionalDoorRepository(service.repository().stateFile()))
            .returnTickets().isEmpty());
    }

    @Test
    void publishedCollectionsAreReadOnlySnapshots() throws Exception {
        DoorStateService service = DoorStateService.load(repository());
        List<DoorPairIdentity> before = service.pairs();
        service.registerPair(pair(80));

        assertTrue(before.isEmpty(), "earlier collection must not become a live view");
        assertThrows(UnsupportedOperationException.class, () -> service.pairs().clear());
        assertThrows(UnsupportedOperationException.class, () -> service.endpoints().clear());
        assertThrows(UnsupportedOperationException.class, () -> service.spaces().clear());
        assertThrows(UnsupportedOperationException.class, () -> service.returnTickets().clear());
    }

    @Test
    void persistenceFailureDoesNotPublishCandidateMutation() throws Exception {
        Path parentThatIsAFile = temporaryDirectory.resolve("not-a-directory");
        Files.writeString(parentThatIsAFile, "occupied");
        DimensionalDoorRepository repository = new DimensionalDoorRepository(parentThatIsAFile.resolve("state.json"));
        DoorStateService service = DoorStateService.load(repository);
        DoorPairIdentity pair = pair(90);

        assertThrows(IOException.class, () -> service.registerPair(pair));
        assertTrue(service.pairs().isEmpty());
        assertEquals(DoorStoreSnapshot.empty(), service.snapshot());
    }

    @Test
    void duplicateEndpointFailureLeavesExistingStateIntact() throws Exception {
        DoorStateService service = DoorStateService.load(repository());
        PlacedDoorEndpoint first = placed(id(100), "minecraft:overworld", 0, 64, 0, DoorItemIdentity.publicDoor(id(101)));
        PlacedDoorEndpoint conflicting = placed(id(100), "minecraft:overworld", 0, 64, 0, DoorItemIdentity.publicDoor(id(102)));
        service.registerEndpoint(first);

        assertThrows(IllegalStateException.class, () -> service.registerEndpoint(conflicting));
        assertEquals(List.of(first), service.endpoints());
        assertEquals(List.of(first),
            DoorStateService.load(new DimensionalDoorRepository(service.repository().stateFile())).endpoints());
    }

    private DimensionalDoorRepository repository() {
        return new DimensionalDoorRepository(temporaryDirectory.resolve("state.json"));
    }

    private static DoorPairIdentity pair(long base) {
        return new DoorPairIdentity(id(base), id(base + 1), id(base + 2));
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

    private static ReturnTicket ticket(UUID playerId, UUID endpointId) {
        return new ReturnTicket(playerId, endpointId, id(999), "minecraft:overworld", 1.5, 65, -2.5, 90, 5);
    }

    private static UUID id(long value) {
        return new UUID(0, value);
    }
}
