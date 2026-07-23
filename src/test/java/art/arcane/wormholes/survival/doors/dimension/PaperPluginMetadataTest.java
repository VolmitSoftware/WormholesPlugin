package art.arcane.wormholes.survival.doors.dimension;

import art.arcane.wormholes.Wormholes;
import org.bukkit.plugin.PluginDescriptionFile;
import org.bukkit.plugin.PluginLoadOrder;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
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
        assertTrue(metadata.contains("Iris:"));
        assertTrue(metadata.contains("load: BEFORE"));
        assertTrue(metadata.contains("required: false"));
        assertTrue(metadata.contains("join-classpath: true"));
        assertTrue(metadata.contains("wormholes.portals.wormhole:"));
        assertFalse(metadata.contains("commands:"), "Paper commands must be registered through lifecycle events");
    }

    @Test
    void bukkitMetadataDeclaresCommandsPermissionsAndOptionalPlaceholderApi() throws Exception {
        PluginDescriptionFile metadata;
        try (InputStream stream = PaperPluginMetadataTest.class.getResourceAsStream("/plugin.yml")) {
            assertNotNull(stream, "Processed plugin.yml is missing");
            metadata = new PluginDescriptionFile(stream);
        }

        assertEquals("Wormholes", metadata.getName());
        assertEquals("1.0.0-26.2", metadata.getVersion());
        assertEquals(Wormholes.class.getName(), metadata.getMain());
        assertEquals("26.2", metadata.getAPIVersion());
        assertEquals(PluginLoadOrder.POSTWORLD, metadata.getLoad());
        assertEquals(List.of("PlaceholderAPI", "Iris"), metadata.getSoftDepend());
        Map<String, Map<String, Object>> commands = metadata.getCommands();
        assertTrue(commands.containsKey("wormholes"));
        assertEquals(List.of("wh", "wormhole"), commands.get("wormholes").get("aliases"));
        assertTrue(metadata.getPermissions().stream().anyMatch(permission -> permission.getName().equals("wormholes.admin")));
        assertTrue(metadata.getPermissions().stream().anyMatch(permission -> permission.getName().equals("wormholes.portals.portal")));
    }

    @Test
    void processedProjectResourcesContainBothPluginMetadataFormats() throws Exception {
        URL paperMetadata = PaperPluginMetadataTest.class.getResource("/paper-plugin.yml");
        assertNotNull(paperMetadata, "Processed paper-plugin.yml is missing");
        assertTrue("file".equals(paperMetadata.getProtocol()), "Expected Gradle's processed resource directory");
        Path legacyMetadata = Path.of(paperMetadata.toURI()).resolveSibling("plugin.yml");
        assertTrue(Files.isRegularFile(legacyMetadata), "Processed resources are missing plugin.yml");
        String bukkitMetadata = Files.readString(legacyMetadata, StandardCharsets.UTF_8);
        assertFalse(bukkitMetadata.contains("${"), "Processed plugin.yml still contains unresolved properties");
    }
}
