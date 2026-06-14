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
import static org.junit.jupiter.api.Assertions.assertTrue;

class TomlCodecNetworkTest {
    @TempDir
    Path tempDir;

    @Test
    void networkConfigWithPeersRoundTripsThroughFile() throws Exception {
        NetworkConfig original = new NetworkConfig();
        original.enabled = true;
        original.serverName = "anchor";
        original.role = "anchor";
        original.listenEnabled = true;
        original.advertiseHost = "10.0.0.1";
        original.listenHost = "10.0.0.1";
        original.listenPort = 9100;
        original.trustOnFirstUse = false;
        original.relayEnabled = true;
        original.transferMode = "packet";
        original.handoffTimeoutMs = 3000L;

        NetworkConfig.PeerEntry hub = new NetworkConfig.PeerEntry();
        hub.name = "hub";
        hub.host = "10.0.0.2";
        hub.port = 9101;
        hub.publicHost = "play.example.com";
        hub.publicPort = 25565;
        hub.relationship = "anchor";
        original.peers.add(hub);

        NetworkConfig.PeerEntry creative = new NetworkConfig.PeerEntry();
        creative.name = "creative";
        creative.host = "10.0.0.3";
        creative.port = 9102;
        creative.publicHost = "creative.example.com";
        creative.publicPort = 25566;
        original.peers.add(creative);

        File file = tempDir.resolve("network.toml").toFile();
        TomlCodec.writeCanonical(file, original);

        String written = Files.readString(file.toPath(), StandardCharsets.UTF_8);
        assertTrue(written.contains("[[peers]]"), "expected array-of-tables in:\n" + written);

        NetworkConfig loaded = TomlCodec.loadOrCreate(file, NetworkConfig.class);
        assertEquals(true, loaded.enabled);
        assertEquals("anchor", loaded.serverName);
        assertEquals("anchor", loaded.role);
        assertEquals(true, loaded.listenEnabled);
        assertEquals("10.0.0.1", loaded.advertiseHost);
        assertEquals("10.0.0.1", loaded.listenHost);
        assertEquals(9100, loaded.listenPort);
        assertEquals(false, loaded.trustOnFirstUse);
        assertEquals(true, loaded.relayEnabled);
        assertEquals("packet", loaded.transferMode);
        assertEquals(3000L, loaded.handoffTimeoutMs);
        assertEquals(2, loaded.peers.size());
        assertEquals("hub", loaded.peers.get(0).name);
        assertEquals("10.0.0.2", loaded.peers.get(0).host);
        assertEquals(9101, loaded.peers.get(0).port);
        assertEquals("play.example.com", loaded.peers.get(0).publicHost);
        assertEquals(25565, loaded.peers.get(0).publicPort);
        assertEquals("anchor", loaded.peers.get(0).relationship);
        assertEquals("creative", loaded.peers.get(1).name);
        assertEquals(25566, loaded.peers.get(1).publicPort);
    }

    @Test
    void defaultNetworkConfigCreatesFileWithEmptyPeers() throws Exception {
        File file = tempDir.resolve("network.toml").toFile();
        NetworkConfig loaded = TomlCodec.loadOrCreate(file, NetworkConfig.class);
        assertEquals(false, loaded.enabled);
        assertEquals(0, loaded.peers.size());
        assertTrue(file.isFile());
        String written = Files.readString(file.toPath(), StandardCharsets.UTF_8);
        assertTrue(!written.contains("view-depth"));
        assertTrue(!written.contains("view-entity-interval-ticks"));
    }

    @Test
    void rewriteOfLoadedConfigIsStable() throws Exception {
        NetworkConfig original = new NetworkConfig();
        NetworkConfig.PeerEntry peer = new NetworkConfig.PeerEntry();
        peer.name = "hub";
        peer.host = "10.0.0.2";
        original.peers.add(peer);

        File file = tempDir.resolve("network.toml").toFile();
        TomlCodec.writeCanonical(file, original);
        String first = Files.readString(file.toPath(), StandardCharsets.UTF_8);

        NetworkConfig loaded = TomlCodec.loadOrCreate(file, NetworkConfig.class);
        assertEquals(1, loaded.peers.size());
        String second = Files.readString(file.toPath(), StandardCharsets.UTF_8);
        assertEquals(first, second);
    }
}
