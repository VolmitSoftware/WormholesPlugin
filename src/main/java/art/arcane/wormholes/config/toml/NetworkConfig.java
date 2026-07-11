package art.arcane.wormholes.config.toml;

import art.arcane.wormholes.util.project.config.ConfigAdvanced;
import art.arcane.wormholes.util.project.config.ConfigDescription;
import art.arcane.wormholes.util.project.config.ConfigDoc;

@ConfigDoc({
    "Cross-server networking. Portal codes discover peers automatically."
})
public class NetworkConfig {
    @ConfigDescription("Enable cross-server portals.")
    public boolean enabled = false;

    @ConfigAdvanced
    public boolean listenEnabled = true;

    @ConfigDescription("Optional raw-stream port. Without forwarding, Wormholes uses the game-port sideband.")
    public int listenPort = 8901;

    @ConfigAdvanced
    public boolean trustOnFirstUse = true;

    @ConfigAdvanced
    public String entityTransferDenyTypes = "";

    @ConfigAdvanced
    public String advertiseHostOverride = "";

    @ConfigAdvanced
    public String serverName = "";
    @ConfigAdvanced
    public String transferMode = "auto";
    @ConfigAdvanced
    public long handoffTimeoutMs = 5000L;
    @ConfigAdvanced
    public boolean autoAcceptTransfers = true;

    @ConfigAdvanced
    public TransportConfig transport = new TransportConfig();
    @ConfigAdvanced
    public ViewConfig view = new ViewConfig();
    @ConfigAdvanced
    public StatsConfig stats = new StatsConfig();
    @ConfigAdvanced
    public ReplicationConfig replication = new ReplicationConfig();

    public static class ViewConfig {
        @ConfigAdvanced
        public boolean entityDeltaEnabled = true;
        @ConfigAdvanced
        public double entityRateNearRange = 16.0D;
        @ConfigAdvanced
        public double entityRateMidRange = 64.0D;
        @ConfigAdvanced
        public double entityRateFarRange = 128.0D;
        @ConfigAdvanced
        public double entityRateNearHz = 20.0D;
        @ConfigAdvanced
        public double entityRateMidHz = 10.0D;
        @ConfigAdvanced
        public double entityRateFarHz = 4.0D;
        @ConfigAdvanced
        public double entityRateVeryFarHz = 1.0D;
        @ConfigAdvanced
        public boolean coneEnabled = true;
        @ConfigAdvanced
        public double coneDegrees = 60.0D;
        @ConfigAdvanced
        public double coneBehindFactor = 0.4D;
        @ConfigAdvanced
        public boolean yBiasEnabled = true;
        @ConfigAdvanced
        public int yBiasCaveYMax = 50;
        @ConfigAdvanced
        public int yBiasSkyYMin = 200;
        @ConfigAdvanced
        public double yBiasFactor = 0.5D;
        @ConfigAdvanced
        public boolean preshipEnabled = true;
        @ConfigAdvanced
        public double preshipDistance = 24.0D;
        @ConfigAdvanced
        public double preshipMinSpeed = 0.1D;
        @ConfigAdvanced
        public double preshipRateFraction = 0.25D;
        @ConfigAdvanced
        public double preshipCancelGraceSeconds = 2.0D;
    }

    public static class TransportConfig {
        @ConfigAdvanced
        public boolean compressionEnabled = true;
        @ConfigAdvanced
        public int compressionLevel = 3;
        @ConfigAdvanced
        public int compressionDictTrainBytes = 10_485_760;
        @ConfigAdvanced
        public int compressionDictTargetSize = 65_536;
        @ConfigAdvanced
        public int compressionRetrainIntervalSec = 600;
        @ConfigAdvanced
        public boolean udsEnabled = true;
        @ConfigAdvanced
        public String udsDir = "";
    }

    public static class ReplicationConfig {
        @ConfigAdvanced
        public int hashProbeIntervalSec = 30;
        @ConfigAdvanced
        public int hashProbeChunksPerTick = 16;
        @ConfigAdvanced
        public int diffWindowSize = 32;
        @ConfigAdvanced
        public int resyncTimeoutSec = 5;
        @ConfigAdvanced
        public int maxQueuedDiffsPerPeer = 4096;
        @ConfigAdvanced
        public int captureSnapshotIntervalTicks = 100;
        @ConfigAdvanced
        public int captureMaxQueuedDiffsPerChunk = 256;
        @ConfigAdvanced
        public boolean captureLightEnabled = true;
        @ConfigAdvanced
        public boolean captureBlockEntityEnabled = true;
    }

    public static class StatsConfig {
        @ConfigAdvanced
        public boolean enabled = true;
        @ConfigAdvanced
        public int intervalSec = 10;
        @ConfigAdvanced
        public String pathOverride = "";
    }

    public static class PeerEntry {
        public String name = "";
        public String host = "";
        public String fallbackHosts = "";
        public int port = 8901;
        public String publicHost = "";
        public int publicPort = 25565;
        public boolean useProxy = false;
    }
}
