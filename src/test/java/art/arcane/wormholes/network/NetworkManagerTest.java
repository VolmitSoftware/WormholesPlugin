package art.arcane.wormholes.network;

import art.arcane.wormholes.config.toml.NetworkConfig;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.io.IOException;
import java.net.ServerSocket;
import java.util.ArrayList;
import java.util.List;
import java.util.function.BooleanSupplier;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

@Timeout(30)
class NetworkManagerTest {
    private static final Logger LOGGER = Logger.getLogger("NetworkManagerTest");
    private static final String ALPHA_NAME = "127.0.0.1";
    private static final int ALPHA_GAME_PORT = 25565;
    private static final String BETA_NAME = "127.0.0.1:25566";
    private static final int BETA_GAME_PORT = 25566;

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

    private static NetworkConfig config(int listenPort, String secret, String peerName, int peerPort) {
        NetworkConfig config = new NetworkConfig();
        config.enabled = true;
        config.listenHost = "127.0.0.1";
        config.advertiseHost = "127.0.0.1";
        config.listenPort = listenPort;
        config.sharedSecret = secret;
        if (peerName != null) {
            NetworkConfig.PeerEntry peer = new NetworkConfig.PeerEntry();
            peer.name = peerName;
            peer.host = "127.0.0.1";
            peer.port = peerPort;
            config.peers.add(peer);
        }
        return config;
    }

    private NetworkManager manager(NetworkConfig config, int gamePort) {
        NetworkManager manager = new NetworkManager(LOGGER, config, "1.26.1", "test", gamePort);
        managers.add(manager);
        return manager;
    }

    @Test
    void localNameIsAddressWithPortOnlyWhenNotDefault() {
        NetworkManager defaultPort = manager(config(8901, "s", null, 0), 25565);
        assertEquals("127.0.0.1", defaultPort.getLocalName());

        NetworkManager customPort = manager(config(8902, "s", null, 0), 25566);
        assertEquals("127.0.0.1:25566", customPort.getLocalName());
    }

    @Test
    void twoManagersHandshakeAndReachReady() throws IOException {
        int portA = freePort();
        int portB = freePort();
        NetworkManager alpha = manager(config(portA, "s3cret", BETA_NAME, portB), ALPHA_GAME_PORT);
        NetworkManager beta = manager(config(portB, "s3cret", ALPHA_NAME, portA), BETA_GAME_PORT);

        alpha.start();
        beta.start();

        awaitTrue("alpha sees beta READY", () -> alpha.isPeerReady(BETA_NAME), 10_000L);
        awaitTrue("beta sees alpha READY", () -> beta.isPeerReady(ALPHA_NAME), 10_000L);
    }

    @Test
    void mismatchedSecretNeverConnects() throws IOException, InterruptedException {
        int portA = freePort();
        int portB = freePort();
        NetworkManager alpha = manager(config(portA, "rightsecret", BETA_NAME, portB), ALPHA_GAME_PORT);
        NetworkManager beta = manager(config(portB, "wrongsecret", ALPHA_NAME, portA), BETA_GAME_PORT);

        alpha.start();
        beta.start();

        Thread.sleep(2_000L);
        assertFalse(alpha.isPeerReady(BETA_NAME));
        assertFalse(beta.isPeerReady(ALPHA_NAME));
    }

    @Test
    void singleSidedConfigConnectsAndLearnsPeerAddresses() throws IOException {
        int portAlpha = freePort();
        int portZulu = freePort();

        NetworkConfig alphaConfig = config(portAlpha, "s3cret", null, 0);
        NetworkConfig zuluConfig = config(portZulu, "s3cret", ALPHA_NAME, portAlpha);

        NetworkManager alpha = manager(alphaConfig, ALPHA_GAME_PORT);
        NetworkManager zulu = manager(zuluConfig, 25599);

        alpha.start();
        zulu.start();

        awaitTrue("zulu connects to alpha", () -> zulu.isPeerReady(ALPHA_NAME), 10_000L);
        awaitTrue("alpha accepts unconfigured zulu", () -> alpha.isPeerReady("127.0.0.1:25599"), 10_000L);

        NetworkConfig.PeerEntry learned = alpha.getPeer("127.0.0.1:25599");
        assertTrue(learned != null && learned.host.equals("127.0.0.1") && learned.port == portZulu && learned.publicPort == 25599,
            "alpha should learn zulu's addresses from the handshake, got " + (learned == null ? "null" : learned.host + ":" + learned.port + "/" + learned.publicPort));
    }

    @Test
    void fallbackHostRotationFindsReachableAddress() throws IOException {
        int portA = freePort();
        int portB = freePort();
        NetworkConfig alphaConfig = config(portA, "s3cret", BETA_NAME, portB);
        alphaConfig.peers.get(0).host = "unreachable.invalid";
        alphaConfig.peers.get(0).fallbackHosts = "127.0.0.1";
        NetworkManager alpha = manager(alphaConfig, ALPHA_GAME_PORT);
        NetworkManager beta = manager(config(portB, "s3cret", null, 0), BETA_GAME_PORT);

        alpha.start();
        beta.start();

        awaitTrue("alpha reaches beta via fallback host", () -> alpha.isPeerReady(BETA_NAME), 20_000L);
        awaitTrue("beta accepts alpha", () -> beta.isPeerReady(ALPHA_NAME), 10_000L);
    }

    @Test
    void mutualDialsSettleToOneStableConnection() throws IOException, InterruptedException {
        int portA = freePort();
        int portB = freePort();
        NetworkManager alpha = manager(config(portA, "s3cret", BETA_NAME, portB), ALPHA_GAME_PORT);
        NetworkManager beta = manager(config(portB, "s3cret", ALPHA_NAME, portA), BETA_GAME_PORT);

        alpha.start();
        beta.start();

        awaitTrue("connected", () -> alpha.isPeerReady(BETA_NAME) && beta.isPeerReady(ALPHA_NAME), 10_000L);
        Thread.sleep(3_000L);
        assertTrue(alpha.isPeerReady(BETA_NAME) && beta.isPeerReady(ALPHA_NAME), "connection should stay stable after duplicate-dial dedupe");
    }

    @Test
    void reconnectsAfterPeerRestart() throws IOException {
        int portA = freePort();
        int portB = freePort();
        NetworkConfig alphaConfig = config(portA, "s3cret", BETA_NAME, portB);
        NetworkConfig betaConfig = config(portB, "s3cret", null, 0);

        NetworkManager alpha = manager(alphaConfig, ALPHA_GAME_PORT);
        NetworkManager beta = manager(betaConfig, BETA_GAME_PORT);
        alpha.start();
        beta.start();
        awaitTrue("initial connect", () -> alpha.isPeerReady(BETA_NAME), 20_000L);

        beta.stop();
        awaitTrue("alpha notices disconnect", () -> !alpha.isPeerReady(BETA_NAME), 10_000L);

        NetworkManager betaReborn = manager(betaConfig, BETA_GAME_PORT);
        betaReborn.start();
        awaitTrue("alpha reconnects", () -> alpha.isPeerReady(BETA_NAME), 20_000L);
        awaitTrue("reborn beta sees alpha", () -> betaReborn.isPeerReady(ALPHA_NAME), 15_000L);
    }

    @Test
    void disabledConfigDoesNotStart() {
        NetworkConfig config = new NetworkConfig();
        config.enabled = false;
        NetworkManager manager = manager(config, 25565);
        manager.start();
        assertFalse(manager.isRunning());
    }

    @Test
    void statusReportsConfiguredPeers() throws IOException {
        int portA = freePort();
        int portB = freePort();
        NetworkManager alpha = manager(config(portA, "s3cret", BETA_NAME, portB), ALPHA_GAME_PORT);
        NetworkManager beta = manager(config(portB, "s3cret", ALPHA_NAME, portA), BETA_GAME_PORT);
        alpha.start();
        beta.start();
        awaitTrue("connected", () -> alpha.isPeerReady(BETA_NAME), 10_000L);

        List<NetworkManager.PeerStatus> statuses = alpha.status();
        assertTrue(statuses.size() == 1 && statuses.get(0).name().equals(BETA_NAME) && statuses.get(0).state().equals("CONNECTED"));
    }
}
