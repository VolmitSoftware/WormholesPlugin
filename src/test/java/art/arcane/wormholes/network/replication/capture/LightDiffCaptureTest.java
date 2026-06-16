package art.arcane.wormholes.network.replication.capture;

import art.arcane.wormholes.network.replication.LightDiff;

import org.junit.jupiter.api.Test;

import java.util.Arrays;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LightDiffCaptureTest {
    @Test
    void chunkLightShadowDetectsChange() {
        ChunkLightShadow shadow = new ChunkLightShadow();
        byte[] sectionData = new byte[LightDiff.DATA_LENGTH];
        Arrays.fill(sectionData, (byte) 0x00);
        shadow.putBlockLight(4, sectionData);
        byte[] previous = shadow.getBlockLight(4);
        assertNotNull(previous);

        byte[] mutated = sectionData.clone();
        mutated[42] = (byte) 0xFF;
        assertFalse(Arrays.equals(previous, mutated));
        shadow.putBlockLight(4, mutated);
        byte[] post = shadow.getBlockLight(4);
        assertTrue(Arrays.equals(mutated, post));
    }

    @Test
    void chunkLightShadowIsolatesPerSection() {
        ChunkLightShadow shadow = new ChunkLightShadow();
        byte[] a = new byte[LightDiff.DATA_LENGTH];
        byte[] b = new byte[LightDiff.DATA_LENGTH];
        Arrays.fill(a, (byte) 0x11);
        Arrays.fill(b, (byte) 0x22);
        shadow.putBlockLight(1, a);
        shadow.putSkyLight(1, b);
        assertTrue(Arrays.equals(a, shadow.getBlockLight(1)));
        assertTrue(Arrays.equals(b, shadow.getSkyLight(1)));
    }

    @Test
    void chunkLightShadowReturnsNullForUnknown() {
        ChunkLightShadow shadow = new ChunkLightShadow();
        assertNull(shadow.getBlockLight(99));
    }
}
