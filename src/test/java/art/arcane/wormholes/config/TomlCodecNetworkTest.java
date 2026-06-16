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
    void networkConfigRuntimeSettingsRoundTripsThroughFile() throws Exception {
        NetworkConfig original = new NetworkConfig();
        original.enabled = true;
        original.serverName = "anchor";
        original.listenEnabled = true;
        original.advertiseHostOverride = "10.0.0.1";
        original.listenPort = 9100;
        original.trustOnFirstUse = false;
        original.transferMode = "packet";
        original.handoffTimeoutMs = 3000L;

        File file = tempDir.resolve("network.toml").toFile();
        TomlCodec.writeCanonical(file, original);

        String written = Files.readString(file.toPath(), StandardCharsets.UTF_8);
        assertFalse(written.contains("[[peers]]"), "network.toml should not expose static peers:\n" + written);

        NetworkConfig loaded = TomlCodec.loadOrCreate(file, NetworkConfig.class);
        assertEquals(true, loaded.enabled);
        assertEquals("anchor", loaded.serverName);
        assertEquals(true, loaded.listenEnabled);
        assertEquals("10.0.0.1", loaded.advertiseHostOverride);
        assertEquals(9100, loaded.listenPort);
        assertEquals(false, loaded.trustOnFirstUse);
        assertEquals("packet", loaded.transferMode);
        assertEquals(3000L, loaded.handoffTimeoutMs);
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
        original.serverName = "hub";
        original.listenPort = 9100;

        File file = tempDir.resolve("network.toml").toFile();
        TomlCodec.writeCanonical(file, original);
        String first = Files.readString(file.toPath(), StandardCharsets.UTF_8);

        NetworkConfig loaded = TomlCodec.loadOrCreate(file, NetworkConfig.class);
        assertEquals("hub", loaded.serverName);
        String second = Files.readString(file.toPath(), StandardCharsets.UTF_8);
        assertEquals(first, second);
    }
}
