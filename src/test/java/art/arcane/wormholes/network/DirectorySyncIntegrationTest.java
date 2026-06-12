package art.arcane.wormholes.network;

import art.arcane.wormholes.config.toml.NetworkConfig;
import art.arcane.wormholes.portal.RemotePortal;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.io.IOException;
import java.net.ServerSocket;
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

    private static NetworkConfig config(int listenPort, String peerName, int peerPort) {
        NetworkConfig config = new NetworkConfig();
        config.enabled = true;
        config.listenHost = "127.0.0.1";
        config.advertiseHost = "127.0.0.1";
        config.listenPort = listenPort;
        config.sharedSecret = "s3cret";
        NetworkConfig.PeerEntry peer = new NetworkConfig.PeerEntry();
        peer.name = peerName;
        peer.host = "127.0.0.1";
        peer.port = peerPort;
        config.peers.add(peer);
        return config;
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
            new art.arcane.wormholes.network.view.ViewSubscriptionManager(manager, viewCache)
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

        NetworkManager alpha = new NetworkManager(LOGGER, config(portA, "127.0.0.1:25566", portB), "1.26.1", "test", 25565);
        NetworkManager beta = new NetworkManager(LOGGER, config(portB, "127.0.0.1", portA), "1.26.1", "test", 25566);
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
        awaitTrue("peers connected", () -> alpha.isPeerReady("127.0.0.1:25566") && beta.isPeerReady("127.0.0.1"), 10_000L);
        awaitTrue("connect-time directories exchanged", () -> betaRegistry.hasPeer("127.0.0.1") && alphaRegistry.hasPeer("127.0.0.1:25566"), 10_000L);

        UUID portalId = UUID.randomUUID();
        alpha.send("127.0.0.1:25566", new WireMessage.PortalUpsert(sampleInfo(portalId, true)));
        awaitTrue("upsert applied on beta", () -> {
            RemotePortal portal = betaRegistry.get("127.0.0.1", portalId);
            return portal != null && portal.isOpen();
        }, 5_000L);

        alpha.send("127.0.0.1:25566", new WireMessage.PortalUpsert(sampleInfo(portalId, false)));
        awaitTrue("re-upsert applied on beta", () -> {
            RemotePortal portal = betaRegistry.get("127.0.0.1", portalId);
            return portal != null && !portal.isOpen();
        }, 5_000L);

        UUID replacementId = UUID.randomUUID();
        alpha.send("127.0.0.1:25566", new WireMessage.PortalDirectory(List.of(sampleInfo(replacementId, true))));
        awaitTrue("directory replaces beta's view of alpha", () -> {
            RemotePortal replacement = betaRegistry.get("127.0.0.1", replacementId);
            return replacement != null && betaRegistry.get("127.0.0.1", portalId) == null;
        }, 5_000L);

        alpha.send("127.0.0.1:25566", new WireMessage.PortalRemove(replacementId));
        awaitTrue("remove applied on beta", () -> betaRegistry.get("127.0.0.1", replacementId) == null, 5_000L);
        assertNull(alphaRegistry.get("127.0.0.1:25566", portalId));
    }
}
