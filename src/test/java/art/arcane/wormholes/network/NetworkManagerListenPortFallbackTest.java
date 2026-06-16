package art.arcane.wormholes.network;

import art.arcane.wormholes.config.toml.NetworkConfig;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.net.ServerSocket;
import java.nio.file.Path;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

@Timeout(20)
class NetworkManagerListenPortFallbackTest {
    private static final Logger LOGGER = Logger.getLogger("NetworkManagerListenPortFallbackTest");

    @TempDir
    Path tempDir;

    private NetworkManager manager;
    private ServerSocket blocker;

    @AfterEach
    void tearDown() throws IOException {
        if (manager != null) {
            manager.stop();
        }
        if (blocker != null && !blocker.isClosed()) {
            blocker.close();
        }
    }

    @Test
    void listenPortAutoFallsBackOverRange() throws IOException {
        int basePort;
        try (ServerSocket probe = new ServerSocket(0)) {
            basePort = probe.getLocalPort();
        }

        blocker = new ServerSocket();
        blocker.setReuseAddress(false);
        blocker.bind(new java.net.InetSocketAddress("0.0.0.0", basePort));

        NetworkConfig config = new NetworkConfig();
        config.enabled = true;
        config.serverName = "fallback-host";
        config.listenPort = basePort;
        config.advertiseHostOverride = "127.0.0.1";

        manager = new NetworkManager(LOGGER, config, "1.26.1", "test", 25565, tempDir);
        manager.start();

        assertTrue(manager.isRunning());
        int bound = manager.getBoundListenPort();
        assertNotEquals(basePort, bound, "expected fallback to a free port above " + basePort);
        assertTrue(bound > basePort && bound <= basePort + 50, "bound port " + bound + " should be inside the fallback window");
    }

    @Test
    void sidebandOnlyWhenEntireFallbackRangeIsBusy() throws IOException {
        int basePort;
        try (ServerSocket probe = new ServerSocket(0)) {
            basePort = probe.getLocalPort();
        }
        ServerSocket[] hold = new ServerSocket[51];
        try {
            for (int i = 0; i <= 50; i++) {
                ServerSocket s = new ServerSocket();
                s.setReuseAddress(false);
                s.bind(new java.net.InetSocketAddress("0.0.0.0", basePort + i));
                hold[i] = s;
            }
            NetworkConfig config = new NetworkConfig();
            config.enabled = true;
            config.serverName = "no-bind";
            config.listenPort = basePort;
            config.advertiseHostOverride = "127.0.0.1";

            manager = new NetworkManager(LOGGER, config, "1.26.1", "test", 25565, tempDir);
            manager.start();
            assertTrue(manager.isRunning());
            assertEquals(basePort, manager.getBoundListenPort(), "sideband-only mode should fall back to configured listen-port for getBoundListenPort() reporting");
        } finally {
            for (ServerSocket s : hold) {
                if (s != null && !s.isClosed()) {
                    s.close();
                }
            }
        }
    }
}
