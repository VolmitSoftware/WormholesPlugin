package art.arcane.wormholes.service;

import art.arcane.volmlib.util.scheduling.SchedulerRuntime;
import art.arcane.wormholes.Wormholes;
import art.arcane.wormholes.config.toml.NetworkConfig;
import art.arcane.wormholes.network.CompressionDictionary;
import art.arcane.wormholes.network.DictionarySampleCollector;
import art.arcane.wormholes.network.NetworkManager;
import art.arcane.wormholes.network.TraversalService;
import art.arcane.wormholes.network.WireCompression;
import art.arcane.wormholes.network.view.EntityRateScheduler;
import art.arcane.wormholes.network.view.PreShipPredictor;
import art.arcane.wormholes.network.view.ViewServer;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Supplier;
import java.util.logging.Level;
import java.util.logging.Logger;

public final class StatsSnapshotWriter {
    public record TransportSettings(boolean compressionEnabled, int compressionLevel, long compressionDictTrainBytes,
                                    boolean udsEnabled, String udsDir, int retrainIntervalSec) {
    }

    public record ViewSettings(double coneDegrees, double coneBehindFactor, boolean coneEnabled,
                               int yBiasCaveYMax, int yBiasSkyYMin, double yBiasFactor, boolean yBiasEnabled,
                               double entityRateNearHz, double entityRateMidHz, double entityRateFarHz, double entityRateVeryFarHz,
                               boolean preshipEnabled, double preshipDistance, double preshipMinSpeed) {
    }

    public record DictState(boolean dictPresent, int version, String hashHex, long trainedAtMillis,
                            long dictBytes, long corpusFillBytes, long corpusBudgetBytes, long retrainEtaSec) {
    }

    public record CompressionState(WireCompression.Stats stats, DictState dict) {
    }

    public record ViewMetrics(ViewServer.Stats viewStats, EntityRateScheduler.Stats rateStats,
                              EntityRateScheduler.Bands bands, PreShipPredictor.Stats preship,
                              double intervalSeconds) {
    }

    public record SnapshotData(Instant generatedAt, Duration uptime, String pluginVersion, int intervalSec,
                               String localName, TransportSettings transport, ViewSettings view,
                               String udsDirDisplay, List<NetworkManager.PeerSnapshot> peers,
                               CompressionState compression, ViewMetrics viewMetrics,
                               TraversalService.Stats transfers, List<String> recentErrors) {
    }

    private static final DateTimeFormatter ISO_INSTANT = DateTimeFormatter.ISO_INSTANT.withZone(ZoneOffset.UTC);
    private static final int COL_WIDTH = 96;

    private final Wormholes plugin;
    private final Logger logger;
    private final Supplier<SnapshotData> dataSupplier;
    private final Path outputFile;
    private final long intervalTicks;
    private final AtomicBoolean started = new AtomicBoolean(false);
    private volatile int taskId;

    public StatsSnapshotWriter(Wormholes plugin, Supplier<SnapshotData> dataSupplier, Path outputFile, Duration interval) {
        this.plugin = Objects.requireNonNull(plugin, "plugin");
        this.logger = plugin.getLogger();
        this.dataSupplier = Objects.requireNonNull(dataSupplier, "dataSupplier");
        this.outputFile = Objects.requireNonNull(outputFile, "outputFile");
        long seconds = Math.max(1L, Objects.requireNonNull(interval, "interval").getSeconds());
        this.intervalTicks = Math.max(1L, seconds * 20L);
    }

    public static StatsSnapshotWriter forRuntime(Wormholes plugin, NetworkManager network, ViewServer viewServer,
                                                 TraversalService traversal, PreShipPredictor preShipPredictor,
                                                 Path outputFile, Duration interval, Instant pluginStartedAt) {
        Supplier<SnapshotData> supplier = () -> buildRuntimeSnapshot(plugin, network, viewServer, traversal, preShipPredictor, interval, pluginStartedAt);
        return new StatsSnapshotWriter(plugin, supplier, outputFile, interval);
    }

    public Path getOutputFile() {
        return outputFile;
    }

    public void start() {
        if (!started.compareAndSet(false, true)) {
            return;
        }
        SchedulerRuntime runtime = plugin.getSchedulerRuntime();
        if (runtime == null) {
            logger.warning("stats: scheduler runtime unavailable, snapshot file disabled");
            started.set(false);
            return;
        }
        writeNow();
        this.taskId = runtime.ar(this::writeNow, (int) intervalTicks);
    }

    public void stop() {
        if (!started.compareAndSet(true, false)) {
            return;
        }
        SchedulerRuntime runtime = plugin.getSchedulerRuntime();
        if (runtime != null && taskId != 0) {
            runtime.car(taskId);
            taskId = 0;
        }
    }

    public boolean isRunning() {
        return started.get();
    }

    public void writeNow() {
        try {
            SnapshotData data = dataSupplier.get();
            if (data == null) {
                return;
            }
            String rendered = render(data);
            writeAtomic(outputFile, rendered);
        } catch (Throwable ex) {
            logger.log(Level.WARNING, "stats: snapshot write failed", ex);
        }
    }

    public static void writeAtomic(Path outputFile, String contents) throws IOException {
        Path parent = outputFile.toAbsolutePath().getParent();
        if (parent != null) {
            Files.createDirectories(parent);
        }
        Path tmp = outputFile.resolveSibling(outputFile.getFileName().toString() + ".tmp");
        Files.writeString(tmp, contents);
        try {
            Files.move(tmp, outputFile, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException ex) {
            Files.move(tmp, outputFile, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    public static String render(SnapshotData data) {
        Objects.requireNonNull(data, "data");
        StringBuilder out = new StringBuilder(4096);

        out.append("=== Wormholes Stats ===\n");
        out.append("generated: ").append(ISO_INSTANT.format(data.generatedAt()));
        out.append("   uptime: ").append(formatUptime(data.uptime()));
        out.append("   plugin: v").append(data.pluginVersion() == null ? "?" : data.pluginVersion());
        out.append("   interval: ").append(data.intervalSec()).append("s");
        out.append("   server: ").append(safe(data.localName()));
        out.append('\n').append('\n');

        out.append("CONFIG\n");
        TransportSettings transport = data.transport();
        out.append("  compression: ")
            .append(transport.compressionEnabled() ? "enabled" : "disabled")
            .append(" (level ").append(transport.compressionLevel()).append(")")
            .append("        dictTrainBytes: ").append(formatBytes(transport.compressionDictTrainBytes())).append('\n');
        out.append("  uds:         ").append(transport.udsEnabled() ? "enabled" : "disabled")
            .append("                   udsDir: ").append(safe(data.udsDirDisplay())).append('\n');
        ViewSettings view = data.view();
        out.append("  view.cone:   ").append(formatDouble(view.coneDegrees(), 0)).append(" deg, behind ")
            .append(formatDouble(view.coneBehindFactor(), 2))
            .append("       view.yBias: cave<").append(view.yBiasCaveYMax())
            .append(" sky>").append(view.yBiasSkyYMin())
            .append(" factor ").append(formatDouble(view.yBiasFactor(), 2)).append('\n');
        out.append("  entity rate: near ").append(formatDouble(view.entityRateNearHz(), 0)).append("Hz")
            .append(" mid ").append(formatDouble(view.entityRateMidHz(), 0)).append("Hz")
            .append(" far ").append(formatDouble(view.entityRateFarHz(), 0)).append("Hz")
            .append(" veryFar ").append(formatDouble(view.entityRateVeryFarHz(), 0)).append("Hz").append('\n');
        out.append("  preship:     ").append(view.preshipEnabled() ? "enabled" : "disabled")
            .append(", distance ").append(formatDouble(view.preshipDistance(), 0))
            .append(", minSpeed ").append(formatDouble(view.preshipMinSpeed(), 2)).append('\n');
        out.append('\n');

        List<NetworkManager.PeerSnapshot> peers = data.peers() == null ? List.of() : new ArrayList<>(data.peers());
        peers.sort(Comparator.comparing(NetworkManager.PeerSnapshot::name));
        out.append("PEERS (").append(peers.size()).append(")\n");
        out.append("  name            transport   remote                                comp      dict          last seen   rtt\n");
        if (peers.isEmpty()) {
            out.append("  (no peers known)\n");
        } else {
            for (NetworkManager.PeerSnapshot peer : peers) {
                String statusTag = peer.disconnected() ? " [disconnected]" : "";
                out.append("  ")
                    .append(padRight(peer.name(), 14)).append("  ")
                    .append(padRight(peer.transport(), 10)).append("  ")
                    .append(padRight(truncate(peer.remoteAddress(), 36), 36)).append("  ")
                    .append(padRight(peer.compressionMode(), 8)).append("  ")
                    .append(padRight(formatDict(peer), 12)).append("  ")
                    .append(padRight(formatAge(peer.lastInboundAgeMillis()), 9)).append("   ")
                    .append(formatRtt(peer.rttMillis()))
                    .append(statusTag)
                    .append('\n');
            }
        }
        out.append('\n');

        CompressionState comp = data.compression();
        DictState dict = comp.dict();
        out.append("COMPRESSION\n");
        if (dict.dictPresent()) {
            out.append("  mode:        dict v").append(dict.version())
                .append(" (hash ").append(safe(dict.hashHex())).append(")")
                .append("   trained: ");
            if (dict.trainedAtMillis() > 0L) {
                out.append(ISO_INSTANT.format(Instant.ofEpochMilli(dict.trainedAtMillis())));
                out.append(" (from ").append(formatBytes(dict.dictBytes())).append(")");
            } else {
                out.append("unknown");
            }
            out.append('\n');
        } else {
            out.append("  mode:        no dictionary trained yet\n");
        }
        long corpusBudget = Math.max(1L, dict.corpusBudgetBytes());
        int corpusPct = (int) Math.min(100L, (dict.corpusFillBytes() * 100L) / corpusBudget);
        out.append("  next retrain in: ");
        if (dict.retrainEtaSec() <= 0L) {
            out.append("pending");
        } else {
            out.append(dict.retrainEtaSec()).append("s");
        }
        out.append("                       corpus: ").append(corpusPct).append("% full")
            .append(" (").append(formatBytes(dict.corpusFillBytes())).append(" / ")
            .append(formatBytes(dict.corpusBudgetBytes())).append(")")
            .append('\n');
        WireCompression.Stats wstats = comp.stats();
        out.append("  bytes raw->wire   in:  ").append(formatBytes(wstats.rawBytesIn())).append(" -> ")
            .append(formatBytes(wstats.wireBytesIn())).append("  (ratio ")
            .append(formatDouble(wstats.ratioIn(), 2)).append(")").append('\n');
        out.append("                    out: ").append(formatBytes(wstats.rawBytesOut())).append(" -> ")
            .append(formatBytes(wstats.wireBytesOut())).append("  (ratio ")
            .append(formatDouble(wstats.ratioOut(), 2)).append(")").append('\n');
        out.append("  frames by mode    none: ").append(wstats.noneCount())
            .append("   dictless: ").append(wstats.dictlessCount())
            .append("   dict: ").append(wstats.dictModeCount())
            .append('\n');
        out.append('\n');

        ViewMetrics viewMetrics = data.viewMetrics();
        ViewServer.Stats viewStats = viewMetrics.viewStats();
        EntityRateScheduler.Stats rateStats = viewMetrics.rateStats();
        out.append("VIEW STREAMING\n");
        out.append("  subscriptions: ").append(viewStats.subscriptions()).append(" active\n");
        out.append("  entities:      ").append(viewStats.trackedEntities()).append(" tracked   ");
        out.append("near ").append(rateStats.sendNear())
            .append("  mid ").append(rateStats.sendMid())
            .append("  far ").append(rateStats.sendFar())
            .append("  veryFar ").append(rateStats.sendVeryFar()).append('\n');
        double intervalSec = Math.max(0.001D, viewMetrics.intervalSeconds());
        out.append("  entity sends/s ")
            .append("near ").append(formatDouble(rateStats.sendNear() / intervalSec, 1))
            .append("  mid ").append(formatDouble(rateStats.sendMid() / intervalSec, 1))
            .append("  far ").append(formatDouble(rateStats.sendFar() / intervalSec, 1))
            .append("  veryFar ").append(formatDouble(rateStats.sendVeryFar() / intervalSec, 1))
            .append('\n');
        long totalPackets = viewStats.chunkBulkSentCount() + viewStats.chunkDiffSentCount();
        long diffPct = totalPackets > 0 ? (viewStats.chunkDiffSentCount() * 100L) / totalPackets : 0L;
        long bulkPct = totalPackets > 0 ? 100L - diffPct : 0L;
        out.append("  packets:       ").append(diffPct).append("% chunk diffs / ").append(bulkPct)
            .append("% chunk bulks (diffs=").append(viewStats.chunkDiffSentCount())
            .append(", bulks=").append(viewStats.chunkBulkSentCount()).append(")").append('\n');
        out.append("  cone bias:     ").append(data.view().coneEnabled() ? "enabled" : "disabled").append('\n');
        PreShipPredictor.Stats preship = viewMetrics.preship();
        out.append("  preship:       active ").append(preship.active())
            .append("   opened ").append(preship.opened())
            .append("   promoted ").append(preship.promoted())
            .append("   cancelled ").append(preship.cancelled())
            .append('\n');
        out.append('\n');

        TraversalService.Stats transfers = data.transfers();
        out.append("TRANSFERS\n");
        out.append("  completed: ").append(transfers.completed())
            .append("   in-flight: ").append(transfers.inFlight())
            .append("   failed: ").append(transfers.failed())
            .append('\n');
        out.append('\n');

        out.append("ERRORS (last 60s)\n");
        List<String> errors = data.recentErrors();
        if (errors == null || errors.isEmpty()) {
            out.append("  - (none)\n");
        } else {
            int limit = Math.min(errors.size(), 10);
            for (int i = 0; i < limit; i++) {
                out.append("  - ").append(errors.get(i)).append('\n');
            }
        }

        return out.toString();
    }

    private static SnapshotData buildRuntimeSnapshot(Wormholes plugin, NetworkManager network, ViewServer viewServer,
                                                     TraversalService traversal, PreShipPredictor preShipPredictor,
                                                     Duration interval, Instant pluginStartedAt) {
        NetworkConfig config = Wormholes.settings == null ? new NetworkConfig() : Wormholes.settings.getNetwork();
        Instant now = Instant.now();
        Duration uptime = Duration.between(pluginStartedAt, now);
        String pluginVersion = plugin.getPluginMeta() == null ? "?" : plugin.getPluginMeta().getVersion();
        String localName = network == null ? "?" : network.getLocalName();
        TransportSettings transportSettings = new TransportSettings(
            config.transport.compressionEnabled,
            config.transport.compressionLevel,
            config.transport.compressionDictTrainBytes,
            config.transport.udsEnabled,
            config.transport.udsDir == null ? "" : config.transport.udsDir,
            config.transport.compressionRetrainIntervalSec
        );
        ViewSettings viewSettings = new ViewSettings(
            config.view.coneDegrees,
            config.view.coneBehindFactor,
            config.view.coneEnabled,
            config.view.yBiasCaveYMax,
            config.view.yBiasSkyYMin,
            config.view.yBiasFactor,
            config.view.yBiasEnabled,
            config.view.entityRateNearHz,
            config.view.entityRateMidHz,
            config.view.entityRateFarHz,
            config.view.entityRateVeryFarHz,
            config.view.preshipEnabled,
            config.view.preshipDistance,
            config.view.preshipMinSpeed
        );
        String udsDirDisplay = (config.transport.udsDir == null || config.transport.udsDir.isBlank())
            ? "<plugin-data>/uds"
            : config.transport.udsDir;
        List<NetworkManager.PeerSnapshot> peers = network == null ? List.of() : network.peerSnapshots();
        WireCompression.Stats wireStats = network == null
            ? new WireCompression.Stats(0L, 0L, 0L, 0L, 0L, 0L, 0L)
            : network.wireCompressionMetrics().snapshot();
        CompressionDictionary dict = network == null ? null : network.currentDictionary();
        DictionarySampleCollector collector = network == null ? null : network.dictionarySampleCollector();
        long corpusFill = collector == null ? 0L : collector.accumulatedBytes();
        long corpusBudget = collector == null ? config.transport.compressionDictTrainBytes : collector.budgetBytes();
        long retrainEta = collector != null && collector.isFull() ? 0L : config.transport.compressionRetrainIntervalSec;
        DictState dictState = new DictState(
            dict != null,
            dict == null ? 0 : dict.version(),
            dict == null ? "-" : dict.hashHex8(),
            dict == null ? 0L : ((long) dict.version()) * 1000L,
            dict == null ? 0L : dict.bytes().length,
            corpusFill,
            corpusBudget,
            retrainEta
        );
        CompressionState compressionState = new CompressionState(wireStats, dictState);
        ViewServer.Stats viewStats = viewServer == null
            ? new ViewServer.Stats(0, 0, 0L, 0L, 0L, 0L)
            : viewServer.statsSnapshot();
        EntityRateScheduler scheduler = viewServer == null ? null : viewServer.getEntityRateScheduler();
        EntityRateScheduler.Stats rateStats = scheduler == null
            ? new EntityRateScheduler.Stats(0L, 0L, 0L, 0L)
            : scheduler.snapshot();
        EntityRateScheduler.Bands bands = scheduler == null ? null : scheduler.getBands();
        PreShipPredictor.Stats preshipStats = preShipPredictor == null
            ? new PreShipPredictor.Stats(0L, 0L, 0L, 0)
            : preShipPredictor.snapshot();
        double intervalSeconds = Math.max(1.0D, interval.getSeconds());
        ViewMetrics viewMetrics = new ViewMetrics(viewStats, rateStats, bands, preshipStats, intervalSeconds);
        TraversalService.Stats transferStats = traversal == null
            ? new TraversalService.Stats(0L, 0L, 0)
            : traversal.statsSnapshot();
        List<String> errors = List.of();
        return new SnapshotData(
            now,
            uptime,
            pluginVersion,
            (int) Math.max(1L, interval.getSeconds()),
            localName,
            transportSettings,
            viewSettings,
            udsDirDisplay,
            peers,
            compressionState,
            viewMetrics,
            transferStats,
            errors
        );
    }

    private static String formatUptime(Duration uptime) {
        long seconds = Math.max(0L, uptime.getSeconds());
        long hours = seconds / 3600L;
        long minutes = (seconds % 3600L) / 60L;
        long secs = seconds % 60L;
        if (hours > 0L) {
            return hours + "h" + minutes + "m";
        }
        if (minutes > 0L) {
            return minutes + "m" + secs + "s";
        }
        return secs + "s";
    }

    private static String formatBytes(long bytes) {
        if (bytes < 1024L) {
            return bytes + " B";
        }
        double kb = bytes / 1024.0D;
        if (kb < 1024.0D) {
            return formatDouble(kb, 1) + " KB";
        }
        double mb = kb / 1024.0D;
        if (mb < 1024.0D) {
            return formatDouble(mb, 1) + " MB";
        }
        double gb = mb / 1024.0D;
        return formatDouble(gb, 1) + " GB";
    }

    private static String formatDouble(double value, int decimals) {
        if (decimals <= 0) {
            return Long.toString(Math.round(value));
        }
        double factor = Math.pow(10.0D, decimals);
        double rounded = Math.round(value * factor) / factor;
        return String.format("%." + decimals + "f", rounded);
    }

    private static String formatAge(long millis) {
        if (millis < 0L) {
            return "-";
        }
        if (millis < 1000L) {
            return millis + "ms";
        }
        double seconds = millis / 1000.0D;
        if (seconds < 60.0D) {
            return formatDouble(seconds, 1) + "s";
        }
        long minutes = (long) (seconds / 60.0D);
        return minutes + "m";
    }

    private static String formatRtt(long millis) {
        if (millis < 0L) {
            return "-";
        }
        return millis + "ms";
    }

    private static String formatDict(NetworkManager.PeerSnapshot peer) {
        if ("pending".equals(peer.compressionMode())) {
            return "-";
        }
        if (peer.dictVersion() <= 0) {
            return "-";
        }
        return "v" + peer.dictVersion() + " " + peer.dictHashHex();
    }

    private static String padRight(String value, int width) {
        String safe = value == null ? "" : value;
        if (safe.length() >= width) {
            return safe;
        }
        StringBuilder builder = new StringBuilder(width);
        builder.append(safe);
        for (int i = safe.length(); i < width; i++) {
            builder.append(' ');
        }
        return builder.toString();
    }

    private static String truncate(String value, int max) {
        if (value == null) {
            return "";
        }
        if (value.length() <= max) {
            return value;
        }
        return value.substring(0, Math.max(0, max - 1)) + "+";
    }

    private static String safe(String value) {
        return value == null ? "" : value;
    }
}
