package art.arcane.wormholes.config.toml;

import art.arcane.wormholes.util.project.config.ConfigDescription;
import art.arcane.wormholes.util.project.config.ConfigDoc;

@ConfigDoc({
    "Wormholes cross-server networking. Zero-config for the common case: the external host auto-detects via HTTPS,",
    "the listen-port auto-falls back across a small range, and peer addresses learn from the handshake."
})
public class NetworkConfig {
    @ConfigDescription("Master switch for cross-server networking.")
    public boolean enabled = false;

    @ConfigDescription("Stable logical name (e.g. hub, survival). Blank auto-derives a wh-... name from this server's public key.")
    public String serverName = "";

    @ConfigDescription("Open an inbound raw Wormholes socket. Disable for outbound-only servers that always dial peers.")
    public boolean listenEnabled = true;

    @ConfigDescription("Preferred raw Wormholes port. If in use, Wormholes scans the next 50 ports and binds the first free one.")
    public int listenPort = 8901;

    @ConfigDescription("Manual override for the externally reachable host. Blank = auto-detect via HTTPS.")
    public String advertiseHostOverride = "";

    @ConfigDescription("Auto-trust a first inbound peer's public key. Disable to require pre-imported portal codes.")
    public boolean trustOnFirstUse = true;

    @ConfigDescription("Player transfer mode: auto, packet, or proxy. auto works for direct two-server setups.")
    public String transferMode = "auto";

    @ConfigDescription("Milliseconds to wait for the destination's handoff ack before aborting.")
    public long handoffTimeoutMs = 5000L;

    @ConfigDescription("Send the player transfer immediately without waiting for the destination ack. Faster traversal, denied transfers land at spawn.")
    public boolean optimisticHandoff = true;

    @ConfigDescription("Accept inbound transfers even when server.properties accepts-transfers=false.")
    public boolean autoAcceptTransfers = true;

    @ConfigDescription("Comma-separated entity types this server refuses to accept across portals (players never blocked).")
    public String entityTransferDenyTypes = "";

    @ConfigDescription({
        "Transport-layer optimizations applied to peer connections.",
        "Covers wire-frame compression and Unix Domain Socket fast-path for same-host peers."
    })
    public TransportConfig transport = new TransportConfig();

    @ConfigDescription({
        "Cross-server view streaming tuning.",
        "Controls entity delta encoding, per-distance update rates, cone/Y-axis slice priority, and predictive pre-ship before portal traversal."
    })
    public ViewConfig view = new ViewConfig();

    @ConfigDescription({
        "Self-refreshing stats snapshot file.",
        "Overwrites <plugin-data>/wormholes-stats.txt every interval-sec with a single bounded page (one snapshot, ~100 lines)",
        "so operators can copy the latest live network state to Claude or to a bug report without tailing rolling logs."
    })
    public StatsConfig stats = new StatsConfig();

    @ConfigDescription({
        "Cross-server chunk replication tuning.",
        "Initial bulk + diff stream + periodic hash probes. Steady-state bandwidth approaches zero for static regions."
    })
    public ReplicationConfig replication = new ReplicationConfig();

    @ConfigDoc({
        "View-streaming tuning shared across all subscribed peers."
    })
    public static class ViewConfig {
        @ConfigDescription("Enable per-entity delta encoding (false sends full snapshots every tick).")
        public boolean entityDeltaEnabled = true;

        @ConfigDescription("Consecutive deltas before a forced full resync of an entity for a subscriber.")
        public int entityDeltaMissesBeforeResync = 5;

        @ConfigDescription("Minimum speed (blocks/tick) above which velocity is included in a delta.")
        public double entityDeltaVelocityThreshold = 0.02D;

        @ConfigDescription("Inner distance band (blocks) for entity updates.")
        public double entityRateNearRange = 16.0D;

        @ConfigDescription("Middle distance band (blocks) for entity updates.")
        public double entityRateMidRange = 64.0D;

        @ConfigDescription("Outer distance band (blocks) for entity updates.")
        public double entityRateFarRange = 128.0D;

        @ConfigDescription("Update frequency in Hz for entities inside the near band.")
        public double entityRateNearHz = 20.0D;

        @ConfigDescription("Update frequency in Hz for entities inside the mid band.")
        public double entityRateMidHz = 10.0D;

        @ConfigDescription("Update frequency in Hz for entities inside the far band.")
        public double entityRateFarHz = 4.0D;

        @ConfigDescription("Update frequency in Hz for entities outside the far band.")
        public double entityRateVeryFarHz = 1.0D;

        @ConfigDescription("Enable cone-affinity slice priority ordering.")
        public boolean coneEnabled = true;

        @ConfigDescription("Total cone angle in degrees treated as the subscriber's forward view.")
        public double coneDegrees = 60.0D;

        @ConfigDescription("Multiplier applied to slice priority when the slice center is outside the forward cone.")
        public double coneBehindFactor = 0.4D;

        @ConfigDescription("Enable Y-axis slice priority bias (deprioritizes high slices for cave subscribers and low slices for sky subscribers).")
        public boolean yBiasEnabled = true;

        @ConfigDescription("Subscriber Y below this is treated as cave for Y-bias priority scaling.")
        public int yBiasCaveYMax = 50;

        @ConfigDescription("Subscriber Y above this is treated as sky for Y-bias priority scaling.")
        public int yBiasSkyYMin = 200;

        @ConfigDescription("Multiplier applied to deprioritized slices under Y-bias.")
        public double yBiasFactor = 0.5D;

        @ConfigDescription("Enable predictive pre-ship subscription before portal traversal.")
        public boolean preshipEnabled = true;

        @ConfigDescription("Distance in blocks within which a player is considered approaching a gateway portal.")
        public double preshipDistance = 24.0D;

        @ConfigDescription("Minimum velocity component along the portal normal that triggers a pre-ship subscription.")
        public double preshipMinSpeed = 0.1D;

        @ConfigDescription("Fraction of full subscription rate used for the preliminary pre-ship subscription (0..1).")
        public double preshipRateFraction = 0.25D;

        @ConfigDescription("Seconds the player must keep velocity flipped away from the portal before the pre-ship subscription is canceled.")
        public double preshipCancelGraceSeconds = 2.0D;
    }

    @ConfigDoc({
        "Transport optimizations: Zstd compression with optional trained dictionary, plus Unix Domain Socket fast-path for same-host peers."
    })
    public static class TransportConfig {
        @ConfigDescription("Enable Zstd compression on peer-to-peer frames.")
        public boolean compressionEnabled = true;

        @ConfigDescription("Zstd level (1..22). Higher = smaller frames, more CPU. 3 is a good throughput/ratio balance.")
        public int compressionLevel = 3;

        @ConfigDescription("Bytes of recent payload retained for dictionary training (default 10 MiB).")
        public int compressionDictTrainBytes = 10_485_760;

        @ConfigDescription("Target dictionary size in bytes (default 64 KiB).")
        public int compressionDictTargetSize = 65_536;

        @ConfigDescription("Seconds between dictionary retrain attempts when the sample budget fills.")
        public int compressionRetrainIntervalSec = 600;

        @ConfigDescription({
            "Enable Unix Domain Socket fast-path for peers whose resolved address is loopback.",
            "TCP is still attempted as a fallback if UDS connect fails."
        })
        public boolean udsEnabled = true;

        @ConfigDescription({
            "Directory holding UDS socket files. Empty = use <plugin-data>/uds.",
            "Both ends must agree on this directory when they discover each other on the same host."
        })
        public String udsDir = "";
    }

    @ConfigDoc({
        "Cross-server chunk replication settings.",
        "Each peer receives an initial bulk copy of a chunk once, then only diffs of subsequent block/light/block-entity changes.",
        "Periodic hash probes detect divergence and trigger resync."
    })
    public static class ReplicationConfig {
        @ConfigDescription("Seconds between hash probe sweeps (minimum 1).")
        public int hashProbeIntervalSec = 30;

        @ConfigDescription("Maximum chunk hashes sent per peer per probe.")
        public int hashProbeChunksPerTick = 16;

        @ConfigDescription("Maximum out-of-order diff batches buffered per chunk before resync is forced.")
        public int diffWindowSize = 32;

        @ConfigDescription("Seconds before an unfilled diff gap triggers a chunk resync request.")
        public int resyncTimeoutSec = 5;

        @ConfigDescription("Maximum queued diff entries per peer-chunk before new diffs are dropped (resync recovers).")
        public int maxQueuedDiffsPerPeer = 4096;

        @ConfigDescription("Ticks between source-side snapshot-diff sweeps that catch programmatic world.setBlockData calls (minimum 20).")
        public int captureSnapshotIntervalTicks = 100;

        @ConfigDescription("Soft cap on accumulated source-side diffs per chunk per tick; over this, new block changes are dropped and the hash probe drives resync.")
        public int captureMaxQueuedDiffsPerChunk = 256;

        @ConfigDescription("Enable source-side per-section block-light / sky-light capture on chunks that had block changes this tick.")
        public boolean captureLightEnabled = true;

        @ConfigDescription("Enable source-side block-entity NBT capture (signs, lecterns, containers, dispensers, comparators).")
        public boolean captureBlockEntityEnabled = true;
    }

    @ConfigDoc({
        "Self-refreshing stats snapshot file used for one-paste operator diagnostics."
    })
    public static class StatsConfig {
        @ConfigDescription("Enable the rotating wormholes-stats.txt snapshot file.")
        public boolean enabled = true;

        @ConfigDescription("Interval in seconds between snapshot rewrites. Minimum 1.")
        public int intervalSec = 10;

        @ConfigDescription({
            "Override path for the snapshot file. Leave blank to use <plugin-data>/wormholes-stats.txt.",
            "Relative paths resolve against the server working directory."
        })
        public String pathOverride = "";
    }

    public static class PeerEntry {
        @ConfigDescription("The peer's server-name (must match its own network.toml server-name).")
        public String name = "";

        @ConfigDescription("Address this server uses to reach the peer's wormhole listen socket.")
        public String host = "";

        @ConfigDescription({
            "Optional comma-separated alternate addresses tried in rotation when host is unreachable",
            "(portal-code imports store these in the internal route store instead)."
        })
        public String fallbackHosts = "";

        @ConfigDescription("The peer's wormhole listen port.")
        public int port = 8901;

        @ConfigDescription("Address PLAYERS use to join the peer (for the transfer packet).")
        public String publicHost = "";

        @ConfigDescription("Port PLAYERS use to join the peer (for the transfer packet).")
        public int publicPort = 25565;

        @ConfigDescription("Use the proxy Connect channel instead of the native transfer packet when both servers share a proxy.")
        public boolean useProxy = false;
    }
}
