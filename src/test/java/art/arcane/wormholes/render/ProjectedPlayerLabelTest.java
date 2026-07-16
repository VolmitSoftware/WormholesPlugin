package art.arcane.wormholes.render;

import com.github.retrooper.packetevents.util.Vector3d;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

public final class ProjectedPlayerLabelTest {
    @Test
    public void labelMetadataIsWhiteFullBrightAndTransparent() {
        ProjectedEntityRenderer.PlayerLabelMetadataSpec spec = ProjectedEntityRenderer.playerLabelMetadataSpec("Alice");

        assertEquals(10, spec.interpolationIndex());
        assertEquals(3, spec.interpolationTicks());
        assertEquals(15, spec.billboardIndex());
        assertEquals((byte) 3, spec.billboard());
        assertEquals(16, spec.brightnessIndex());
        assertEquals(15_728_880, spec.brightness());
        assertEquals(23, spec.textIndex());
        assertEquals(Component.text("Alice", NamedTextColor.WHITE), spec.text());
        assertEquals(25, spec.backgroundIndex());
        assertEquals(0, spec.background());
    }

    @Test
    public void labelPositionTracksPlayerHeight() {
        Vector3d position = ProjectedEntityRenderer.playerLabelPosition(new Vector3d(12.5D, 64.0D, -7.25D), 1.8D);

        assertEquals(12.5D, position.getX(), 1.0E-9D);
        assertEquals(66.3D, position.getY(), 1.0E-9D);
        assertEquals(-7.25D, position.getZ(), 1.0E-9D);
    }

    @Test
    public void projectedProfileDoesNotReuseVisibleSourceName() {
        UUID firstUuid = UUID.fromString("12345678-1234-5678-90ab-cdef12345678");
        UUID secondUuid = UUID.fromString("abcdef12-3456-7890-abcd-ef1234567890");

        String first = ProjectedEntityRenderer.projectedProfileName("Alice", firstUuid, false);
        String second = ProjectedEntityRenderer.projectedProfileName("Alice", secondUuid, false);

        assertEquals(16, first.length());
        assertTrue(first.matches("wh[0-9a-f]{14}"));
        assertNotEquals("Alice", first);
        assertNotEquals(first, second);
        assertEquals("Dinnerbone", ProjectedEntityRenderer.projectedProfileName("Alice", firstUuid, true));
        assertEquals("PortalPlayer", ProjectedEntityRenderer.projectedProfileName("Dinnerbone", firstUuid, true));
    }

    @Test
    public void blankAndOversizedLabelsAreSafeAndNonempty() {
        assertEquals("PortalPlayer", ProjectedEntityRenderer.playerLabelText(" "));
        assertEquals("abcdefghijklmnop", ProjectedEntityRenderer.playerLabelText("abcdefghijklmnop-extra"));
    }
}
