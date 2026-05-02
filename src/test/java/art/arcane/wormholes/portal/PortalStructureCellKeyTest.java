package art.arcane.wormholes.portal;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

public final class PortalStructureCellKeyTest {
    @Test
    public void blockKeyRoundTripPreservesSignedCoordinates() {
        assertBlockKeyRoundTrip(0, 64, 0);
        assertBlockKeyRoundTrip(-301, -64, 2048);
        assertBlockKeyRoundTrip(120000, 319, -120000);
    }

    private static void assertBlockKeyRoundTrip(int x, int y, int z) {
        long key = PortalStructure.packBlockKey(x, y, z);

        assertEquals(x, PortalStructure.unpackBlockX(key));
        assertEquals(y, PortalStructure.unpackBlockY(key));
        assertEquals(z, PortalStructure.unpackBlockZ(key));
    }
}
