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
import java.util.Random;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BooleanSupplier;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

@Timeout(60)
class DictionaryRotationLifecycleTest {
    private static final Logger LOGGER = Logger.getLogger("DictionaryRotationLifecycleTest");
    private static final String ALPHA_NAME = "alpha";
    private static final String BETA_NAME = "beta";
    private static final int STREAM_MESSAGES = 100;

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
        config.transport.compressionDictTrainBytes = 512 * 1024;
        config.transport.compressionDictTargetSize = 8 * 1024;
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

    private static final String[] TOKENS_A = {
        "portal:overworld:gateway;state=open;frame=N,E,U;",
        "origin=10.5,64.0,20.5;bounds=9.5,63.5,19.5:11.5,66.5,21.5;",
        "chunk:12:-7:palette=minecraft:stone,minecraft:dirt,minecraft:grass_block;",
        "entity:minecraft:zombie:pos=1.0,2.0,3.0:health=20.0;",
        "diff:seq=42:blocks=minecraft:oak_fence[waterlogged=false];"
    };

    private static final String[] TOKENS_B = {
        "biome#minecraft:deep_dark#depth=-32#scale=0.75#",
        "vel=-0.25,0.0,0.75|yaw=180.0|pitch=-12.5|onGround=true|",
        "nbt{Items:[{id:diamond_sword,Count:1b,tag:{Enchantments:[]}}]}",
        "light:block=15:sky=0:section=8:mask=0xFFEE;",
        "REDSTONE|LAMP|COMPARATOR|OBSERVER|PISTON|"
    };

    private static void fillCollector(NetworkManager manager, long seed, String[] tokens) {
        Random random = new Random(seed);
        DictionarySampleCollector collector = manager.dictionarySampleCollector();
        int guard = 0;
        while (!collector.isFull() && guard++ < 8192) {
            StringBuilder builder = new StringBuilder(1200);
            while (builder.length() < 1024) {
                builder.append(tokens[random.nextInt(tokens.length)]).append(random.nextInt(1000));
            }
            byte[] bytes = builder.toString().getBytes(java.nio.charset.StandardCharsets.US_ASCII);
            byte[] sample = new byte[1024];
            System.arraycopy(bytes, 0, sample, 0, 1024);
            collector.record(sample);
        }
        assertTrue(collector.isFull(), "collector should fill within the guard bound");
    }

    private static int negotiatedVersionWith(NetworkManager manager, String peerName) {
        for (NetworkManager.PeerSnapshot snapshot : manager.peerSnapshots()) {
            if (peerName.equals(snapshot.name())) {
                return snapshot.dictVersion();
            }
        }
        return -1;
    }

    private static WireMessage.PortalDirectory bulkyDirectory(int portals) {
        List<PortalInfo> infos = new ArrayList<>(portals);
        for (int i = 0; i < portals; i++) {
            infos.add(new PortalInfo(new UUID(7L, i), "Gateway lifecycle " + i, "world", "GATEWAY", true, "N", "E", "U",
                10.5D + i, 64.0D, 20.5D,
                9.5D, 63.5D, 19.5D,
                11.5D, 66.5D, 21.5D));
        }
        return new WireMessage.PortalDirectory(infos);
    }

    @Test
    void midStreamDictionaryRotationKeepsConnectionsAndReenablesDictMode() throws IOException {
        int portA = freePort();
        int portB = freePort();
        NetworkManager alpha = manager(config(portA, ALPHA_NAME), 25565, "rotate-alpha");
        NetworkManager beta = manager(config(portB, BETA_NAME), 25566, "rotate-beta");
        alpha.savePeer(route(BETA_NAME, portB));
        beta.savePeer(route(ALPHA_NAME, portA));

        AtomicBoolean disconnected = new AtomicBoolean();
        AtomicInteger betaReceived = new AtomicInteger();
        alpha.setPeerStateSink((name, up) -> {
            if (!up) {
                disconnected.set(true);
            }
        });
        beta.setPeerStateSink((name, up) -> {
            if (!up) {
                disconnected.set(true);
            }
        });
        beta.setMessageSink((peerName, message) -> {
            if (message instanceof WireMessage.PortalDirectory) {
                betaReceived.incrementAndGet();
            }
        });

        alpha.start();
        beta.start();
        awaitTrue("connected", () -> alpha.isPeerReady(BETA_NAME) && beta.isPeerReady(ALPHA_NAME), 10_000L);

        fillCollector(alpha, 1L, TOKENS_A);
        alpha.retrainNow();
        CompressionDictionary first = alpha.currentDictionary();
        assertNotNull(first, "first retrain must adopt unconditionally");
        int firstVersion = first.version();
        awaitTrue("both sides negotiate dict v1",
            () -> negotiatedVersionWith(alpha, BETA_NAME) == firstVersion && negotiatedVersionWith(beta, ALPHA_NAME) == firstVersion,
            15_000L);

        WireMessage.PortalDirectory directory = bulkyDirectory(6);
        for (int i = 0; i < STREAM_MESSAGES; i++) {
            assertTrue(alpha.send(BETA_NAME, directory));
        }
        awaitTrue("first stream fully delivered", () -> betaReceived.get() >= STREAM_MESSAGES, 15_000L);

        fillCollector(alpha, 2L, TOKENS_B);
        alpha.retrainNow();
        CompressionDictionary second = alpha.currentDictionary();
        assertNotNull(second);
        int secondVersion = second.version();
        assertNotEquals(firstVersion, secondVersion, "shifted traffic must adopt a new dictionary");

        for (int i = 0; i < STREAM_MESSAGES; i++) {
            assertTrue(alpha.send(BETA_NAME, directory));
        }
        awaitTrue("second stream fully delivered across the rotation", () -> betaReceived.get() >= 2 * STREAM_MESSAGES, 15_000L);

        awaitTrue("both sides re-enable dict mode on the new version",
            () -> negotiatedVersionWith(alpha, BETA_NAME) == secondVersion && negotiatedVersionWith(beta, ALPHA_NAME) == secondVersion,
            15_000L);
        assertFalse(disconnected.get(), "dictionary rotation must never tear down connections");
        assertTrue(alpha.isPeerReady(BETA_NAME) && beta.isPeerReady(ALPHA_NAME));
    }
}
