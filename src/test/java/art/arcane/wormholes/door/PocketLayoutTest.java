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
    void roomIsExactlyThirtyTwoBlocksOnEveryAxisAndTwoChunksWide() {
        PocketLayout positive = layout(1, 8_200, 128, 16_392);
        PocketLayout negative = layout(2, -8_184, 128, -16_376);

        assertRoomBounds(positive, 8_192, 16_384, 127);
        assertRoomBounds(negative, -8_192, -16_384, 127);
    }

    @Test
    void currentAndLegacyStarterCoresRemainInsideTheBuildableInterior() {
        PocketLayout current = layout(4, 8, 128, 8);
        PocketLayout legacy = layout(5, 0, 128, 0);

        assertEquals(0, current.minX());
        assertEquals(0, current.minZ());
        assertEquals(-16, legacy.minX());
        assertEquals(-16, legacy.minZ());
        assertLegacyCoreInside(current, 8, 8);
        assertLegacyCoreInside(legacy, 0, 0);
    }

    @Test
    void shellAndInteriorClassifyTheCompleteRoomWithoutLeakingOutside() {
        PocketLayout layout = layout(3, 8, 128, 8);
        int contained = 0;
        int shell = 0;
        int interior = 0;

        for (int x = layout.minX() - 1; x <= layout.maxX() + 1; x++) {
            for (int y = layout.minY() - 1; y <= layout.maxY() + 1; y++) {
                for (int z = layout.minZ() - 1; z <= layout.maxZ() + 1; z++) {
                    if (layout.contains(x, y, z)) {
                        contained++;
                    }
                    if (layout.isShellBlock(x, y, z)) {
                        shell++;
                    }
                    if (layout.isInteriorBlock(x, y, z)) {
                        interior++;
                    }
                }
            }
        }

        assertEquals(32_768, contained);
        assertEquals(5_768, shell);
        assertEquals(27_000, interior);
        assertTrue(layout.isShellBlock(layout.minX(), layout.minY(), layout.minZ()));
        assertTrue(layout.isShellBlock(layout.maxX(), layout.maxY(), layout.maxZ()));
        assertTrue(layout.isInteriorBlock(layout.minX() + 1, layout.minY() + 1, layout.minZ() + 1));
        assertTrue(layout.isInteriorBlock(layout.maxX() - 1, layout.maxY() - 1, layout.maxZ() - 1));
        assertFalse(layout.contains(layout.minX() - 1, layout.minY(), layout.minZ()));
        assertFalse(layout.isShellBlock(layout.maxX() + 1, layout.maxY(), layout.maxZ()));
    }

    @Test
    void returnDoorIsGroundedInTheCenterOfTheSouthWall() {
        PocketLayout layout = layout(10, 100, 80, -200);
        PocketBlockPosition lower = layout.returnDoorLower();
        PocketBlockPosition upper = layout.returnDoorUpper();
        PocketBlockPosition support = layout.returnDoorSupport();
        PocketEntryCoordinates entry = layout.entry();

        assertEquals(1, Math.abs(2 * lower.x() - (layout.minX() + layout.maxX())));
        assertEquals(layout.maxZ(), lower.z());
        assertEquals(layout.minY() + 1, lower.y());
        assertEquals(new PocketBlockPosition(lower.x(), lower.y() + 1, lower.z()), upper);
        assertEquals(new PocketBlockPosition(lower.x(), layout.minY(), lower.z()), support);
        assertEquals(lower.x() + 0.5D, entry.x(), 0.0D);
        assertEquals(lower.y(), entry.y(), 0.0D);
        assertEquals(lower.z() - 0.5D, entry.z(), 0.0D);
        assertTrue(layout.isShellBlock(lower.x(), lower.y(), lower.z()));
        assertTrue(layout.isInteriorBlock(floor(entry.x()), floor(entry.y()), floor(entry.z())));
    }

    @Test
    void protectionCoversEveryWallFloorAndCeilingButNotTheInterior() {
        PocketLayout layout = layout(30, 8, 128, 8);

        assertTrue(layout.isProtected(layout.minX(), layout.minY() + 1, layout.minZ() + 1));
        assertTrue(layout.isProtected(layout.maxX(), layout.minY() + 1, layout.minZ() + 1));
        assertTrue(layout.isProtected(layout.minX() + 1, layout.minY(), layout.minZ() + 1));
        assertTrue(layout.isProtected(layout.minX() + 1, layout.maxY(), layout.minZ() + 1));
        assertTrue(layout.isProtected(layout.minX() + 1, layout.minY() + 1, layout.minZ()));
        assertTrue(layout.isProtected(layout.minX() + 1, layout.minY() + 1, layout.maxZ()));
        assertFalse(layout.isProtected(layout.minX() + 1, layout.minY() + 1, layout.minZ() + 1));
        assertFalse(layout.isProtected(layout.minX() - 1, layout.minY(), layout.minZ()));
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
    void coordinateOverflowIsRejectedInsteadOfWrapping() {
        PocketLayout horizontalEdge = layout(40, Integer.MAX_VALUE, 128, 0);
        PocketLayout verticalEdge = layout(41, 0, Integer.MAX_VALUE, 0);

        assertThrows(ArithmeticException.class, horizontalEdge::maxX);
        assertThrows(ArithmeticException.class, verticalEdge::maxY);
    }

    @Test
    void entryCoordinatesRejectNonFiniteValues() {
        assertThrows(IllegalArgumentException.class,
            () -> new PocketEntryCoordinates(Double.NaN, 0, 0, 0, 0));
        assertThrows(IllegalArgumentException.class,
            () -> new PocketEntryCoordinates(0, 0, 0, Float.POSITIVE_INFINITY, 0));
    }

    private static void assertRoomBounds(PocketLayout layout, int expectedMinX, int expectedMinZ, int expectedMinY) {
        assertEquals(expectedMinX, layout.minX());
        assertEquals(expectedMinX + 31, layout.maxX());
        assertEquals(expectedMinZ, layout.minZ());
        assertEquals(expectedMinZ + 31, layout.maxZ());
        assertEquals(expectedMinY, layout.minY());
        assertEquals(expectedMinY + 31, layout.maxY());
        assertEquals(32, layout.maxX() - layout.minX() + 1);
        assertEquals(32, layout.maxY() - layout.minY() + 1);
        assertEquals(32, layout.maxZ() - layout.minZ() + 1);
        assertEquals(1, (layout.maxX() >> 4) - (layout.minX() >> 4));
        assertEquals(1, (layout.maxZ() >> 4) - (layout.minZ() >> 4));
    }

    private static void assertLegacyCoreInside(PocketLayout layout, int centerX, int centerZ) {
        assertTrue(layout.isShellBlock(centerX - 4, 127, centerZ - 4));
        assertTrue(layout.isShellBlock(centerX + 4, 127, centerZ + 4));
        assertTrue(layout.isInteriorBlock(centerX - 4, 128, centerZ - 4));
        assertTrue(layout.isInteriorBlock(centerX + 4, 130, centerZ + 4));
    }

    private static PocketLayout layout(long spaceId, int centerX, int centerY, int centerZ) {
        PocketBinding binding = PocketBinding.personal(id(spaceId + 1_000));
        return new PocketLayout(new PocketSpace(id(spaceId), binding, 0, centerX, centerY, centerZ));
    }

    private static int floor(double value) {
        return (int) Math.floor(value);
    }

    private static UUID id(long value) {
        return new UUID(0, value);
    }
}
