package art.arcane.wormholes.survival.doors.dimension;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

public final class PaperPluginMetadataTest {
    @Test
    void paperMetadataDeclaresBootstrapFoliaAndOptionalPlaceholderApi() throws IOException {
        String metadata;
        try (InputStream stream = PaperPluginMetadataTest.class.getResourceAsStream("/paper-plugin.yml")) {
            assertNotNull(stream, "Processed paper-plugin.yml is missing");
            metadata = new String(stream.readAllBytes(), StandardCharsets.UTF_8);
        }

        assertTrue(metadata.contains("bootstrapper: " + WormholesBootstrap.class.getName()));
        assertTrue(metadata.contains("folia-supported: true"));
        assertTrue(metadata.contains("PlaceholderAPI:"));
        assertTrue(metadata.contains("load: BEFORE"));
        assertTrue(metadata.contains("required: false"));
        assertTrue(metadata.contains("join-classpath: true"));
        assertTrue(metadata.contains("wormholes.portals.wormhole:"));
        assertFalse(metadata.contains("commands:"), "Paper commands must be registered through lifecycle events");
    }

    @Test
    void processedProjectResourcesDoNotContainLegacyPluginMetadata() throws Exception {
        URL paperMetadata = PaperPluginMetadataTest.class.getResource("/paper-plugin.yml");
        assertNotNull(paperMetadata, "Processed paper-plugin.yml is missing");
        assertTrue("file".equals(paperMetadata.getProtocol()), "Expected Gradle's processed resource directory");
        Path legacyMetadata = Path.of(paperMetadata.toURI()).resolveSibling("plugin.yml");
        assertFalse(Files.exists(legacyMetadata), "Processed resources still contain plugin.yml");
    }
}
