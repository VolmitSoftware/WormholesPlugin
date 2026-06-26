package art.arcane.wormholes.service;

import art.arcane.wormholes.network.NetworkManager;
import art.arcane.wormholes.network.TraversalService;
import art.arcane.wormholes.network.WireCompression;
import art.arcane.wormholes.network.view.EntityRateScheduler;
import art.arcane.wormholes.network.view.PreShipPredictor;
import art.arcane.wormholes.network.view.ViewServer;
import art.arcane.wormholes.service.StatsSnapshotWriter.CompressionState;
import art.arcane.wormholes.service.StatsSnapshotWriter.DictState;
import art.arcane.wormholes.service.StatsSnapshotWriter.SnapshotData;
import art.arcane.wormholes.service.StatsSnapshotWriter.TransportSettings;
import art.arcane.wormholes.service.StatsSnapshotWriter.ViewMetrics;
import art.arcane.wormholes.service.StatsSnapshotWriter.ViewSettings;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class StatsSnapshotWriterTest {

    @Test
    void renderedSnapshotContainsAllSectionHeaders() {
        SnapshotData data = sampleSnapshot();
        String rendered = StatsSnapshotWriter.render(data);
        assertTrue(rendered.startsWith("=== Wormholes Stats ===\n"), "header line missing");
        assertTrue(rendered.contains("CONFIG\n"), "CONFIG section missing");
        assertTrue(rendered.contains("PEERS (3)\n"), "PEERS section header missing or wrong count");
        assertTrue(rendered.contains("COMPRESSION\n"), "COMPRESSION section missing");
        assertTrue(rendered.contains("VIEW STREAMING\n"), "VIEW STREAMING section missing");
        assertTrue(rendered.contains("TRANSFERS\n"), "TRANSFERS section missing");
        assertTrue(rendered.contains("ERRORS (last 60s)\n"), "ERRORS section missing");
    }

    @Test
    void peerRowsAreEmittedInDeterministicSortedOrder() {
        SnapshotData data = sampleSnapshot();
        String rendered = StatsSnapshotWriter.render(data);
        int idxEast = rendered.indexOf("east-1");
        int idxFallback = rendered.indexOf("fallback-3");
        int idxWest = rendered.indexOf("west-2");
        assertTrue(idxEast > 0 && idxFallback > 0 && idxWest > 0, "all peer names present");
        assertTrue(idxEast < idxFallback, "east-1 sorts before fallback-3");
        assertTrue(idxFallback < idxWest, "fallback-3 sorts before west-2");
    }

    @Test
    void disconnectedPeerStillRendersWithStatusTag() {
        List<NetworkManager.PeerSnapshot> peers = new ArrayList<>(samplePeers());
        peers.add(new NetworkManager.PeerSnapshot("zulu-9", "TCP", "203.0.113.99:25599", "dict",
            7, "ab12cd34", 4_500L, 88L, false, true));
        SnapshotData data = sampleSnapshotWithPeers(peers);
        String rendered = StatsSnapshotWriter.render(data);
        assertTrue(rendered.contains("zulu-9"), "disconnected peer must still render");
        assertTrue(rendered.contains("[disconnected]"), "disconnected tag missing");
    }

    @Test
    void pendingHandshakePeerShowsDashForDict() {
        List<NetworkManager.PeerSnapshot> peers = List.of(
            new NetworkManager.PeerSnapshot("alpha", "TCP", "10.0.0.1:8901", "pending", 0, "-",
                -1L, -1L, false, false)
        );
        SnapshotData data = sampleSnapshotWithPeers(peers);
        String rendered = StatsSnapshotWriter.render(data);
        assertTrue(rendered.contains("alpha"));
        assertTrue(rendered.contains("pending"));
    }

    @Test
    void writeAtomicProducesCompleteFileOnBothCalls(@TempDir Path tempDir) throws IOException {
        Path output = tempDir.resolve("wormholes-stats.txt");
        SnapshotData first = sampleSnapshot();
        StatsSnapshotWriter.writeAtomic(output, StatsSnapshotWriter.render(first));
        String firstRead = Files.readString(output);
        assertTrue(firstRead.contains("=== Wormholes Stats ==="), "first write must include header");
        assertTrue(firstRead.endsWith("- (none)\n"), "first write must end completely");

        SnapshotData second = sampleSnapshotWithPeers(List.of(
            new NetworkManager.PeerSnapshot("solo-peer", "UDS", "/tmp/wh-solo.sock", "dict",
                4, "deadbeef", 12L, 1L, true, false)
        ));
        StatsSnapshotWriter.writeAtomic(output, StatsSnapshotWriter.render(second));
        String secondRead = Files.readString(output);
        assertTrue(secondRead.contains("solo-peer"), "second write must contain new peer");
        assertTrue(secondRead.contains("=== Wormholes Stats ==="), "second write must include header");
        assertFalse(secondRead.contains("east-1"), "second write must NOT contain stale data from first snapshot");
        assertTrue(secondRead.endsWith("- (none)\n"), "second write must also end completely");
    }

    @Test
    void emptyErrorsListRendersAsNonePlaceholder() {
        SnapshotData data = sampleSnapshot();
        String rendered = StatsSnapshotWriter.render(data);
        assertTrue(rendered.contains("- (none)"));
    }

    @Test
    void recentErrorsAreCappedAtTen() {
        List<String> errors = new ArrayList<>();
        for (int i = 0; i < 20; i++) {
            errors.add("err-" + i);
        }
        SnapshotData base = sampleSnapshot();
        SnapshotData data = new SnapshotData(
            base.generatedAt(), base.uptime(), base.pluginVersion(), base.intervalSec(),
            base.localName(), base.transport(), base.view(), base.udsDirDisplay(),
            base.peers(), base.compression(), base.viewMetrics(), base.transfers(), errors
        );
        String rendered = StatsSnapshotWriter.render(data);
        assertTrue(rendered.contains("err-0"));
        assertTrue(rendered.contains("err-9"));
        assertFalse(rendered.contains("err-10"), "errors list must cap at 10");
    }

    private static SnapshotData sampleSnapshot() {
        return sampleSnapshotWithPeers(samplePeers());
    }

    private static SnapshotData sampleSnapshotWithPeers(List<NetworkManager.PeerSnapshot> peers) {
        TransportSettings transport = new TransportSettings(true, 3, 10_485_760L, true, "", 600);
        ViewSettings view = new ViewSettings(
            60.0D, 0.40D, true,
            50, 200, 0.50D, true,
            20.0D, 10.0D, 4.0D, 1.0D,
            true, 24.0D, 0.10D
        );
        WireCompression.Stats wireStats = new WireCompression.Stats(
            13_000_000L, 3_600_000L, 19_000_000L, 5_500_000L,
            142L, 0L, 91_204L
        );
        DictState dict = new DictState(true, 3, "a1b2c3d4",
            1_750_000_000_000L, 65_536L,
            4_900_000L, 10_485_760L, 358L);
        CompressionState compression = new CompressionState(wireStats, dict);

        ViewServer.Stats viewStats = new ViewServer.Stats(47, 312, 8_266L, 82_938L, 4_000L, 200L);
        EntityRateScheduler.Stats rateStats = new EntityRateScheduler.Stats(360L, 470L, 356L, 158L);
        EntityRateScheduler.Bands bands = new EntityRateScheduler.Bands(16.0D, 64.0D, 128.0D,
            20.0D, 10.0D, 4.0D, 1.0D);
        PreShipPredictor.Stats preship = new PreShipPredictor.Stats(192L, 51L, 138L, 3);
        ViewMetrics viewMetrics = new ViewMetrics(viewStats, rateStats, bands, preship, 10.0D);

        TraversalService.Stats transfers = new TraversalService.Stats(38L, 0L, 1);

        return new SnapshotData(
            Instant.parse("2026-06-15T14:33:21Z"),
            Duration.ofSeconds((2L * 3600L) + (13L * 60L)),
            "1.0.0-26.2",
            10,
            "hub-1",
            transport,
            view,
            "<plugin-data>/uds",
            peers,
            compression,
            viewMetrics,
            transfers,
            List.of()
        );
    }

    private static List<NetworkManager.PeerSnapshot> samplePeers() {
        return List.of(
            new NetworkManager.PeerSnapshot("east-1", "TCP", "peer-east.example:25599", "dict",
                3, "a1b2c3d4", 300L, 12L, true, false),
            new NetworkManager.PeerSnapshot("west-2", "UDS", "/tmp/wh-west.sock", "dict",
                3, "a1b2c3d4", 100L, 1L, true, false),
            new NetworkManager.PeerSnapshot("fallback-3", "TCP", "203.0.113.7:25599", "none",
                0, "-", 1_200L, 88L, true, false)
        );
    }
}
