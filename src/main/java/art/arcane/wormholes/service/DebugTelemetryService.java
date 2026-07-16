package art.arcane.wormholes.service;

import art.arcane.volmlib.util.scheduling.SchedulerRuntime;
import art.arcane.wormholes.Settings;
import art.arcane.wormholes.Wormholes;
import art.arcane.wormholes.config.WormholesSettings;
import art.arcane.wormholes.config.toml.NetworkConfig;
import art.arcane.wormholes.network.NetworkManager;
import art.arcane.wormholes.network.TraversalService;
import art.arcane.wormholes.network.WireCompression;
import art.arcane.wormholes.network.replication.ChunkReplicationManager;
import art.arcane.wormholes.network.replication.capture.CaptureRuntime;
import art.arcane.wormholes.network.replication.capture.RegionalDiffAccumulator;
import art.arcane.wormholes.network.view.ViewServer;
import org.bukkit.Bukkit;

import java.io.BufferedReader;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;
import java.util.Properties;
import java.util.logging.Level;
import java.util.logging.Logger;

public final class DebugTelemetryService {
    private static final int REPORT_INTERVAL_TICKS = 20;

    private final Wormholes plugin;
    private final Logger logger;
    private boolean started;
    private boolean observedEnabled;
    private Boolean runtimeOverride;
    private int taskId;
    private CounterSnapshot previous;

    public DebugTelemetryService(Wormholes plugin) {
        this.plugin = plugin;
        this.logger = plugin.getLogger();
    }

    public synchronized void start() {
        if (started) {
            return;
        }
        SchedulerRuntime runtime = plugin.getSchedulerRuntime();
        if (runtime == null) {
            logger.warning("[debug] scheduler runtime unavailable; console telemetry disabled");
            return;
        }
        started = true;
        taskId = runtime.ar(this::report, REPORT_INTERVAL_TICKS);
    }

    public synchronized void stop() {
        if (!started) {
            return;
        }
        started = false;
        observedEnabled = false;
        runtimeOverride = null;
        previous = null;
        SchedulerRuntime runtime = plugin.getSchedulerRuntime();
        if (runtime != null && taskId != 0) {
            runtime.car(taskId);
        }
        taskId = 0;
    }

    public synchronized boolean toggle(String actor) {
        boolean current = runtimeOverride == null ? Settings.DEBUG : runtimeOverride.booleanValue();
        boolean enabled = !current;
        runtimeOverride = Boolean.valueOf(enabled);
        Settings.DEBUG = enabled;
        previous = null;
        observedEnabled = enabled;
        logger.info("[debug] verbose logging and console telemetry " + (enabled ? "ENABLED" : "DISABLED")
            + " by " + singleLine(actor));
        if (enabled) {
            logRuntimeConfig();
        }
        return enabled;
    }

    public synchronized void onSettingsReloaded() {
        if (runtimeOverride != null) {
            Settings.DEBUG = runtimeOverride.booleanValue();
        }
    }

    private synchronized void report() {
        if (!started) {
            return;
        }
        if (runtimeOverride != null) {
            Settings.DEBUG = runtimeOverride.booleanValue();
        }
        if (!Settings.DEBUG) {
            observedEnabled = false;
            previous = null;
            return;
        }
        try {
            if (!observedEnabled) {
                observedEnabled = true;
                logRuntimeConfig();
            }
            RuntimeSnapshot current = snapshot(System.nanoTime());
            RateSnapshot rates = previous == null
                ? RateSnapshot.zero()
                : RateSnapshot.between(previous, current.counters());
            previous = current.counters();
            logProjection();
            logView(current, rates);
            logNetwork(current, rates);
            logTransfers(current, rates);
            logPeers(current.peers(), current.network());
        } catch (Throwable error) {
            logger.log(Level.WARNING, "[debug] console telemetry sample failed", error);
        }
    }

    private RuntimeSnapshot snapshot(long nowNanos) {
        NetworkManager network = plugin.getNetworkManager();
        WireCompression.Stats wire = network == null
            ? new WireCompression.Stats(0L, 0L, 0L, 0L, 0L, 0L, 0L)
            : network.wireCompressionMetrics().snapshot();
        NetworkManager.DebugSnapshot networkDebug = network == null
            ? new NetworkManager.DebugSnapshot(0L, 0L, 0L, 0L, 0L)
            : network.debugSnapshot();
        List<NetworkManager.PeerSnapshot> peers = network == null ? List.of() : network.peerSnapshots();
        ChunkReplicationManager.Stats replication = network == null
            ? new ChunkReplicationManager.Stats(0L, 0L, 0L, 0L, 0L)
            : network.getReplicationManager().statsSnapshot();

        ViewServer viewServer = plugin.getViewServer();
        ViewServer.Stats view = viewServer == null
            ? new ViewServer.Stats(0, 0, 0L, 0L, 0L, 0L)
            : viewServer.statsSnapshot();
        TraversalService traversalService = plugin.getTraversalService();
        TraversalService.Stats transfers = traversalService == null
            ? new TraversalService.Stats(0L, 0L, 0)
            : traversalService.statsSnapshot();

        CaptureRuntime captureRuntime = plugin.getCaptureRuntime();
        RegionalDiffAccumulator.Stats capture = captureRuntime == null
            ? new RegionalDiffAccumulator.Stats(0L, 0L, 0L, 0L, 0L, 0L)
            : captureRuntime.statsSnapshot().accumulator();

        CounterSnapshot counters = new CounterSnapshot(
            nowNanos,
            wire.rawBytesIn(),
            wire.wireBytesIn(),
            wire.rawBytesOut(),
            wire.wireBytesOut(),
            view.chunkBulkSentCount(),
            view.chunkDiffSentCount(),
            view.entitySendCount(),
            view.timeSendCount(),
            replication.blocksSent(),
            replication.resyncRequests(),
            transfers.completed(),
            transfers.failed(),
            networkDebug.sidebandDroppedBytes(),
            networkDebug.sidebandDroppedCount(),
            capture.blocksDropped(),
            capture.overflowDrops()
        );
        return new RuntimeSnapshot(counters, network, networkDebug, peers, view, replication, transfers, capture);
    }

    private void logProjection() {
        long now = System.currentTimeMillis();
        logger.info("[debug/projection] active=" + WormholesTelemetry.activeProjections()
            + " observers=" + WormholesTelemetry.projectionObservers()
            + " spoofed=" + WormholesTelemetry.spoofedEntities()
            + " clientPackets=" + formatRate(WormholesTelemetry.packetsPerSecond(now)) + "/s"
            + " blockChanges=" + formatRate(WormholesTelemetry.blockChangesPerSecond(now)) + "/s"
            + " render=" + formatRate(WormholesTelemetry.renderMsPerSecond(now)) + "ms/s");
    }

    private void logView(RuntimeSnapshot current, RateSnapshot rates) {
        ViewServer.Stats view = current.view();
        ChunkReplicationManager.Stats replication = current.replication();
        logger.info("[debug/view] subscriptions=" + view.subscriptions()
            + " trackedEntities=" + view.trackedEntities()
            + " bulk=" + formatRate(rates.viewBulkPerSecond()) + "/s"
            + " diff=" + formatRate(rates.viewDiffPerSecond()) + "/s"
            + " entityUpdates=" + formatRate(rates.viewEntityPerSecond()) + "/s"
            + " time=" + formatRate(rates.viewTimePerSecond()) + "/s"
            + " replicatedBlocks=" + formatRate(rates.replicatedBlocksPerSecond()) + "/s"
            + " resync=" + replication.resyncRequests() + " (+" + formatRate(rates.resyncPerSecond()) + "/s)");
    }

    private void logNetwork(RuntimeSnapshot current, RateSnapshot rates) {
        int connected = 0;
        int tcp = 0;
        int uds = 0;
        int sideband = 0;
        long maximumRtt = -1L;
        long maximumIdle = -1L;
        for (NetworkManager.PeerSnapshot peer : current.peers()) {
            if (!peer.handshakeComplete() || peer.disconnected()) {
                continue;
            }
            connected++;
            switch (peer.transport()) {
                case "TCP" -> tcp++;
                case "UDS" -> uds++;
                case "SIDEBAND" -> sideband++;
                default -> {
                }
            }
            maximumRtt = Math.max(maximumRtt, peer.rttMillis());
            maximumIdle = Math.max(maximumIdle, peer.lastInboundAgeMillis());
        }

        NetworkManager.DebugSnapshot debug = current.networkDebug();
        logger.info("[debug/network] peers=" + current.peers().size()
            + " connected=" + connected
            + " tcp=" + tcp
            + " uds=" + uds
            + " sideband=" + sideband
            + " codecTx=" + formatBytesRate(rates.wireBytesOutPerSecond())
            + " raw=" + formatBytesRate(rates.rawBytesOutPerSecond())
            + " ratio=" + formatRatio(rates.wireBytesOutPerSecond(), rates.rawBytesOutPerSecond())
            + " codecRx=" + formatBytesRate(rates.wireBytesInPerSecond())
            + " raw=" + formatBytesRate(rates.rawBytesInPerSecond())
            + " ratio=" + formatRatio(rates.wireBytesInPerSecond(), rates.rawBytesInPerSecond())
            + " rawQueue=" + debug.rawWriteQueueFrames()
            + " sideQueue=" + debug.sidebandQueuedCount() + "/" + formatBytes(debug.sidebandQueuedBytes())
            + " sideDrops=" + debug.sidebandDroppedCount() + "/" + formatBytes(debug.sidebandDroppedBytes())
            + " (+" + formatRate(rates.sidebandDroppedCountPerSecond()) + "/s, "
            + formatBytesRate(rates.sidebandDroppedBytesPerSecond()) + ")"
            + " rttMax=" + formatMillis(maximumRtt)
            + " idleMax=" + formatMillis(maximumIdle));
    }

    private void logTransfers(RuntimeSnapshot current, RateSnapshot rates) {
        TraversalService.Stats transfers = current.transfers();
        RegionalDiffAccumulator.Stats capture = current.capture();
        logger.info("[debug/handoff] inFlight=" + transfers.inFlight()
            + " completed=" + transfers.completed() + " (+" + formatRate(rates.transfersCompletedPerSecond()) + "/s)"
            + " failed=" + transfers.failed() + " (+" + formatRate(rates.transfersFailedPerSecond()) + "/s)"
            + " captureDropped=" + capture.blocksDropped() + " (+" + formatRate(rates.captureDroppedPerSecond()) + "/s)"
            + " captureOverflow=" + capture.overflowDrops() + " (+" + formatRate(rates.captureOverflowPerSecond()) + "/s)");
    }

    private void logPeers(List<NetworkManager.PeerSnapshot> peers, NetworkManager network) {
        for (NetworkManager.PeerSnapshot peer : peers) {
            String state = peer.disconnected() ? "CLOSED" : peer.handshakeComplete() ? "CONNECTED" : "PENDING";
            NetworkConfig.PeerEntry config = network == null ? null : network.getPeer(peer.name());
            String endpoints = config == null
                ? ""
                : " peerHost=" + singleLine(config.host)
                    + " fallbacks=" + singleLine(config.fallbackHosts)
                    + " publicHost=" + singleLine(config.publicHost)
                    + " gamePort=" + config.publicPort
                    + " proxy=" + config.useProxy;
            logger.info("[debug/peer] name=" + singleLine(peer.name())
                + " state=" + state
                + " transport=" + singleLine(peer.transport())
                + " remote=" + singleLine(peer.remoteAddress())
                + " rtt=" + formatMillis(peer.rttMillis())
                + " idle=" + formatMillis(peer.lastInboundAgeMillis())
                + " compression=" + singleLine(peer.compressionMode())
                + " dict=" + peer.dictVersion()
                + endpoints);
        }
    }

    private void logRuntimeConfig() {
        WormholesSettings settings = plugin.getSettings();
        NetworkConfig networkConfig = settings == null ? null : settings.getNetwork();
        NetworkManager network = plugin.getNetworkManager();
        logger.info("[debug/config] server=" + (network == null ? "unavailable" : singleLine(network.getLocalName()))
            + " network=" + (networkConfig != null && networkConfig.enabled)
            + " transferMode=" + (networkConfig == null ? "unavailable" : singleLine(networkConfig.transferMode))
            + " autoAcceptTransfers=" + (networkConfig != null && networkConfig.autoAcceptTransfers)
            + " accepts-transfers=" + nativeTransferSetting()
            + " gamePort=" + Bukkit.getPort()
            + " rawPort=" + (network == null ? "unavailable" : Integer.toString(network.getBoundListenPort())));
    }

    private static String nativeTransferSetting() {
        Path path = Path.of("server.properties");
        if (!Files.isRegularFile(path)) {
            return "unavailable";
        }
        Properties properties = new Properties();
        try (BufferedReader reader = Files.newBufferedReader(path)) {
            properties.load(reader);
            return singleLine(properties.getProperty("accepts-transfers", "unset"));
        } catch (IOException | SecurityException ignored) {
            return "unavailable";
        }
    }

    private static String singleLine(String value) {
        if (value == null || value.isBlank()) {
            return "-";
        }
        return value.replace('\n', ' ').replace('\r', ' ').replace('\t', ' ').trim();
    }

    private static String formatRate(double value) {
        return String.format(Locale.ROOT, "%.1f", Math.max(0.0D, value));
    }

    private static String formatRatio(double wire, double raw) {
        if (raw <= 0.0D) {
            return "-";
        }
        return String.format(Locale.ROOT, "%.2f", Math.max(0.0D, wire) / raw);
    }

    private static String formatBytesRate(double bytesPerSecond) {
        return formatBytes(Math.round(Math.max(0.0D, bytesPerSecond))) + "/s";
    }

    private static String formatBytes(long bytes) {
        long safeBytes = Math.max(0L, bytes);
        if (safeBytes < 1024L) {
            return safeBytes + "B";
        }
        double kibibytes = safeBytes / 1024.0D;
        if (kibibytes < 1024.0D) {
            return String.format(Locale.ROOT, "%.1fKiB", kibibytes);
        }
        return String.format(Locale.ROOT, "%.1fMiB", kibibytes / 1024.0D);
    }

    private static String formatMillis(long millis) {
        return millis < 0L ? "-" : millis + "ms";
    }

    record CounterSnapshot(long capturedAtNanos,
                           long rawBytesIn, long wireBytesIn, long rawBytesOut, long wireBytesOut,
                           long viewBulk, long viewDiff, long viewEntity, long viewTime,
                           long replicatedBlocks, long resyncs,
                           long transfersCompleted, long transfersFailed,
                           long sidebandDroppedBytes, long sidebandDroppedCount,
                           long captureDropped, long captureOverflow) {
    }

    record RateSnapshot(double elapsedSeconds,
                        double rawBytesInPerSecond, double wireBytesInPerSecond,
                        double rawBytesOutPerSecond, double wireBytesOutPerSecond,
                        double viewBulkPerSecond, double viewDiffPerSecond,
                        double viewEntityPerSecond, double viewTimePerSecond,
                        double replicatedBlocksPerSecond, double resyncPerSecond,
                        double transfersCompletedPerSecond, double transfersFailedPerSecond,
                        double sidebandDroppedBytesPerSecond, double sidebandDroppedCountPerSecond,
                        double captureDroppedPerSecond, double captureOverflowPerSecond) {
        static RateSnapshot zero() {
            return new RateSnapshot(0.0D, 0.0D, 0.0D, 0.0D, 0.0D, 0.0D, 0.0D, 0.0D, 0.0D,
                0.0D, 0.0D, 0.0D, 0.0D, 0.0D, 0.0D, 0.0D, 0.0D);
        }

        static RateSnapshot between(CounterSnapshot previous, CounterSnapshot current) {
            long elapsedNanos = current.capturedAtNanos() - previous.capturedAtNanos();
            if (elapsedNanos <= 0L) {
                return zero();
            }
            double seconds = elapsedNanos / 1_000_000_000.0D;
            return new RateSnapshot(
                seconds,
                rate(previous.rawBytesIn(), current.rawBytesIn(), seconds),
                rate(previous.wireBytesIn(), current.wireBytesIn(), seconds),
                rate(previous.rawBytesOut(), current.rawBytesOut(), seconds),
                rate(previous.wireBytesOut(), current.wireBytesOut(), seconds),
                rate(previous.viewBulk(), current.viewBulk(), seconds),
                rate(previous.viewDiff(), current.viewDiff(), seconds),
                rate(previous.viewEntity(), current.viewEntity(), seconds),
                rate(previous.viewTime(), current.viewTime(), seconds),
                rate(previous.replicatedBlocks(), current.replicatedBlocks(), seconds),
                rate(previous.resyncs(), current.resyncs(), seconds),
                rate(previous.transfersCompleted(), current.transfersCompleted(), seconds),
                rate(previous.transfersFailed(), current.transfersFailed(), seconds),
                rate(previous.sidebandDroppedBytes(), current.sidebandDroppedBytes(), seconds),
                rate(previous.sidebandDroppedCount(), current.sidebandDroppedCount(), seconds),
                rate(previous.captureDropped(), current.captureDropped(), seconds),
                rate(previous.captureOverflow(), current.captureOverflow(), seconds)
            );
        }

        private static double rate(long previous, long current, double seconds) {
            return current < previous ? 0.0D : (current - previous) / seconds;
        }
    }

    private record RuntimeSnapshot(CounterSnapshot counters, NetworkManager network,
                                   NetworkManager.DebugSnapshot networkDebug,
                                   List<NetworkManager.PeerSnapshot> peers,
                                   ViewServer.Stats view,
                                   ChunkReplicationManager.Stats replication,
                                   TraversalService.Stats transfers,
                                   RegionalDiffAccumulator.Stats capture) {
    }
}
