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
import java.util.function.BooleanSupplier;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

@Timeout(60)
class DictionaryRetrainGateTest {
    private static final Logger LOGGER = Logger.getLogger("DictionaryRetrainGateTest");

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

    private NetworkManager startedManager(String identityName) throws IOException {
        NetworkConfig config = new NetworkConfig();
        config.enabled = true;
        config.serverName = identityName;
        config.advertiseHostOverride = "127.0.0.1";
        config.listenPort = freePort();
        config.transport.compressionDictTrainBytes = 512 * 1024;
        config.transport.compressionDictTargetSize = 8 * 1024;
        NetworkManager manager = new NetworkManager(LOGGER, config, "26.2", "test", 25565, tempDir.resolve(identityName));
        managers.add(manager);
        manager.start();
        assertTrue(manager.isRunning());
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

    @Test
    void firstTrainingAdoptsUnconditionally() throws IOException {
        NetworkManager manager = startedManager("gate-first");
        assertNull(manager.currentDictionary());
        fillCollector(manager, 1L, TOKENS_A);
        manager.retrainNow();
        assertNotNull(manager.currentDictionary(), "first dictionary must be adopted without gating");
        assertEquals(0L, manager.dictionarySampleCollector().accumulatedBytes(), "collector must reset after adoption");
    }

    @Test
    void retrainOnSameTrafficIsRejected() throws IOException {
        NetworkManager manager = startedManager("gate-same");
        fillCollector(manager, 1L, TOKENS_A);
        manager.retrainNow();
        CompressionDictionary incumbent = manager.currentDictionary();
        assertNotNull(incumbent);

        fillCollector(manager, 1L, TOKENS_A);
        manager.retrainNow();
        CompressionDictionary after = manager.currentDictionary();
        assertTrue(CompressionDictionary.sameHash(incumbent.hash(), after.hash()),
            "retraining on identical traffic must not rotate the dictionary");
        assertEquals(0L, manager.dictionarySampleCollector().accumulatedBytes(),
            "collector must reset after a rejected retrain to open a fresh window");
    }

    @Test
    void retrainOnShiftedTrafficIsAdopted() throws IOException {
        NetworkManager manager = startedManager("gate-shift");
        fillCollector(manager, 1L, TOKENS_A);
        manager.retrainNow();
        CompressionDictionary incumbent = manager.currentDictionary();
        assertNotNull(incumbent);

        fillCollector(manager, 2L, TOKENS_B);
        manager.retrainNow();
        CompressionDictionary after = manager.currentDictionary();
        assertNotNull(after);
        assertFalse(CompressionDictionary.sameHash(incumbent.hash(), after.hash()),
            "structurally shifted traffic must beat the incumbent on the holdout and be adopted");
        assertEquals(0L, manager.dictionarySampleCollector().accumulatedBytes());
    }

    @Test
    void retrainAfterStopDoesNotInstall() throws IOException {
        NetworkManager manager = startedManager("gate-stopped");
        manager.stop();
        fillCollector(manager, 1L, TOKENS_A);
        manager.retrainNow();
        assertNull(manager.currentDictionary(), "a retrain completing after stop() must be discarded");
    }

    @Test
    void retrainInFlightGuardPreventsOverlap() throws IOException {
        NetworkManager manager = startedManager("gate-overlap");
        fillCollector(manager, 1L, TOKENS_A);
        manager.maybeRetrainDictionary();
        manager.maybeRetrainDictionary();
        awaitTrue("background retrain adopts", () -> manager.currentDictionary() != null, 15_000L);
        CompressionDictionary adopted = manager.currentDictionary();
        awaitTrue("collector reset after adoption", () -> manager.dictionarySampleCollector().accumulatedBytes() == 0L, 15_000L);
        CompressionDictionary settled = manager.currentDictionary();
        assertTrue(CompressionDictionary.sameHash(adopted.hash(), settled.hash()),
            "rapid double trigger must produce exactly one dictionary rotation");
    }
}
