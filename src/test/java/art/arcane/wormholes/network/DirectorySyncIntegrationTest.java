package art.arcane.wormholes.network;

import art.arcane.wormholes.config.toml.NetworkConfig;
import art.arcane.wormholes.portal.RemotePortal;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.net.ServerSocket;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.function.BooleanSupplier;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.fail;

@Timeout(30)
class DirectorySyncIntegrationTest {
    private static final Logger LOGGER = Logger.getLogger("DirectorySyncIntegrationTest");
    private static final String ALPHA_NAME = "alpha";
    private static final String BETA_NAME = "beta";

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

    private static NetworkConfig config(int listenPort, String serverName) {
        NetworkConfig config = new NetworkConfig();
        config.enabled = true;
        config.serverName = serverName;
        config.advertiseHostOverride = "127.0.0.1";
        config.listenPort = listenPort;
        return config;
    }

    private static NetworkConfig.PeerEntry route(String peerName, int peerPort) {
        NetworkConfig.PeerEntry peer = new NetworkConfig.PeerEntry();
        peer.name = peerName;
        peer.host = "127.0.0.1";
        peer.port = peerPort;
        return peer;
    }

    private static NetworkRouter router(NetworkManager manager, RemotePortalRegistry registry) {
        PortalSyncService sync = new PortalSyncService(manager, List::of, Runnable::run);
        art.arcane.wormholes.network.view.RemoteViewCache viewCache = new art.arcane.wormholes.network.view.RemoteViewCache();
        return new NetworkRouter(
            registry,
            sync,
            new TraversalService(manager),
            new art.arcane.wormholes.network.view.ViewServer(manager),
            viewCache,
            new art.arcane.wormholes.network.view.ViewSubscriptionManager(manager, viewCache),
            manager.getReplicationManager(),
            manager
        );
    }

    private static PortalInfo sampleInfo(UUID id, boolean open) {
        return new PortalInfo(id, "Gateway test", "world", "GATEWAY", open, "N", "E", "U",
            10.5D, 64.0D, 20.5D,
            9.5D, 63.5D, 19.5D,
            11.5D, 66.5D, 21.5D);
    }

    @Test
    void directoryUpsertAndRemoveFlowAcrossRealSockets() throws IOException {
        int portA = freePort();
        int portB = freePort();

        NetworkManager alpha = new NetworkManager(LOGGER, config(portA, ALPHA_NAME), "1.26.1", "test", 25565, tempDir.resolve("alpha"));
        NetworkManager beta = new NetworkManager(LOGGER, config(portB, BETA_NAME), "1.26.1", "test", 25566, tempDir.resolve("beta"));
        alpha.savePeer(route(BETA_NAME, portB));
        beta.savePeer(route(ALPHA_NAME, portA));
        managers.add(alpha);
        managers.add(beta);

        RemotePortalRegistry betaRegistry = new RemotePortalRegistry();
        NetworkRouter betaRouter = router(beta, betaRegistry);
        beta.setMessageSink(betaRouter::onMessage);
        beta.setPeerStateSink(betaRouter::onPeerState);

        RemotePortalRegistry alphaRegistry = new RemotePortalRegistry();
        NetworkRouter alphaRouter = router(alpha, alphaRegistry);
        alpha.setMessageSink(alphaRouter::onMessage);
        alpha.setPeerStateSink(alphaRouter::onPeerState);

        alpha.start();
        beta.start();
        awaitTrue("peers connected", () -> alpha.isPeerReady(BETA_NAME) && beta.isPeerReady(ALPHA_NAME), 10_000L);
        awaitTrue("connect-time directories exchanged", () -> betaRegistry.hasPeer(ALPHA_NAME) && alphaRegistry.hasPeer(BETA_NAME), 10_000L);

        UUID portalId = UUID.randomUUID();
        alpha.send(BETA_NAME, new WireMessage.PortalUpsert(sampleInfo(portalId, true)));
        awaitTrue("upsert applied on beta", () -> {
            RemotePortal portal = betaRegistry.get(ALPHA_NAME, portalId);
            return portal != null && portal.isOpen();
        }, 5_000L);

        alpha.send(BETA_NAME, new WireMessage.PortalUpsert(sampleInfo(portalId, false)));
        awaitTrue("re-upsert applied on beta", () -> {
            RemotePortal portal = betaRegistry.get(ALPHA_NAME, portalId);
            return portal != null && !portal.isOpen();
        }, 5_000L);

        UUID replacementId = UUID.randomUUID();
        alpha.send(BETA_NAME, new WireMessage.PortalDirectory(List.of(sampleInfo(replacementId, true))));
        awaitTrue("directory replaces beta's view of alpha", () -> {
            RemotePortal replacement = betaRegistry.get(ALPHA_NAME, replacementId);
            return replacement != null && betaRegistry.get(ALPHA_NAME, portalId) == null;
        }, 5_000L);

        alpha.send(BETA_NAME, new WireMessage.PortalRemove(replacementId));
        awaitTrue("remove applied on beta", () -> betaRegistry.get(ALPHA_NAME, replacementId) == null, 5_000L);
        assertNull(alphaRegistry.get(BETA_NAME, portalId));
    }
}
