package art.arcane.wormholes.network;

import art.arcane.wormholes.config.toml.NetworkConfig;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.net.ServerSocket;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.function.BooleanSupplier;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.fail;

@Timeout(30)
class HandshakeAutoFieldsTest {
    private static final Logger LOGGER = Logger.getLogger("HandshakeAutoFieldsTest");

    @TempDir
    Path tempDir;

    private final List<NetworkManager> managers = new ArrayList<>();

    @AfterEach
    void tearDown() {
        for (NetworkManager manager : managers) {
            manager.stop();
        }
        managers.clear();
    }

    private static int freePort() throws IOException {
        try (ServerSocket socket = new ServerSocket(0)) {
            return socket.getLocalPort();
        }
    }

    private static NetworkConfig config(int listenPort, String name) {
        NetworkConfig config = new NetworkConfig();
        config.enabled = true;
        config.serverName = name;
        config.listenPort = listenPort;
        config.advertiseHostOverride = "127.0.0.1";
        return config;
    }

    private NetworkManager manager(NetworkConfig config, int gamePort, String identityName) {
        NetworkManager manager = new NetworkManager(LOGGER, config, "1.26.1", "test", gamePort, tempDir.resolve(identityName));
        managers.add(manager);
        return manager;
    }

    private static void awaitTrue(String what, BooleanSupplier condition, long timeoutMillis) {
        long deadline = System.currentTimeMillis() + timeoutMillis;
        while (System.currentTimeMillis() < deadline) {
            if (condition.getAsBoolean()) {
                return;
            }
            try {
                Thread.sleep(10L);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                fail("Interrupted while waiting for: " + what);
            }
        }
        fail("Timed out waiting for: " + what);
    }

    @Test
    void challengeRoundTripCarriesAdvertiseAndPorts() throws Exception {
        byte[] nonce = Handshake.newNonce();
        java.security.KeyPair pair = java.security.KeyPairGenerator.getInstance("Ed25519").generateKeyPair();
        WireMessage.Challenge challenge = new WireMessage.Challenge(
            "beta",
            "203.0.113.42",
            8901,
            25566,
            nonce,
            pair.getPublic().getEncoded(),
            new byte[]{1, 2, 3},
            true,
            CompressionDictionary.ZERO_HASH,
            0
        );
        byte[] frame = WireCodec.encodeFrame(challenge);
        WireMessage decoded = WireCodec.readFrame(new java.io.DataInputStream(new java.io.ByteArrayInputStream(frame)));
        WireMessage.Challenge round = (WireMessage.Challenge) decoded;
        assertEquals("203.0.113.42", round.advertiseHost());
        assertEquals(8901, round.wormholePort());
        assertEquals(25566, round.gamePort());
    }

    @Test
    void peerAutoPopulatesPublicHostAndPortFromHandshake() throws Exception {
        int portA = freePort();
        int portB = freePort();
        int alphaGamePort = 25565;
        int betaGamePort = 25577;

        NetworkConfig alphaConfig = config(portA, "alpha-auto");
        NetworkConfig betaConfig = config(portB, "beta-auto");

        NetworkManager alpha = manager(alphaConfig, alphaGamePort, "alpha-auto");
        NetworkManager beta = manager(betaConfig, betaGamePort, "beta-auto");

        NetworkConfig.PeerEntry alphaSavesBeta = new NetworkConfig.PeerEntry();
        alphaSavesBeta.name = "beta-auto";
        alphaSavesBeta.host = "127.0.0.1";
        alphaSavesBeta.port = portB;
        alpha.savePeer(alphaSavesBeta);

        NetworkConfig.PeerEntry betaSavesAlpha = new NetworkConfig.PeerEntry();
        betaSavesAlpha.name = "alpha-auto";
        betaSavesAlpha.host = "127.0.0.1";
        betaSavesAlpha.port = portA;
        beta.savePeer(betaSavesAlpha);

        alpha.start();
        beta.start();

        awaitTrue("alpha sees beta READY", () -> alpha.isPeerReady("beta-auto"), 10_000L);
        awaitTrue("beta sees alpha READY", () -> beta.isPeerReady("alpha-auto"), 10_000L);

        awaitTrue("alpha auto-populated beta's publicPort", () -> {
            NetworkConfig.PeerEntry learned = alpha.getPeer("beta-auto");
            return learned != null && learned.publicPort == betaGamePort && learned.publicHost != null && !learned.publicHost.isBlank();
        }, 5_000L);

        NetworkConfig.PeerEntry learned = alpha.getPeer("beta-auto");
        assertNotNull(learned);
        assertEquals(betaGamePort, learned.publicPort);
        assertEquals("127.0.0.1", learned.publicHost);
    }
}
