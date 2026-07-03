package art.arcane.wormholes.network;

import art.arcane.wormholes.config.toml.NetworkConfig;
import art.arcane.wormholes.network.replication.ChunkBulk;
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
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

@Timeout(30)
class PeerConnectionAsyncEncodeTest {
    private static final Logger LOGGER = Logger.getLogger("PeerConnectionAsyncEncodeTest");
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

    private NetworkManager manager(NetworkConfig config, int gamePort, String identityName) {
        NetworkManager manager = new NetworkManager(LOGGER, config, "26.2", "test", gamePort, tempDir.resolve(identityName));
        managers.add(manager);
        return manager;
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
    void largeCompressibleMessageDeliveredAfterAsyncEncode() throws IOException, InterruptedException {
        int portA = freePort();
        int portB = freePort();
        NetworkManager alpha = manager(config(portA, ALPHA_NAME), 25565, "async-alpha");
        NetworkManager beta = manager(config(portB, BETA_NAME), 25566, "async-beta");
        alpha.savePeer(route(BETA_NAME, portB));
        beta.savePeer(route(ALPHA_NAME, portA));
        LinkedBlockingQueue<Integer> received = new LinkedBlockingQueue<>();
        beta.setMessageSink((peerName, message) -> {
            if (message instanceof WireMessage.EntityTransfer transfer) {
                received.offer(transfer.entitySnapshot().length);
            }
        });
        alpha.start();
        beta.start();
        awaitTrue("connected", () -> alpha.isPeerReady(BETA_NAME) && beta.isPeerReady(ALPHA_NAME), 10_000L);

        byte[] snapshot = new byte[200_000];
        for (int i = 0; i < snapshot.length; i++) {
            snapshot[i] = (byte) (i % 11);
        }
        WireMessage.EntityTransfer transfer = new WireMessage.EntityTransfer(UUID.randomUUID(), UUID.randomUUID(), snapshot, traversive());
        assertTrue(alpha.send(BETA_NAME, transfer));
        assertEquals(snapshot.length, received.poll(10L, TimeUnit.SECONDS));
    }

    @Test
    void encodeFailureDropsMessageAndKeepsConnectionOpen() throws IOException, InterruptedException {
        int portA = freePort();
        int portB = freePort();
        NetworkManager alpha = manager(config(portA, ALPHA_NAME), 25565, "drop-alpha");
        NetworkManager beta = manager(config(portB, BETA_NAME), 25566, "drop-beta");
        alpha.savePeer(route(BETA_NAME, portB));
        beta.savePeer(route(ALPHA_NAME, portA));
        LinkedBlockingQueue<String> received = new LinkedBlockingQueue<>();
        beta.setMessageSink((peerName, message) -> received.offer(message.type().name()));
        alpha.start();
        beta.start();
        awaitTrue("connected", () -> alpha.isPeerReady(BETA_NAME) && beta.isPeerReady(ALPHA_NAME), 10_000L);

        Random random = new Random(99L);
        List<ChunkBulk> chunks = new ArrayList<>(3);
        for (int i = 0; i < 3; i++) {
            byte[] payload = new byte[2 * 1024 * 1024];
            random.nextBytes(payload);
            chunks.add(new ChunkBulk(i, i + 1L, payload));
        }
        WireMessage.ChunkBulkBatch oversized = new WireMessage.ChunkBulkBatch(chunks);
        assertTrue(alpha.send(BETA_NAME, oversized), "oversized message should queue; the drop happens at writer-thread encode");

        assertTrue(alpha.send(BETA_NAME, new WireMessage.PortalDirectory(List.of())));
        String delivered = received.poll(10L, TimeUnit.SECONDS);
        assertEquals("PORTAL_DIRECTORY", delivered, "small message must be delivered after the oversized frame was dropped");
        assertTrue(alpha.isPeerReady(BETA_NAME), "connection must remain READY after an encode failure");
        assertTrue(beta.isPeerReady(ALPHA_NAME));
    }
}
