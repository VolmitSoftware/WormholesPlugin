package art.arcane.wormholes.door;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

class PocketAllocatorTest {
    @Test
    void squareSpiralStartsInDeterministicOrder() {
        List<PocketCell> expected = List.of(
            new PocketCell(0, 0),
            new PocketCell(1, 0),
            new PocketCell(1, 1),
            new PocketCell(0, 1),
            new PocketCell(-1, 1),
            new PocketCell(-1, 0),
            new PocketCell(-1, -1),
            new PocketCell(0, -1),
            new PocketCell(1, -1),
            new PocketCell(2, -1),
            new PocketCell(2, 0),
            new PocketCell(2, 1),
            new PocketCell(2, 2)
        );

        for (int slot = 0; slot < expected.size(); slot++) {
            assertEquals(expected.get(slot), PocketAllocator.cellForSlot(slot), "slot " + slot);
        }
    }

    @Test
    void allocationsAreFarApartAndStableForBinding() {
        PocketAllocator allocator = new PocketAllocator();
        PocketBinding alice = PocketBinding.personal(id(1));
        PocketBinding publicDoor = PocketBinding.publicDoor(id(2));

        PocketSpace first = allocator.getOrAllocate(alice);
        PocketSpace repeated = allocator.getOrAllocate(alice);
        PocketSpace second = allocator.getOrAllocate(publicDoor);

        assertSame(first, repeated);
        assertEquals(0, first.slot());
        assertEquals(PocketAllocator.CHUNK_CENTER_OFFSET, first.centerX());
        assertEquals(PocketAllocator.CHUNK_CENTER_OFFSET, first.centerZ());
        assertEquals(1, second.slot());
        assertEquals(PocketAllocator.DEFAULT_STRIDE + PocketAllocator.CHUNK_CENTER_OFFSET, second.centerX());
        assertEquals(PocketAllocator.CHUNK_CENTER_OFFSET, second.centerZ());
        assertEquals(2, allocator.nextSlot());
        assertNotEquals(first.spaceId(), second.spaceId());
    }

    @Test
    void spaceIdIsDeterministicAcrossAllocatorInstances() {
        PocketBinding binding = PocketBinding.personal(id(10));
        PocketSpace first = new PocketAllocator().getOrAllocate(binding);
        PocketSpace second = new PocketAllocator().getOrAllocate(binding);

        assertEquals(first.spaceId(), second.spaceId());
        assertEquals(PocketAllocator.spaceIdFor(binding), first.spaceId());
    }

    @Test
    void restoreContinuesAfterHighestSlotWithoutReusingSpace() {
        PocketAllocator original = new PocketAllocator();
        PocketSpace first = original.getOrAllocate(PocketBinding.personal(id(20)));
        PocketSpace second = original.getOrAllocate(PocketBinding.publicDoor(id(21)));

        PocketAllocator restored = new PocketAllocator(
            original.stride(), original.centerY(), original.nextSlot(), original.spaces()
        );
        PocketSpace third = restored.getOrAllocate(PocketBinding.personal(id(22)));

        assertEquals(first, restored.find(first.binding()).orElseThrow());
        assertEquals(second, restored.find(second.binding()).orElseThrow());
        assertEquals(2, third.slot());
        assertEquals(PocketAllocator.DEFAULT_STRIDE + PocketAllocator.CHUNK_CENTER_OFFSET, third.centerX());
        assertEquals(PocketAllocator.DEFAULT_STRIDE + PocketAllocator.CHUNK_CENTER_OFFSET, third.centerZ());
    }

    @Test
    void invalidOrAmbiguousRestoresAreRejected() {
        PocketBinding binding = PocketBinding.personal(id(30));
        PocketSpace valid = new PocketSpace(
            PocketAllocator.spaceIdFor(binding), binding, 0,
            PocketAllocator.CHUNK_CENTER_OFFSET, PocketAllocator.DEFAULT_CENTER_Y, PocketAllocator.CHUNK_CENTER_OFFSET
        );
        PocketSpace wrongCoordinates = new PocketSpace(id(31), PocketBinding.publicDoor(id(32)), 1, 1, 128, 0);

        assertThrows(IllegalArgumentException.class,
            () -> new PocketAllocator(8_192, 128, 0, List.of(valid)));
        assertThrows(IllegalArgumentException.class,
            () -> new PocketAllocator(8_192, 128, 2, List.of(valid, valid)));
        assertThrows(IllegalArgumentException.class,
            () -> new PocketAllocator(8_192, 128, 2, List.of(valid, wrongCoordinates)));
        assertThrows(IllegalArgumentException.class,
            () -> PocketAllocator.cellForSlot(-1));
    }

    @Test
    void restoresLegacySchemaOneCoordinatesWithoutMovingExistingPocket() {
        PocketBinding binding = PocketBinding.personal(id(35));
        PocketSpace legacy = new PocketSpace(
            PocketAllocator.spaceIdFor(binding), binding, 0, 0, PocketAllocator.DEFAULT_CENTER_Y, 0
        );

        PocketAllocator restored = new PocketAllocator(8_192, 128, 1, List.of(legacy));

        assertEquals(legacy, restored.find(binding).orElseThrow());
        PocketSpace next = restored.getOrAllocate(PocketBinding.publicDoor(id(36)));
        assertEquals(8_192 + PocketAllocator.CHUNK_CENTER_OFFSET, next.centerX());
    }

    @Test
    void coordinateOverflowFailsInsteadOfWrapping() {
        PocketAllocator allocator = new PocketAllocator(Integer.MAX_VALUE - PocketAllocator.CHUNK_CENTER_OFFSET, 128);
        for (int slot = 0; slot < 9; slot++) {
            allocator.getOrAllocate(PocketBinding.personal(id(40 + slot)));
        }

        assertThrows(IllegalStateException.class,
            () -> allocator.getOrAllocate(PocketBinding.personal(id(60))));
    }

    private static UUID id(long value) {
        return new UUID(0, value);
    }
}
