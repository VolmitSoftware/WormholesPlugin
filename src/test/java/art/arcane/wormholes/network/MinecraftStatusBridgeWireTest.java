package art.arcane.wormholes.network;

import art.arcane.wormholes.config.toml.NetworkConfig;
import com.github.retrooper.packetevents.protocol.player.ClientVersion;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayOutputStream;
import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

@Timeout(30)
class MinecraftStatusBridgeWireTest {
    private static final Logger LOGGER = Logger.getLogger("MinecraftStatusBridgeWireTest");
    private static final String ALPHA_NAME = "alpha";
    private static final String BETA_NAME = "beta";
    private static final String HOST_PREFIX = "whs.";

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

    private static NetworkConfig config(int listenPort, String serverName) {
        NetworkConfig config = new NetworkConfig();
        config.enabled = true;
        config.serverName = serverName == null ? "" : serverName;
        config.advertiseHostOverride = "127.0.0.1";
        config.listenPort = listenPort;
        return config;
    }

    private NetworkManager manager(NetworkConfig config, int gamePort, String identityName) {
        NetworkManager manager = new NetworkManager(LOGGER, config, "26.2", "test", gamePort, tempDir.resolve(identityName));
        managers.add(manager);
        return manager;
    }

    private static int readVarInt(byte[] data, int[] cursor) {
        int value = 0;
        int position = 0;
        while (position < 35) {
            int current = data[cursor[0]++] & 0xFF;
            value |= (current & 0x7F) << position;
            if ((current & 0x80) == 0) {
                return value;
            }
            position += 7;
        }
        throw new IllegalStateException("varint too large");
    }

    private static int readVarInt(InputStream input) throws IOException {
        int value = 0;
        int position = 0;
        while (position < 35) {
            int current = input.read();
            if (current < 0) {
                throw new EOFException("truncated varint");
            }
            value |= (current & 0x7F) << position;
            if ((current & 0x80) == 0) {
                return value;
            }
            position += 7;
        }
        throw new IOException("varint too large");
    }

    private static void writeVarInt(OutputStream output, int value) throws IOException {
        int remaining = value;
        while ((remaining & 0xFFFFFF80) != 0) {
            output.write((remaining & 0x7F) | 0x80);
            remaining >>>= 7;
        }
        output.write(remaining);
    }

    @Test
    void requestBytesEncodeTwoValidVarintPrefixedPackets() throws IOException {
        String host = HOST_PREFIX + "canned-status-bridge-request";
        int port = 25565;
        byte[] bytes = MinecraftStatusBridge.requestBytes(host, port);

        int[] cursor = {0};
        int handshakeLength = readVarInt(bytes, cursor);
        int handshakeStart = cursor[0];
        assertEquals(0, readVarInt(bytes, cursor));
        assertEquals(ClientVersion.getLatest().getProtocolVersion(), readVarInt(bytes, cursor));
        int hostLength = readVarInt(bytes, cursor);
        String decodedHost = new String(bytes, cursor[0], hostLength, StandardCharsets.UTF_8);
        cursor[0] += hostLength;
        assertEquals(host, decodedHost);
        int decodedPort = ((bytes[cursor[0]] & 0xFF) << 8) | (bytes[cursor[0] + 1] & 0xFF);
        cursor[0] += 2;
        assertEquals(port, decodedPort);
        assertEquals(1, readVarInt(bytes, cursor));
        assertEquals(handshakeLength, cursor[0] - handshakeStart);

        int statusRequestLength = readVarInt(bytes, cursor);
        int statusRequestStart = cursor[0];
        assertEquals(0, readVarInt(bytes, cursor));
        assertEquals(statusRequestLength, cursor[0] - statusRequestStart);
        assertEquals(bytes.length, cursor[0]);
    }

    @Test
    void pollRoundTripsOverLoopbackSocket() throws IOException, InterruptedException {
        NetworkConfig alphaConfig = config(freePort(), ALPHA_NAME);
        alphaConfig.listenEnabled = false;
        NetworkConfig betaConfig = config(freePort(), BETA_NAME);
        betaConfig.listenEnabled = false;
        NetworkManager alpha = manager(alphaConfig, freePort(), "wire-alpha");
        NetworkManager beta = manager(betaConfig, freePort(), "wire-beta");
        NetworkConfig.PeerEntry alphaRoute = new NetworkConfig.PeerEntry();
        alphaRoute.name = ALPHA_NAME;
        alphaRoute.host = "";
        alphaRoute.port = 0;
        alphaRoute.publicHost = "";
        alphaRoute.publicPort = 0;
        beta.savePeer(alphaRoute);
        beta.start();

        AtomicReference<Throwable> serverFailure = new AtomicReference<>();
        try (ServerSocket serverSocket = new ServerSocket()) {
            serverSocket.bind(new InetSocketAddress("127.0.0.1", 0));
            Thread server = new Thread(() -> serveStatusBridgeOnce(serverSocket, beta, serverFailure), "wire-test-status-server");
            server.setDaemon(true);
            server.start();

            NetworkConfig.PeerEntry peerEntry = new NetworkConfig.PeerEntry();
            peerEntry.name = BETA_NAME;
            peerEntry.host = "127.0.0.2";
            peerEntry.port = 0;
            peerEntry.fallbackHosts = "127.0.0.1";
            peerEntry.publicHost = "127.0.0.2";
            peerEntry.publicPort = serverSocket.getLocalPort();
            alpha.savePeer(peerEntry);

            MinecraftStatusBridge.PollResult poll = alpha.statusBridge().pollWithEndpoint(
                peerEntry, alpha.createStatusBridgePacket(BETA_NAME, List.of()));
            MinecraftStatusBridge.StatusPacket response = poll.packet();
            server.join(10_000L);
            assertFalse(server.isAlive());
            assertNull(serverFailure.get(), () -> "status server thread failed: " + serverFailure.get());
            assertTrue(response != null);
            assertTrue(response.verify());
            assertEquals(BETA_NAME, response.sourceServer());
            assertEquals("127.0.0.1", poll.host());
            alpha.start();
            assertTrue(alpha.handleStatusBridgeResponse(BETA_NAME, response, 1L, poll.host()));
            assertEquals("127.0.0.1", alpha.privatePlayerEndpoint(BETA_NAME));
            assertTrue(beta.isPeerReady(ALPHA_NAME));
        }
    }

    @Test
    void gamePortHostsPreferPublicAndDeduplicateFallbacks() {
        NetworkConfig.PeerEntry peer = new NetworkConfig.PeerEntry();
        peer.publicHost = "play.example.test";
        peer.host = "play.example.test";
        peer.fallbackHosts = "192.168.1.42, play.example.test, 127.0.0.1";

        assertEquals(List.of("play.example.test", "192.168.1.42", "127.0.0.1"),
            MinecraftStatusBridge.gamePortHosts(peer));
    }

    private static void serveStatusBridgeOnce(ServerSocket serverSocket, NetworkManager beta, AtomicReference<Throwable> failure) {
        try (Socket socket = serverSocket.accept()) {
            InputStream input = socket.getInputStream();
            OutputStream output = socket.getOutputStream();
            int handshakeLength = readVarInt(input);
            byte[] handshake = input.readNBytes(handshakeLength);
            if (handshake.length != handshakeLength) {
                throw new EOFException("truncated handshake packet");
            }
            int[] cursor = {0};
            int packetId = readVarInt(handshake, cursor);
            if (packetId != 0) {
                throw new IOException("unexpected handshake packet id: " + packetId);
            }
            readVarInt(handshake, cursor);
            int hostLength = readVarInt(handshake, cursor);
            String address = new String(handshake, cursor[0], hostLength, StandardCharsets.UTF_8);
            if (!address.startsWith(HOST_PREFIX)) {
                throw new IOException("handshake server-address is missing the sideband prefix: " + address);
            }
            int statusRequestLength = readVarInt(input);
            byte[] statusRequest = input.readNBytes(statusRequestLength);
            if (statusRequest.length != statusRequestLength) {
                throw new EOFException("truncated status request packet");
            }
            MinecraftStatusBridge.StatusPacket request = MinecraftStatusBridge.StatusPacket.decode(address.substring(HOST_PREFIX.length()), beta.compression());
            MinecraftStatusBridge.StatusPacket response = beta.handleStatusBridgeRequest(request);
            if (response == null) {
                throw new IOException("beta rejected the status bridge request");
            }
            String json = "{\"wormholes\":\"" + response.encode(beta.compression()) + "\"}";
            byte[] jsonBytes = json.getBytes(StandardCharsets.UTF_8);
            ByteArrayOutputStream packet = new ByteArrayOutputStream(jsonBytes.length + 8);
            writeVarInt(packet, 0);
            writeVarInt(packet, jsonBytes.length);
            packet.write(jsonBytes);
            byte[] body = packet.toByteArray();
            writeVarInt(output, body.length);
            output.write(body);
            output.flush();
        } catch (Throwable t) {
            failure.compareAndSet(null, t);
        }
    }
}
