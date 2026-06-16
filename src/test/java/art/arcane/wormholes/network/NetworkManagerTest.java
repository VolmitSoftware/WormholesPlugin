package art.arcane.wormholes.network;

import art.arcane.wormholes.Wormholes;
import art.arcane.wormholes.config.toml.NetworkConfig;
import art.arcane.wormholes.portal.UniversalTunnel;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.net.ServerSocket;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import java.util.UUID;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

@Timeout(30)
class NetworkManagerTest {
    private static final Logger LOGGER = Logger.getLogger("NetworkManagerTest");
    private static final String ALPHA_NAME = "alpha";
    private static final int ALPHA_GAME_PORT = 25565;
    private static final String BETA_NAME = "beta";
    private static final int BETA_GAME_PORT = 25566;
    private static final String ZULU_NAME = "zulu";

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
        config.serverName = serverName == null ? "" : serverName;
        config.advertiseHostOverride = "127.0.0.1";
        config.listenPort = listenPort;
        return config;
    }

    private static NetworkConfig.PeerEntry route(String peerName, int peerPort) {
        return route(peerName, "127.0.0.1", peerPort);
    }

    private static NetworkConfig.PeerEntry route(String peerName, String host, int peerPort) {
        NetworkConfig.PeerEntry peer = new NetworkConfig.PeerEntry();
        peer.name = peerName;
        peer.host = host;
        peer.port = peerPort;
        return peer;
    }

    private NetworkManager manager(NetworkConfig config, int gamePort, String identityName) {
        NetworkManager manager = new NetworkManager(LOGGER, config, "1.26.1", "test", gamePort, tempDir.resolve(identityName));
        managers.add(manager);
        return manager;
    }

    private static PortalInfo portalInfo(UUID id, boolean open) {
        return new PortalInfo(id, "Gateway test", "world", "GATEWAY", open, "N", "E", "U",
            10.5D, 64.0D, 20.5D,
            9.5D, 63.5D, 19.5D,
            11.5D, 66.5D, 21.5D);
    }

    private static WireTraversive traversive() {
        return new WireTraversive("N", "E", "U",
            10.5D, 64.0D, 20.5D,
            10.5D, 64.0D, 20.5D,
            0.0D, 0.0D, 1.0D,
            0.0D, 0.0D, 1.0D,
            true);
    }

    @Test
    void localNameUsesConfiguredServerNameWhenPresent() {
        NetworkConfig named = config(8901, "");
        named.serverName = "hub";
        NetworkManager namedManager = manager(named, 25565, "named");
        assertEquals("hub", namedManager.getLocalName());

        NetworkManager defaultPort = manager(config(8902, ""), 25565, "default");
        String defaultName = defaultPort.getLocalName();
        assertTrue(defaultName.startsWith("wh-"));

        NetworkManager defaultPortReloaded = manager(config(8903, ""), 25565, "default");
        assertEquals(defaultName, defaultPortReloaded.getLocalName());

        NetworkManager customPort = manager(config(8904, ""), 25566, "custom");
        assertTrue(customPort.getLocalName().startsWith("wh-"));
        assertNotEquals(defaultName, customPort.getLocalName());
    }

    @Test
    void twoManagersHandshakeAndReachReady() throws IOException {
        int portA = freePort();
        int portB = freePort();
        NetworkManager alpha = manager(config(portA, ALPHA_NAME), ALPHA_GAME_PORT, "alpha");
        NetworkManager beta = manager(config(portB, BETA_NAME), BETA_GAME_PORT, "beta");
        alpha.savePeer(route(BETA_NAME, portB));
        beta.savePeer(route(ALPHA_NAME, portA));

        alpha.start();
        beta.start();

        awaitTrue("alpha sees beta READY", () -> alpha.isPeerReady(BETA_NAME), 10_000L);
        awaitTrue("beta sees alpha READY", () -> beta.isPeerReady(ALPHA_NAME), 10_000L);
    }

    @Test
    void trustedPeerRejectsChangedPublicKey() throws IOException, InterruptedException {
        int portA = freePort();
        int portB = freePort();
        NetworkConfig alphaConfig = config(portA, ALPHA_NAME);
        NetworkConfig betaConfig = config(portB, BETA_NAME);
        NetworkManager alpha = manager(alphaConfig, ALPHA_GAME_PORT, "alpha");
        NetworkManager beta = manager(betaConfig, BETA_GAME_PORT, "beta");
        alpha.savePeer(route(BETA_NAME, portB));
        beta.savePeer(route(ALPHA_NAME, portA));

        alpha.start();
        beta.start();
        awaitTrue("initial connect", () -> alpha.isPeerReady(BETA_NAME), 10_000L);

        beta.stop();
        awaitTrue("alpha notices disconnect", () -> !alpha.isPeerReady(BETA_NAME), 10_000L);

        NetworkManager impostor = manager(betaConfig, BETA_GAME_PORT, "beta-impostor");
        impostor.start();
        Thread.sleep(2_000L);
        assertFalse(alpha.isPeerReady(BETA_NAME));
    }

    @Test
    void singleSidedConfigConnectsAndLearnsPeerAddresses() throws IOException {
        int portAlpha = freePort();
        int portZulu = freePort();

        NetworkConfig alphaConfig = config(portAlpha, ALPHA_NAME);
        NetworkConfig zuluConfig = config(portZulu, ZULU_NAME);

        NetworkManager alpha = manager(alphaConfig, ALPHA_GAME_PORT, "alpha");
        NetworkManager zulu = manager(zuluConfig, 25599, "zulu");
        zulu.savePeer(route(ALPHA_NAME, portAlpha));

        alpha.start();
        zulu.start();

        awaitTrue("zulu connects to alpha", () -> zulu.isPeerReady(ALPHA_NAME), 10_000L);
        awaitTrue("alpha accepts unconfigured zulu", () -> alpha.isPeerReady(ZULU_NAME), 10_000L);

        NetworkConfig.PeerEntry learned = alpha.getPeer(ZULU_NAME);
        assertTrue(learned != null && learned.host.equals("127.0.0.1") && learned.port == portZulu && learned.publicPort == 25599,
            "alpha should learn zulu's addresses from the handshake, got " + (learned == null ? "null" : learned.host + ":" + learned.port + "/" + learned.publicPort));
    }

    @Test
    void outboundOnlyBoatConnectsToAnchor() throws IOException {
        int portAnchor = freePort();
        int unusedBoatPort = freePort();
        NetworkConfig anchorConfig = config(portAnchor, ALPHA_NAME);
        NetworkConfig boatConfig = config(unusedBoatPort, BETA_NAME);
        boatConfig.listenEnabled = false;

        NetworkManager anchor = manager(anchorConfig, ALPHA_GAME_PORT, "anchor");
        NetworkManager boat = manager(boatConfig, BETA_GAME_PORT, "boat");
        boat.savePeer(route(ALPHA_NAME, portAnchor));

        anchor.start();
        boat.start();

        awaitTrue("boat reaches anchor", () -> boat.isPeerReady(ALPHA_NAME), 10_000L);
        awaitTrue("anchor accepts boat", () -> anchor.isPeerReady(BETA_NAME), 10_000L);
    }

    @Test
    void anchorRelaysPortalDirectoriesAndRoutedTrafficBetweenBoats() throws IOException, InterruptedException {
        int anchorPort = freePort();
        int boatAPort = freePort();
        int boatBPort = freePort();

        NetworkConfig anchorConfig = config(anchorPort, "anchor");
        anchorConfig.serverName = "anchor";

        NetworkConfig boatAConfig = config(boatAPort, "boat-a");
        boatAConfig.serverName = "boat-a";
        boatAConfig.listenEnabled = false;

        NetworkConfig boatBConfig = config(boatBPort, "boat-b");
        boatBConfig.serverName = "boat-b";
        boatBConfig.listenEnabled = false;

        NetworkManager anchor = manager(anchorConfig, 25565, "relay-anchor");
        NetworkManager boatA = manager(boatAConfig, 25566, "relay-boat-a");
        NetworkManager boatB = manager(boatBConfig, 25567, "relay-boat-b");
        boatA.savePeer(route("anchor", anchorPort));
        boatB.savePeer(route("anchor", anchorPort));
        LinkedBlockingQueue<String> boatBMessages = new LinkedBlockingQueue<>();
        LinkedBlockingQueue<String> boatAMessages = new LinkedBlockingQueue<>();
        boatB.setMessageSink((peerName, message) -> {
            if (message instanceof WireMessage.PortalDirectory) {
                boatBMessages.offer(peerName + ":" + message.type());
            }
        });
        boatA.setMessageSink((peerName, message) -> {
            if (message instanceof WireMessage.ViewSubscribe subscribe) {
                boatAMessages.offer(peerName + ":" + subscribe.portalId());
            }
        });

        anchor.start();
        boatA.start();
        boatB.start();

        awaitTrue("boat A reaches anchor", () -> boatA.isPeerReady("anchor"), 10_000L);
        awaitTrue("boat B reaches anchor", () -> boatB.isPeerReady("anchor"), 10_000L);
        awaitTrue("anchor sees boat A", () -> anchor.isPeerReady("boat-a"), 10_000L);
        awaitTrue("anchor sees boat B", () -> anchor.isPeerReady("boat-b"), 10_000L);

        assertTrue(boatA.send("anchor", new WireMessage.PortalDirectory(List.of())));
        assertEquals("boat-a:PORTAL_DIRECTORY", boatBMessages.poll(10L, TimeUnit.SECONDS));

        UUID portalId = UUID.randomUUID();
        assertTrue(boatB.send("boat-a", new WireMessage.ViewSubscribe(portalId)));
        assertEquals("boat-b:" + portalId, boatAMessages.poll(10L, TimeUnit.SECONDS));
    }

    @Test
    void savedPeerRoutePersistsAndDialsWithoutConfiguredPeer() throws IOException {
        int portA = freePort();
        int portB = freePort();
        NetworkConfig alphaConfig = config(portA, ALPHA_NAME);
        NetworkConfig betaConfig = config(portB, BETA_NAME);
        NetworkConfig.PeerEntry route = new NetworkConfig.PeerEntry();
        route.name = BETA_NAME;
        route.host = "127.0.0.1";
        route.port = portB;
        route.publicHost = "127.0.0.1";
        route.publicPort = BETA_GAME_PORT;

        NetworkManager routeWriter = manager(alphaConfig, ALPHA_GAME_PORT, "route-alpha");
        routeWriter.savePeer(route);
        routeWriter.stop();

        NetworkManager alpha = manager(alphaConfig, ALPHA_GAME_PORT, "route-alpha");
        NetworkManager beta = manager(betaConfig, BETA_GAME_PORT, "route-beta");
        alpha.start();
        beta.start();

        awaitTrue("alpha reaches beta through saved route", () -> alpha.isPeerReady(BETA_NAME), 10_000L);
        awaitTrue("beta accepts alpha without configured peer", () -> beta.isPeerReady(ALPHA_NAME), 10_000L);
        assertTrue(alpha.getPeer(BETA_NAME) != null);
    }

    @Test
    void diagnosticsExplainSeparateWormholesPort() {
        NetworkConfig alphaConfig = config(8901, ALPHA_NAME);
        NetworkManager alpha = manager(alphaConfig, ALPHA_GAME_PORT, "diagnostic-alpha");
        alpha.savePeer(route(BETA_NAME, 8902));
        List<String> diagnostics = alpha.diagnostics();
        assertTrue(diagnostics.stream().anyMatch(message -> message.contains("status sideband")));
        assertTrue(diagnostics.stream().anyMatch(message -> message.contains("raw port")));
    }

    @Test
    void statusSidebandExchangesQueuedMessagesAfterTrust() throws IOException, InterruptedException {
        int rawAlpha = freePort();
        int rawBeta = freePort();
        NetworkConfig alphaConfig = config(rawAlpha, ALPHA_NAME);
        alphaConfig.listenEnabled = false;
        NetworkConfig.PeerEntry betaRoute = route(BETA_NAME, rawBeta);
        betaRoute.publicHost = "127.0.0.1";
        betaRoute.publicPort = BETA_GAME_PORT;

        NetworkConfig betaConfig = config(rawBeta, BETA_NAME);
        betaConfig.listenEnabled = false;
        NetworkConfig.PeerEntry alphaRoute = route(ALPHA_NAME, rawAlpha);
        alphaRoute.publicHost = "127.0.0.1";
        alphaRoute.publicPort = ALPHA_GAME_PORT;

        NetworkManager alpha = manager(alphaConfig, ALPHA_GAME_PORT, "status-alpha");
        NetworkManager beta = manager(betaConfig, BETA_GAME_PORT, "status-beta");
        alpha.savePeer(betaRoute);
        beta.savePeer(alphaRoute);
        LinkedBlockingQueue<String> betaMessages = new LinkedBlockingQueue<>();
        beta.setMessageSink((peerName, message) -> {
            if (message instanceof WireMessage.PortalDirectory) {
                betaMessages.offer(peerName + ":" + message.type());
            }
        });
        alpha.start();
        beta.start();

        assertTrue(alpha.send(BETA_NAME, new WireMessage.PortalDirectory(List.of())));
        MinecraftStatusBridge.StatusPacket request = beta.createStatusBridgePacket(ALPHA_NAME, List.of());
        MinecraftStatusBridge.StatusPacket response = alpha.handleStatusBridgeRequest(request);

        assertTrue(alpha.isPeerReady(BETA_NAME));
        assertTrue(beta.handleStatusBridgeResponse(ALPHA_NAME, response, 12L));
        assertTrue(beta.isPeerReady(ALPHA_NAME));
        assertEquals(ALPHA_NAME + ":PORTAL_DIRECTORY", betaMessages.poll(10L, TimeUnit.SECONDS));
    }

    @Test
    void statusSidebandFragmentsJumboFrames() throws IOException, InterruptedException {
        int rawAlpha = freePort();
        int rawBeta = freePort();
        NetworkConfig alphaConfig = config(rawAlpha, ALPHA_NAME);
        alphaConfig.listenEnabled = false;
        NetworkConfig.PeerEntry betaRoute = route(BETA_NAME, rawBeta);
        betaRoute.publicHost = "127.0.0.1";
        betaRoute.publicPort = BETA_GAME_PORT;

        NetworkConfig betaConfig = config(rawBeta, BETA_NAME);
        betaConfig.listenEnabled = false;
        NetworkConfig.PeerEntry alphaRoute = route(ALPHA_NAME, rawAlpha);
        alphaRoute.publicHost = "127.0.0.1";
        alphaRoute.publicPort = ALPHA_GAME_PORT;

        NetworkManager alpha = manager(alphaConfig, ALPHA_GAME_PORT, "jumbo-alpha");
        NetworkManager beta = manager(betaConfig, BETA_GAME_PORT, "jumbo-beta");
        alpha.savePeer(betaRoute);
        beta.savePeer(alphaRoute);
        LinkedBlockingQueue<Integer> betaTransfers = new LinkedBlockingQueue<>();
        beta.setMessageSink((peerName, message) -> {
            if (message instanceof WireMessage.EntityTransfer transfer) {
                betaTransfers.offer(transfer.entitySnapshot().length);
            }
        });
        alpha.start();
        beta.start();

        byte[] snapshot = new byte[70_000];
        new Random(42L).nextBytes(snapshot);
        WireMessage.EntityTransfer transfer = new WireMessage.EntityTransfer(UUID.randomUUID(), UUID.randomUUID(), snapshot, traversive());
        assertTrue(WireCodec.encodeFrame(transfer).length > MinecraftStatusBridge.MAX_FRAME_BYTES);
        assertTrue(alpha.send(BETA_NAME, transfer));

        for (int i = 0; i < 16 && betaTransfers.isEmpty(); i++) {
            MinecraftStatusBridge.StatusPacket request = beta.createStatusBridgePacket(ALPHA_NAME, List.of());
            MinecraftStatusBridge.StatusPacket response = alpha.handleStatusBridgeRequest(request);
            assertTrue(response != null);
            assertTrue(beta.handleStatusBridgeResponse(ALPHA_NAME, response, 12L));
        }
        assertEquals(snapshot.length, betaTransfers.poll(10L, TimeUnit.SECONDS));
    }

    @Test
    void statusReportsUndialableRoutesAsWaiting() throws IOException {
        int portA = freePort();
        NetworkConfig alphaConfig = config(portA, ALPHA_NAME);
        NetworkManager alpha = manager(alphaConfig, ALPHA_GAME_PORT, "waiting-alpha");
        NetworkConfig.PeerEntry undialableRoute = new NetworkConfig.PeerEntry();
        undialableRoute.name = BETA_NAME;
        undialableRoute.host = "";
        undialableRoute.port = 0;
        undialableRoute.publicHost = "";
        undialableRoute.publicPort = 0;
        alpha.savePeer(undialableRoute);

        alpha.start();

        List<NetworkManager.PeerStatus> statuses = alpha.status();
        assertEquals(1, statuses.size());
        assertEquals("WAITING", statuses.get(0).state());
    }

    @Test
    void fallbackHostRotationFindsReachableAddress() throws IOException {
        int portA = freePort();
        int portB = freePort();
        NetworkConfig alphaConfig = config(portA, ALPHA_NAME);
        NetworkManager alpha = manager(alphaConfig, ALPHA_GAME_PORT, "alpha");
        NetworkConfig.PeerEntry betaRoute = route(BETA_NAME, "unreachable.invalid", portB);
        betaRoute.fallbackHosts = "127.0.0.1";
        alpha.savePeer(betaRoute);
        NetworkManager beta = manager(config(portB, BETA_NAME), BETA_GAME_PORT, "beta");

        alpha.start();
        beta.start();

        awaitTrue("alpha reaches beta via fallback host", () -> alpha.isPeerReady(BETA_NAME), 20_000L);
        awaitTrue("beta accepts alpha", () -> beta.isPeerReady(ALPHA_NAME), 10_000L);
    }

    @Test
    void mutualDialsSettleToOneStableConnection() throws IOException, InterruptedException {
        int portA = freePort();
        int portB = freePort();
        NetworkManager alpha = manager(config(portA, ALPHA_NAME), ALPHA_GAME_PORT, "alpha");
        NetworkManager beta = manager(config(portB, BETA_NAME), BETA_GAME_PORT, "beta");
        alpha.savePeer(route(BETA_NAME, portB));
        beta.savePeer(route(ALPHA_NAME, portA));

        alpha.start();
        beta.start();

        awaitTrue("connected", () -> alpha.isPeerReady(BETA_NAME) && beta.isPeerReady(ALPHA_NAME), 10_000L);
        Thread.sleep(3_000L);
        assertTrue(alpha.isPeerReady(BETA_NAME) && beta.isPeerReady(ALPHA_NAME), "connection should stay stable after duplicate-dial dedupe");
    }

    @Test
    void reconnectsAfterPeerRestartWithSameIdentity() throws IOException {
        int portA = freePort();
        int portB = freePort();
        NetworkConfig alphaConfig = config(portA, ALPHA_NAME);
        NetworkConfig betaConfig = config(portB, BETA_NAME);

        NetworkManager alpha = manager(alphaConfig, ALPHA_GAME_PORT, "alpha");
        NetworkManager beta = manager(betaConfig, BETA_GAME_PORT, "beta");
        alpha.savePeer(route(BETA_NAME, portB));
        alpha.start();
        beta.start();
        awaitTrue("initial connect", () -> alpha.isPeerReady(BETA_NAME), 20_000L);

        beta.stop();
        awaitTrue("alpha notices disconnect", () -> !alpha.isPeerReady(BETA_NAME), 10_000L);

        NetworkManager betaReborn = manager(betaConfig, BETA_GAME_PORT, "beta");
        betaReborn.start();
        awaitTrue("alpha reconnects", () -> alpha.isPeerReady(BETA_NAME), 20_000L);
        awaitTrue("reborn beta sees alpha", () -> betaReborn.isPeerReady(ALPHA_NAME), 15_000L);
    }

    @Test
    void universalTunnelRecoversWhenPeerReadyAndRemoteLastReportedClosed() throws IOException {
        int portA = freePort();
        int portB = freePort();
        NetworkManager alpha = manager(config(portA, ALPHA_NAME), ALPHA_GAME_PORT, "alpha");
        NetworkManager beta = manager(config(portB, BETA_NAME), BETA_GAME_PORT, "beta");
        alpha.savePeer(route(BETA_NAME, portB));
        beta.savePeer(route(ALPHA_NAME, portA));
        alpha.start();
        beta.start();
        awaitTrue("connected", () -> alpha.isPeerReady(BETA_NAME), 10_000L);

        NetworkManager previousNetwork = Wormholes.networkManager;
        RemotePortalRegistry previousRegistry = Wormholes.remotePortalRegistry;
        try {
            UUID portalId = UUID.randomUUID();
            RemotePortalRegistry registry = new RemotePortalRegistry();
            registry.applyDirectory(BETA_NAME, List.of(portalInfo(portalId, false)));
            Wormholes.networkManager = alpha;
            Wormholes.remotePortalRegistry = registry;

            UniversalTunnel tunnel = new UniversalTunnel(BETA_NAME, portalId);
            assertTrue(tunnel.isValid());
        } finally {
            Wormholes.networkManager = previousNetwork;
            Wormholes.remotePortalRegistry = previousRegistry;
        }
    }

    @Test
    void disabledConfigDoesNotStart() {
        NetworkConfig config = new NetworkConfig();
        config.enabled = false;
        NetworkManager manager = manager(config, 25565, "disabled");
        manager.start();
        assertFalse(manager.isRunning());
    }

    @Test
    void statusReportsSavedPeerRoutes() throws IOException {
        int portA = freePort();
        int portB = freePort();
        NetworkManager alpha = manager(config(portA, ALPHA_NAME), ALPHA_GAME_PORT, "alpha");
        NetworkManager beta = manager(config(portB, BETA_NAME), BETA_GAME_PORT, "beta");
        alpha.savePeer(route(BETA_NAME, portB));
        beta.savePeer(route(ALPHA_NAME, portA));
        alpha.start();
        beta.start();
        awaitTrue("connected", () -> alpha.isPeerReady(BETA_NAME), 10_000L);

        List<NetworkManager.PeerStatus> statuses = alpha.status();
        assertTrue(statuses.size() == 1 && statuses.get(0).name().equals(BETA_NAME) && statuses.get(0).state().equals("CONNECTED"));
    }
}
