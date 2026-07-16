package art.arcane.wormholes.network;

import art.arcane.wormholes.config.toml.NetworkConfig;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.lang.reflect.Field;
import java.net.InetSocketAddress;
import java.net.SocketAddress;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

class StatusSidebandReliabilityTest {
    private static final Logger LOGGER = Logger.getLogger("StatusSidebandReliabilityTest");
    private static final String ALPHA = "alpha";
    private static final String BETA = "beta";

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

    @Test
    void replaysUnacknowledgedResponseAndDeduplicatesBothDirections() throws Exception {
        NetworkManager alpha = manager(ALPHA);
        NetworkManager beta = manager(BETA);
        AtomicInteger requestDeliveries = new AtomicInteger();
        AtomicInteger responseDeliveries = new AtomicInteger();
        beta.setMessageSink((peerName, message) -> {
            if (message instanceof WireMessage.Ping) {
                requestDeliveries.incrementAndGet();
            }
        });
        alpha.setMessageSink((peerName, message) -> {
            if (message instanceof WireMessage.PortalDirectory) {
                responseDeliveries.incrementAndGet();
            }
        });
        assertTrue(beta.send(ALPHA, new WireMessage.PortalDirectory(List.of())));

        MinecraftStatusBridge.StatusPacket request = alpha.createStatusBridgePacket(
            BETA,
            List.of(encoded(new WireMessage.Ping(41L)))
        );
        MinecraftStatusBridge.StatusPacket response = beta.handleStatusBridgeRequest(request);
        MinecraftStatusBridge.StatusPacket replay = beta.handleStatusBridgeRequest(request);

        assertNotNull(response);
        assertSame(response, replay);
        assertEquals(request.nonce(), response.ackNonce());
        assertEquals(1, requestDeliveries.get());
        assertTrue(beta.hasPendingStatusResponse(ALPHA));
        assertTrue(alpha.handleStatusBridgeResponse(BETA, response, 1L));
        assertTrue(alpha.handleStatusBridgeResponse(BETA, response, 1L));
        assertEquals(1, responseDeliveries.get());

        MinecraftStatusBridge.StatusPacket acknowledgment = alpha.createStatusBridgePacket(BETA, List.of());
        assertEquals(response.nonce(), acknowledgment.ackNonce());
        assertTrue(acknowledgment.verify());
        MinecraftStatusBridge.StatusPacket acknowledgmentResponse = beta.handleStatusBridgeRequest(acknowledgment);

        assertNotNull(acknowledgmentResponse);
        assertEquals(acknowledgment.nonce(), acknowledgmentResponse.ackNonce());
        assertFalse(beta.hasPendingStatusResponse(ALPHA));
    }

    @Test
    void expiredResponseBatchReturnsToTheOutboxAndCanBeReplayed() throws Exception {
        NetworkManager alpha = manager(ALPHA);
        NetworkManager beta = manager(BETA);
        assertTrue(beta.send(ALPHA, new WireMessage.PortalDirectory(List.of())));
        MinecraftStatusBridge.StatusPacket request = alpha.createStatusBridgePacket(BETA, List.of());
        MinecraftStatusBridge.StatusPacket firstResponse = beta.handleStatusBridgeRequest(request);

        assertNotNull(firstResponse);
        assertTrue(beta.hasPendingStatusResponse(ALPHA));
        assertEquals(0L, beta.statusOutboxQueuedCount(ALPHA));

        beta.expirePendingStatusDeliveries(Long.MAX_VALUE);

        assertFalse(beta.hasPendingStatusResponse(ALPHA));
        assertEquals(1L, beta.statusOutboxQueuedCount(ALPHA));
        MinecraftStatusBridge.StatusPacket retriedResponse = beta.handleStatusBridgeRequest(request);
        assertNotNull(retriedResponse);
        assertNotEquals(firstResponse.nonce(), retriedResponse.nonce());
        assertEquals(request.nonce(), retriedResponse.ackNonce());
        assertEquals(1, retriedResponse.messages().size());
        assertInstanceOf(WireMessage.PortalDirectory.class, retriedResponse.messages().getFirst());
        assertTrue(beta.hasPendingStatusResponse(ALPHA));
    }

    @Test
    void rawUpgradeClearsSidebandStateRejectsStalePacketsAndEmitsReset() throws Exception {
        NetworkManager alpha = manager(ALPHA);
        NetworkManager beta = manager(BETA);
        List<Boolean> peerStates = new ArrayList<>();
        beta.setPeerStateSink((peerName, ready) -> {
            if (ALPHA.equals(peerName)) {
                peerStates.add(ready);
            }
        });
        assertTrue(beta.send(ALPHA, new WireMessage.PortalDirectory(List.of())));
        MinecraftStatusBridge.StatusPacket request = alpha.createStatusBridgePacket(BETA, List.of());
        MinecraftStatusBridge.StatusPacket response = beta.handleStatusBridgeRequest(request);
        assertNotNull(response);
        assertTrue(beta.hasPendingStatusResponse(ALPHA));
        assertTrue(beta.send(ALPHA, new WireMessage.Ping(42L)));
        assertEquals(1L, beta.statusOutboxQueuedCount(ALPHA));

        PeerConnection rawConnection = readyConnection(beta, BETA, ALPHA);
        beta.onReady(rawConnection);

        assertEquals(List.of(true, false, true), peerStates);
        assertTrue(beta.isPeerReady(ALPHA));
        assertFalse(beta.isSidebandOnlyPeer(ALPHA));
        assertFalse(beta.hasPendingStatusResponse(ALPHA));
        assertEquals(0L, beta.statusOutboxQueuedCount(ALPHA));
        assertFalse(beta.nextStatusAttempt.containsKey(ALPHA));
        assertTrue(rawConnection.send(new WireMessage.Ping(43L)));
        NetworkManager.DebugSnapshot snapshot = beta.debugSnapshot();
        assertEquals(rawConnection.getWriteQueueSize(), snapshot.rawWriteQueueFrames());
        assertTrue(snapshot.rawWriteQueueFrames() > 0L);
        assertEquals(0L, snapshot.sidebandQueuedBytes());
        assertEquals(0L, snapshot.sidebandQueuedCount());
        assertTrue(snapshot.sidebandDroppedBytes() > 0L);
        assertTrue(snapshot.sidebandDroppedCount() > 0L);
        assertNull(beta.handleStatusBridgeRequest(request));
        MinecraftStatusBridge.StatusPacket staleResponse = alpha.createStatusBridgePacket(BETA, List.of());
        assertFalse(beta.handleStatusBridgeResponse(ALPHA, staleResponse, 1L));
    }

    @Test
    void nonceWindowIsBoundedAndOnlyReacceptsEvictedValues() {
        NetworkManager.NonceWindow window = new NetworkManager.NonceWindow(2);

        assertTrue(window.add(1L));
        assertTrue(window.add(2L));
        assertFalse(window.add(1L));
        assertEquals(2, window.size());
        assertTrue(window.add(3L));

        assertFalse(window.contains(1L));
        assertTrue(window.contains(2L));
        assertTrue(window.contains(3L));
        assertEquals(2, window.size());
        assertTrue(window.add(1L));
        assertFalse(window.contains(2L));
    }

    private NetworkManager manager(String name) throws Exception {
        NetworkConfig config = new NetworkConfig();
        config.enabled = true;
        config.listenEnabled = false;
        config.serverName = name;
        config.advertiseHostOverride = "127.0.0.1";
        NetworkManager manager = new NetworkManager(LOGGER, config, "26.2", "test", 25565,
            tempDir.resolve(name));
        manager.savePeer(route(ALPHA.equals(name) ? BETA : ALPHA));
        setRunning(manager);
        managers.add(manager);
        return manager;
    }

    private static NetworkConfig.PeerEntry route(String peerName) {
        NetworkConfig.PeerEntry peer = new NetworkConfig.PeerEntry();
        peer.name = peerName;
        peer.publicHost = "127.0.0.1";
        peer.publicPort = 25565;
        return peer;
    }

    private static MinecraftStatusBridge.EncodedMessage encoded(WireMessage message) throws Exception {
        return new MinecraftStatusBridge.EncodedMessage(message, WireCodec.encodeFrame(message));
    }

    private static void setRunning(NetworkManager manager) throws Exception {
        Field field = NetworkManager.class.getDeclaredField("running");
        field.setAccessible(true);
        AtomicBoolean running = (AtomicBoolean) field.get(manager);
        running.set(true);
    }

    @SuppressWarnings("unchecked")
    private static PeerConnection readyConnection(NetworkManager manager, String localName, String peerName) throws Exception {
        LocalIdentity identity = new LocalIdentity(localName, "26.2", "test", "127.0.0.1", 8901, 25565,
            new byte[0], null);
        PeerConnection connection = new PeerConnection(new InertPeerChannel(), true, identity, peerName, null,
            manager, manager);
        Field field = PeerConnection.class.getDeclaredField("state");
        field.setAccessible(true);
        AtomicReference<PeerConnection.State> state = (AtomicReference<PeerConnection.State>) field.get(connection);
        state.set(PeerConnection.State.READY);
        return connection;
    }

    private static final class InertPeerChannel implements PeerTransport.PeerChannel {
        private final InputStream input = new ByteArrayInputStream(new byte[0]);
        private final OutputStream output = new ByteArrayOutputStream();

        @Override
        public InputStream getInputStream() {
            return input;
        }

        @Override
        public OutputStream getOutputStream() {
            return output;
        }

        @Override
        public void setReadTimeout(int millis) {
        }

        @Override
        public void setTcpNoDelay(boolean noDelay) {
        }

        @Override
        public String describeRemote() {
            return "inert:0";
        }

        @Override
        public SocketAddress remoteAddress() {
            return new InetSocketAddress("127.0.0.1", 0);
        }

        @Override
        public boolean isLoopback() {
            return true;
        }

        @Override
        public void close() {
        }
    }
}
