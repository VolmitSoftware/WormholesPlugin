package art.arcane.wormholes.config.toml;

import art.arcane.wormholes.util.project.config.ConfigDescription;
import art.arcane.wormholes.util.project.config.ConfigDoc;

@ConfigDoc({
    "Cross-server networking. Portal codes discover peers automatically."
})
public class NetworkConfig {
    @ConfigDescription("Enable cross-server portals.")
    public boolean enabled = false;
    public boolean listenEnabled = true;

    @ConfigDescription("Optional raw-stream port. Without forwarding, Wormholes uses the game-port sideband.")
    public int listenPort = 8901;
    public boolean trustOnFirstUse = true;
    public String entityTransferDenyTypes = "";
    public String advertiseHostOverride = "";
    public String serverName = "";
    public String transferMode = "auto";
    public long handoffTimeoutMs = 5000L;
    public boolean autoAcceptTransfers = true;
    public TransportConfig transport = new TransportConfig();
    public ViewConfig view = new ViewConfig();
    public StatsConfig stats = new StatsConfig();
    public ReplicationConfig replication = new ReplicationConfig();

    public static class ViewConfig {
        public boolean entityDeltaEnabled = true;
        public double entityRateNearRange = 16.0D;
        public double entityRateMidRange = 64.0D;
        public double entityRateFarRange = 128.0D;
        public double entityRateNearHz = 20.0D;
        public double entityRateMidHz = 10.0D;
        public double entityRateFarHz = 4.0D;
        public double entityRateVeryFarHz = 1.0D;
        public boolean coneEnabled = true;
        public double coneDegrees = 60.0D;
        public double coneBehindFactor = 0.4D;
        public boolean yBiasEnabled = true;
        public int yBiasCaveYMax = 50;
        public int yBiasSkyYMin = 200;
        public double yBiasFactor = 0.5D;
        public boolean preshipEnabled = true;
        public double preshipDistance = 24.0D;
        public double preshipMinSpeed = 0.1D;
        public double preshipRateFraction = 0.25D;
        public double preshipCancelGraceSeconds = 2.0D;
    }

    public static class TransportConfig {
        public boolean compressionEnabled = true;
        public int compressionLevel = 3;
        public int compressionDictTrainBytes = 10_485_760;
        public int compressionDictTargetSize = 65_536;
        public int compressionRetrainIntervalSec = 600;
        public boolean udsEnabled = true;
        public String udsDir = "";
    }

    public static class ReplicationConfig {
        public int hashProbeIntervalSec = 30;
        public int hashProbeChunksPerTick = 16;
        public int diffWindowSize = 32;
        public int resyncTimeoutSec = 5;
        public int maxQueuedDiffsPerPeer = 4096;
        public int captureSnapshotIntervalTicks = 100;
        public int captureMaxQueuedDiffsPerChunk = 256;
        public boolean captureLightEnabled = true;
        public boolean captureBlockEntityEnabled = true;
    }

    public static class StatsConfig {
        public boolean enabled = true;
        public int intervalSec = 10;
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
