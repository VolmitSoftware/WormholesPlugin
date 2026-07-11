package art.arcane.wormholes.door;

import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PocketLayoutTest {
    @Test
    void coreIsNineByNineWithThreeBlockInitialInterior() {
        PocketLayout layout = layout(1, 8_192, 128, -16_384);

        assertEquals(8_188, layout.minX());
        assertEquals(8_196, layout.maxX());
        assertEquals(-16_388, layout.minZ());
        assertEquals(-16_380, layout.maxZ());
        assertEquals(127, layout.platformY());
        assertEquals(128, layout.clearMinY());
        assertEquals(130, layout.clearMaxY());

        int platformBlocks = 0;
        int interiorBlocks = 0;
        for (int x = 8_187; x <= 8_197; x++) {
            for (int y = 127; y <= 131; y++) {
                for (int z = -16_389; z <= -16_379; z++) {
                    if (layout.isPlatformBlock(x, y, z)) {
                        platformBlocks++;
                    }
                    if (layout.isInitialInteriorBlock(x, y, z)) {
                        interiorBlocks++;
                    }
                }
            }
        }
        assertEquals(81, platformBlocks);
        assertEquals(243, interiorBlocks);
    }

    @Test
    void returnDoorAndEntryUseFixedSafeOffsets() {
        PocketLayout layout = layout(10, 100, 80, -200);

        assertEquals(new PocketBlockPosition(100, 80, -197), layout.returnDoorLower());
        assertEquals(new PocketBlockPosition(100, 81, -197), layout.returnDoorUpper());
        assertEquals(new PocketBlockPosition(100, 79, -197), layout.returnDoorSupport());
        assertEquals(new PocketEntryCoordinates(100.5, 80, -199.5, 0, 0), layout.entry());
    }

    @Test
    void returnIdentityIsStablePerSpaceAndDifferentAcrossSpaces() {
        PocketLayout firstInstance = layout(20, 0, 128, 0);
        PocketLayout sameSpace = layout(20, 8_192, 128, 8_192);
        PocketLayout anotherSpace = layout(21, 0, 128, 0);

        DoorItemIdentity first = firstInstance.returnDoorIdentity();
        assertEquals(DoorKind.RETURN, first.kind());
        assertEquals(firstInstance.space().spaceId(), first.spaceId());
        assertEquals(first, sameSpace.returnDoorIdentity(), "coordinates must not affect return-door identity");
        assertNotEquals(first.itemId(), anotherSpace.returnDoorIdentity().itemId());
    }

    @Test
    void protectionCoversOnlyCoreFloorSupportAndDoor() {
        PocketLayout layout = layout(30, 0, 128, 0);

        assertTrue(layout.isProtected(-4, 127, -4));
        assertTrue(layout.isProtected(4, 127, 4));
        assertTrue(layout.isProtected(0, 127, 3), "door support is part of the floor");
        assertTrue(layout.isProtected(0, 128, 3));
        assertTrue(layout.isProtected(0, 129, 3));

        assertFalse(layout.isProtected(0, 128, 0), "ordinary interior remains buildable");
        assertFalse(layout.isProtected(0, 130, 3), "air above the exit is not protected");
        assertFalse(layout.isProtected(5, 127, 0));
        assertFalse(layout.isProtected(0, 126, 3));
    }

    @Test
    void coordinateOverflowIsRejectedInsteadOfWrapping() {
        PocketSpace edge = new PocketSpace(
            id(40), PocketBinding.personal(id(41)), 0, Integer.MAX_VALUE, 128, 0
        );
        PocketLayout layout = new PocketLayout(edge);
        assertThrows(ArithmeticException.class, layout::maxX);
    }

    @Test
    void entryCoordinatesRejectNonFiniteValues() {
        assertThrows(IllegalArgumentException.class,
            () -> new PocketEntryCoordinates(Double.NaN, 0, 0, 0, 0));
        assertThrows(IllegalArgumentException.class,
            () -> new PocketEntryCoordinates(0, 0, 0, Float.POSITIVE_INFINITY, 0));
    }

    private static PocketLayout layout(long spaceId, int centerX, int centerY, int centerZ) {
        PocketBinding binding = PocketBinding.personal(id(spaceId + 1_000));
        return new PocketLayout(new PocketSpace(id(spaceId), binding, 0, centerX, centerY, centerZ));
    }

    private static UUID id(long value) {
        return new UUID(0, value);
    }
}
