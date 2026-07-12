package art.arcane.wormholes.door;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DimensionalDoorRepositoryTest {
    @TempDir
    Path temporaryDirectory;

    @Test
    void missingRepositoryLoadsEmptyWithoutCreatingStateFile() throws Exception {
        DimensionalDoorRepository repository = DimensionalDoorRepository.under(temporaryDirectory);

        assertEquals(DoorStoreSnapshot.empty(), repository.load());
        assertFalse(Files.exists(repository.stateFile()));
    }

    @Test
    void completeStateRoundTripsAcrossFreshRepository() throws Exception {
        DoorPairIdentity pair = new DoorPairIdentity(id(1), id(2), id(3));
        PlacedDoorEndpoint endpointA = new PlacedDoorEndpoint(
            new DoorPosition(id(4), "minecraft:overworld", 1, 64, 2),
            pair.endpoint(PairEndpoint.A)
        );
        PlacedDoorEndpoint personal = new PlacedDoorEndpoint(
            new DoorPosition(id(5), "minecraft:the_end", -3, 70, 8),
            DoorItemIdentity.personal(id(6))
        );
        PocketAllocator allocator = new PocketAllocator();
        allocator.getOrAllocate(PocketBinding.personal(id(7)));
        allocator.getOrAllocate(PocketBinding.iron(id(8)));
        ReturnTicket ticket = ticket(id(9), id(6));
        DoorStoreSnapshot expected = new DoorStoreSnapshot(
            DoorStoreSnapshot.CURRENT_SCHEMA,
            allocator.nextSlot(),
            List.of(pair),
            List.of(endpointA, personal),
            allocator.spaces(),
            List.of(ticket)
        );
        Path stateFile = temporaryDirectory.resolve("custom-state.json");

        new DimensionalDoorRepository(stateFile).save(expected);
        DoorStoreSnapshot actual = new DimensionalDoorRepository(stateFile).load();

        assertEquals(expected, actual);
        assertTrue(Files.readString(stateFile).contains("\"nextPocketSlot\": 2"));
        try (Stream<Path> files = Files.list(temporaryDirectory)) {
            assertEquals(List.of(stateFile), files.toList(), "atomic temp file must be cleaned up");
        }
    }

    @Test
    void returnTicketApisReplacePersistAndRemovePerPlayer() throws Exception {
        Path stateFile = temporaryDirectory.resolve("state.json");
        DimensionalDoorRepository repository = new DimensionalDoorRepository(stateFile);
        ReturnTicket first = ticket(id(20), id(21));
        ReturnTicket replacement = new ReturnTicket(
            first.playerId(), id(22), id(23), "minecraft:the_nether", 9.5, 75, -4.5, 180, -10
        );

        repository.putReturnTicket(first);
        assertEquals(first, repository.getReturnTicket(first.playerId()).orElseThrow());
        repository.putReturnTicket(replacement);
        assertEquals(replacement, repository.getReturnTicket(first.playerId()).orElseThrow());
        assertEquals(1, repository.load().returnTickets().size());

        assertEquals(replacement, repository.removeReturnTicket(first.playerId()).orElseThrow());
        assertTrue(repository.getReturnTicket(first.playerId()).isEmpty());
        assertTrue(new DimensionalDoorRepository(stateFile).load().returnTickets().isEmpty());
        assertTrue(repository.removeReturnTicket(first.playerId()).isEmpty());
    }

    @Test
    void successiveAtomicSavesReplaceWholeSnapshot() throws Exception {
        Path stateFile = temporaryDirectory.resolve("state.json");
        DimensionalDoorRepository repository = new DimensionalDoorRepository(stateFile);
        repository.save(DoorStoreSnapshot.empty().withReturnTicket(ticket(id(30), id(31))));
        repository.save(DoorStoreSnapshot.empty());

        assertEquals(DoorStoreSnapshot.empty(), new DimensionalDoorRepository(stateFile).load());
    }

    @Test
    void malformedAndUnsupportedFilesFailLoudly() throws Exception {
        Path malformed = temporaryDirectory.resolve("malformed.json");
        Files.writeString(malformed, "{ definitely not json");
        assertThrows(IOException.class, () -> new DimensionalDoorRepository(malformed).load());

        Path unsupported = temporaryDirectory.resolve("unsupported.json");
        Files.writeString(unsupported, """
            {"schema": 999, "nextPocketSlot": 0, "pairs": [], "endpoints": [], "spaces": [], "returnTickets": []}
            """);
        assertThrows(IOException.class, () -> new DimensionalDoorRepository(unsupported).load());
    }

    @Test
    void malformedStateCanRecoverItsRetiredPocketSlot() throws Exception {
        Path stateFile = temporaryDirectory.resolve("state.json");
        Files.writeString(stateFile, "{\"nextPocketSlot\": 37, broken");

        DimensionalDoorRepository repository = new DimensionalDoorRepository(stateFile);

        assertThrows(IOException.class, repository::load);
        assertEquals(37L, repository.recoverNextPocketSlot());
    }

    @Test
    void recoveryNeverRegressesBelowTheHighestAllocatedSlot() throws Exception {
        Path stateFile = temporaryDirectory.resolve("state.json");
        Files.writeString(
            stateFile,
            "{\"nextPocketSlot\":1,\"spaces\":[{\"slot\":4},{\"slot\":9}], broken");

        assertEquals(10L, new DimensionalDoorRepository(stateFile).recoverNextPocketSlot());
    }

    @Test
    void unrecoverableStateFailsWithoutDiscardingSlotHistory() throws Exception {
        Path stateFile = temporaryDirectory.resolve("state.json");
        Files.writeString(stateFile, "not dimensional door state");

        assertThrows(
            IOException.class,
            () -> new DimensionalDoorRepository(stateFile).recoverNextPocketSlot());
    }

    @Test
    void snapshotRejectsCrossRecordIdentityAndAllocationCorruption() {
        DoorPairIdentity pair = new DoorPairIdentity(id(40), id(41), id(42));
        PlacedDoorEndpoint forged = new PlacedDoorEndpoint(
            new DoorPosition(id(43), "minecraft:overworld", 0, 64, 0),
            DoorItemIdentity.paired(id(44), pair.pairId(), PairEndpoint.A)
        );
        assertThrows(IllegalArgumentException.class, () -> new DoorStoreSnapshot(
            1, 0, List.of(pair), List.of(forged), List.of(), List.of()
        ));

        PocketBinding binding = PocketBinding.personal(id(45));
        PocketSpace space = new PocketSpace(id(46), binding, 0, 0, 128, 0);
        assertThrows(IllegalArgumentException.class, () -> new DoorStoreSnapshot(
            1, 0, List.of(), List.of(), List.of(space), List.of()
        ));
        assertThrows(IllegalArgumentException.class, () -> new DoorStoreSnapshot(
            1, 1, List.of(), List.of(), List.of(space), List.of(ticket(id(47), id(48)), ticket(id(47), id(49)))
        ));
    }

    @Test
    void snapshotDefensivelyCopiesListsAndTicketsValidateCoordinates() {
        ArrayList<ReturnTicket> mutableTickets = new ArrayList<>();
        DoorStoreSnapshot snapshot = new DoorStoreSnapshot(DoorStoreSnapshot.CURRENT_SCHEMA, 0, List.of(), List.of(), List.of(), mutableTickets);
        mutableTickets.add(ticket(id(50), id(51)));

        assertTrue(snapshot.returnTickets().isEmpty());
        assertThrows(UnsupportedOperationException.class,
            () -> snapshot.returnTickets().add(ticket(id(52), id(53))));
        assertThrows(IllegalArgumentException.class,
            () -> new ReturnTicket(id(54), id(55), id(56), "minecraft:overworld", Double.NaN, 0, 0, 0, 0));
    }

    private static ReturnTicket ticket(UUID playerId, UUID sourceEndpointId) {
        return new ReturnTicket(playerId, sourceEndpointId, id(100), "minecraft:overworld", 1.25, 65, -2.75, 90, 5);
    }

    private static UUID id(long value) {
        return new UUID(0, value);
    }
}
