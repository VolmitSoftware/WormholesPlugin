package art.arcane.wormholes.config;

import art.arcane.wormholes.config.toml.NetworkConfig;
import art.arcane.wormholes.util.project.config.TomlCodec;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TomlCodecNetworkTest {
    @TempDir
    Path tempDir;

    @Test
    void operatorSettingsRoundTripAndEveryKnobIsWritten() throws Exception {
        NetworkConfig original = new NetworkConfig();
        original.enabled = true;
        original.listenEnabled = true;
        original.listenPort = 9100;
        original.trustOnFirstUse = false;
        original.entityTransferDenyTypes = "ARMOR_STAND";
        original.advertiseHostOverride = "10.0.0.1";

        File file = tempDir.resolve("network.toml").toFile();
        TomlCodec.writeCanonical(file, original);

        String written = Files.readString(file.toPath(), StandardCharsets.UTF_8);
        assertFalse(written.contains("[[peers]]"), "network config should not expose static peers:\n" + written);
        assertTrue(written.contains("advertise-host-override"), "advertise-host-override must be written");
        assertTrue(written.contains("transfer-mode"), "transfer mode must be visible and configurable");
        assertTrue(written.contains("server-name"), "server name must be visible and configurable");
        assertTrue(written.contains("compression-enabled"), "transport tuning must be visible and configurable");

        NetworkConfig loaded = TomlCodec.loadOrCreate(file, NetworkConfig.class);
        assertEquals(true, loaded.enabled);
        assertEquals(true, loaded.listenEnabled);
        assertEquals(9100, loaded.listenPort);
        assertEquals(false, loaded.trustOnFirstUse);
        assertEquals("ARMOR_STAND", loaded.entityTransferDenyTypes);
        assertEquals("10.0.0.1", loaded.advertiseHostOverride);
    }

    @Test
    void defaultNetworkConfigCreatesFileWithoutPeers() throws Exception {
        File file = tempDir.resolve("network.toml").toFile();
        NetworkConfig loaded = TomlCodec.loadOrCreate(file, NetworkConfig.class);
        assertEquals(false, loaded.enabled);
        assertTrue(file.isFile());
        String written = Files.readString(file.toPath(), StandardCharsets.UTF_8);
        assertFalse(written.contains("[[peers]]"));
        assertFalse(written.contains("view-depth"));
        assertFalse(written.contains("view-entity-interval-ticks"));
    }

    @Test
    void rewriteOfLoadedConfigIsStable() throws Exception {
        NetworkConfig original = new NetworkConfig();
        original.enabled = true;
        original.listenPort = 9100;

        File file = tempDir.resolve("network.toml").toFile();
        TomlCodec.writeCanonical(file, original);
        String first = Files.readString(file.toPath(), StandardCharsets.UTF_8);

        NetworkConfig loaded = TomlCodec.loadOrCreate(file, NetworkConfig.class);
        assertEquals(9100, loaded.listenPort);
        assertEquals(true, loaded.enabled);
        String second = Files.readString(file.toPath(), StandardCharsets.UTF_8);
        assertEquals(first, second);
    }
}
