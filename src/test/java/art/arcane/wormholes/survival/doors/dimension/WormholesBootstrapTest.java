package art.arcane.wormholes.survival.doors.dimension;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;

public final class WormholesBootstrapTest {
    @Test
    void doesNotRegisterPackThroughPaperDatapackDiscoveryLifecycle() throws IOException {
        String classResource = "/" + WormholesBootstrap.class.getName().replace('.', '/') + ".class";
        try (InputStream input = WormholesBootstrapTest.class.getResourceAsStream(classResource)) {
            assertNotNull(input, "Compiled Wormholes bootstrap class is missing");
            String classConstants = new String(input.readAllBytes(), StandardCharsets.ISO_8859_1);
            assertFalse(classConstants.contains("DATAPACK_DISCOVERY"));
            assertFalse(classConstants.contains("io/papermc/paper/datapack"));
            assertFalse(classConstants.contains("getLogger"));
        }
    }
}
