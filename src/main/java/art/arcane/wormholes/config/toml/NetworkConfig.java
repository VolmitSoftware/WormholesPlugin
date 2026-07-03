package art.arcane.wormholes.config.toml;

import art.arcane.wormholes.util.project.config.ConfigDescription;
import art.arcane.wormholes.util.project.config.ConfigDoc;

@ConfigDoc({
    "Wormholes cross-server networking. Zero-config: the external host auto-detects via HTTPS and self-corrects over the",
    "signed handshake, peer addresses learn from portal codes, and the only setting worth touching is the raw Wormholes",
    "listen-port -- forward it on both servers to upgrade from the game-port fallback to full-throughput projection streaming."
})
public class NetworkConfig {
    @ConfigDescription("Master switch for cross-server networking.")
    public boolean enabled = false;

    @ConfigDescription("Open an inbound raw Wormholes socket. Disable for outbound-only servers that always dial peers.")
    public boolean listenEnabled = true;

    @ConfigDescription("Preferred raw Wormholes port. Forward this on both servers for high-throughput projection streaming. If in use, Wormholes scans the next 50 ports.")
    public int listenPort = 8901;

    @ConfigDescription("Auto-trust a first inbound peer's public key. Disable to require pre-imported portal codes.")
    public boolean trustOnFirstUse = true;

    @ConfigDescription("Comma-separated entity types this server refuses to accept across portals (players never blocked).")
    public String entityTransferDenyTypes = "";

    @ConfigDescription({
        "Manual override for the externally reachable host. Blank = auto-detect via HTTPS and self-correct over the handshake.",
        "Set this only when auto-detection picks the wrong address (NAT/container hosts, or 127.0.0.1 for a same-machine test)."
    })
    public String advertiseHostOverride = "";

    public transient String serverName = "";
    public transient String transferMode = "auto";
    public transient long handoffTimeoutMs = 5000L;
    public transient boolean optimisticHandoff = true;
    public transient boolean autoAcceptTransfers = true;

    public transient TransportConfig transport = new TransportConfig();
    public transient ViewConfig view = new ViewConfig();
    public transient StatsConfig stats = new StatsConfig();
    public transient ReplicationConfig replication = new ReplicationConfig();

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
