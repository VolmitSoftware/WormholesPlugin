package art.arcane.wormholes.service;

import art.arcane.volmlib.integration.IntegrationHandshakeRequest;
import art.arcane.volmlib.integration.IntegrationHandshakeResponse;
import art.arcane.volmlib.integration.IntegrationHeartbeat;
import art.arcane.volmlib.integration.IntegrationMetricDescriptor;
import art.arcane.volmlib.integration.IntegrationMetricSample;
import art.arcane.volmlib.integration.IntegrationMetricSchema;
import art.arcane.volmlib.integration.IntegrationProtocolNegotiator;
import art.arcane.volmlib.integration.IntegrationProtocolVersion;
import art.arcane.volmlib.integration.IntegrationServiceContract;
import art.arcane.wormholes.Wormholes;
import art.arcane.wormholes.network.NetworkManager;
import art.arcane.wormholes.network.RemotePortalRegistry;
import art.arcane.wormholes.network.TraversalService;
import art.arcane.wormholes.network.WireCompression;
import art.arcane.wormholes.network.replication.ChunkReplicationManager;
import art.arcane.wormholes.network.view.ViewServer;
import org.bukkit.Bukkit;
import org.bukkit.plugin.ServicePriority;

import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

public final class WormholesIntegrationService implements IntegrationServiceContract {
    private static final Set<IntegrationProtocolVersion> SUPPORTED_PROTOCOLS = Set.of(
        new IntegrationProtocolVersion(1, 0),
        new IntegrationProtocolVersion(1, 1)
    );
    private static final Set<String> CAPABILITIES = Set.of(
        "handshake",
        "heartbeat",
        "metrics",
        "wormholes-projection-metrics"
    );

    private volatile IntegrationProtocolVersion negotiatedProtocol = new IntegrationProtocolVersion(1, 1);
    private final RateWindow wireBytesOutWindow = new RateWindow();
    private final RateWindow wireBytesInWindow = new RateWindow();
    private final RateWindow sidebandDropsWindow = new RateWindow();
    private final RateWindow replicatedBlocksWindow = new RateWindow();

    public void register() {
        Bukkit.getServicesManager().register(IntegrationServiceContract.class, this, Wormholes.instance, ServicePriority.Normal);
        Wormholes.v("Integration provider registered for Wormholes");
    }

    public void unregister() {
        Bukkit.getServicesManager().unregister(IntegrationServiceContract.class, this);
        WormholesTelemetry.clear();
        wireBytesOutWindow.clear();
        wireBytesInWindow.clear();
        sidebandDropsWindow.clear();
        replicatedBlocksWindow.clear();
    }

    @Override
    public String pluginId() {
        return "wormholes";
    }

    @Override
    public String pluginVersion() {
        return Wormholes.instance.getDescription().getVersion();
    }

    @Override
    public Set<IntegrationProtocolVersion> supportedProtocols() {
        return SUPPORTED_PROTOCOLS;
    }

    @Override
    public Set<String> capabilities() {
        return CAPABILITIES;
    }

    @Override
    public Set<IntegrationMetricDescriptor> metricDescriptors() {
        return IntegrationMetricSchema.descriptors().stream()
            .filter(descriptor -> descriptor.key().startsWith("wormholes."))
            .collect(Collectors.toSet());
    }

    @Override
    public IntegrationHandshakeResponse handshake(IntegrationHandshakeRequest request) {
        long now = System.currentTimeMillis();
        if (request == null) {
            return new IntegrationHandshakeResponse(
                pluginId(), pluginVersion(), false, null,
                SUPPORTED_PROTOCOLS, CAPABILITIES, "missing request", now
            );
        }

        Optional<IntegrationProtocolVersion> negotiated = IntegrationProtocolNegotiator.negotiate(
            SUPPORTED_PROTOCOLS,
            request.supportedProtocols()
        );
        if (negotiated.isEmpty()) {
            return new IntegrationHandshakeResponse(
                pluginId(), pluginVersion(), false, null,
                SUPPORTED_PROTOCOLS, CAPABILITIES, "no-common-protocol", now
            );
        }

        negotiatedProtocol = negotiated.get();
        return new IntegrationHandshakeResponse(
            pluginId(), pluginVersion(), true, negotiatedProtocol,
            SUPPORTED_PROTOCOLS, CAPABILITIES, "ok", now
        );
    }

    @Override
    public IntegrationHeartbeat heartbeat() {
        return new IntegrationHeartbeat(negotiatedProtocol, true, System.currentTimeMillis(), "ok");
    }

    @Override
    public Map<String, IntegrationMetricSample> sampleMetrics(Set<String> metricKeys) {
        Set<String> requested = metricKeys == null || metricKeys.isEmpty()
            ? IntegrationMetricSchema.wormholesKeys()
            : metricKeys;
        long now = System.currentTimeMillis();
        Map<String, IntegrationMetricSample> out = new HashMap<>();

        for (String key : requested) {
            switch (key) {
                case IntegrationMetricSchema.WORMHOLES_PORTALS ->
                    out.put(key, samplePortals(now));
                case IntegrationMetricSchema.WORMHOLES_PROJECTIONS_ACTIVE ->
                    out.put(key, available(key, WormholesTelemetry.activeProjections(), now));
                case IntegrationMetricSchema.WORMHOLES_PROJECTION_OBSERVERS ->
                    out.put(key, available(key, WormholesTelemetry.projectionObservers(), now));
                case IntegrationMetricSchema.WORMHOLES_PROJECTION_RENDER_MS ->
                    out.put(key, available(key, WormholesTelemetry.renderMsPerSecond(now), now));
                case IntegrationMetricSchema.WORMHOLES_BLOCK_CHANGES_PER_SECOND ->
                    out.put(key, available(key, WormholesTelemetry.blockChangesPerSecond(now), now));
                case IntegrationMetricSchema.WORMHOLES_PACKETS_PER_SECOND ->
                    out.put(key, available(key, WormholesTelemetry.packetsPerSecond(now), now));
                case IntegrationMetricSchema.WORMHOLES_SPOOFED_ENTITIES ->
                    out.put(key, available(key, WormholesTelemetry.spoofedEntities(), now));
                case IntegrationMetricSchema.WORMHOLES_TRAVERSALS_PER_MINUTE ->
                    out.put(key, available(key, WormholesTelemetry.traversalsPerMinute(now), now));
                case IntegrationMetricSchema.WORMHOLES_PEERS_CONNECTED ->
                    out.put(key, samplePeersConnected(now));
                case IntegrationMetricSchema.WORMHOLES_REMOTE_PORTALS ->
                    out.put(key, sampleRemotePortals(now));
                case IntegrationMetricSchema.WORMHOLES_PEER_RTT_MAX_MS ->
                    out.put(key, samplePeerRttMax(now));
                case IntegrationMetricSchema.WORMHOLES_WIRE_BYTES_OUT_PER_SECOND ->
                    out.put(key, sampleWireBytesOutPerSecond(now));
                case IntegrationMetricSchema.WORMHOLES_WIRE_BYTES_IN_PER_SECOND ->
                    out.put(key, sampleWireBytesInPerSecond(now));
                case IntegrationMetricSchema.WORMHOLES_COMPRESSION_RATIO_OUT ->
                    out.put(key, sampleCompressionRatioOut(now));
                case IntegrationMetricSchema.WORMHOLES_SIDEBAND_QUEUED_BYTES ->
                    out.put(key, sampleSidebandQueuedBytes(now));
                case IntegrationMetricSchema.WORMHOLES_SIDEBAND_DROPS_PER_SECOND ->
                    out.put(key, sampleSidebandDropsPerSecond(now));
                case IntegrationMetricSchema.WORMHOLES_VIEW_SUBSCRIPTIONS ->
                    out.put(key, sampleViewSubscriptions(now));
                case IntegrationMetricSchema.WORMHOLES_VIEW_TRACKED_ENTITIES ->
                    out.put(key, sampleViewTrackedEntities(now));
                case IntegrationMetricSchema.WORMHOLES_REPLICATED_BLOCKS_PER_SECOND ->
                    out.put(key, sampleReplicatedBlocksPerSecond(now));
                case IntegrationMetricSchema.WORMHOLES_RESYNC_REQUESTS_TOTAL ->
                    out.put(key, sampleResyncRequestsTotal(now));
                case IntegrationMetricSchema.WORMHOLES_TRANSFERS_IN_FLIGHT ->
                    out.put(key, sampleTransfersInFlight(now));
                case IntegrationMetricSchema.WORMHOLES_TRANSFERS_FAILED_TOTAL ->
                    out.put(key, sampleTransfersFailedTotal(now));
                default -> out.put(key, IntegrationMetricSample.unavailable(
                    IntegrationMetricSchema.descriptor(key),
                    "unsupported-key",
                    now
                ));
            }
        }

        return out;
    }

    private IntegrationMetricSample samplePortals(long now) {
        IntegrationMetricDescriptor descriptor = IntegrationMetricSchema.descriptor(IntegrationMetricSchema.WORMHOLES_PORTALS);
        if (Wormholes.portalManager == null) {
            return IntegrationMetricSample.unavailable(descriptor, "portal-manager-not-ready", now);
        }

        return IntegrationMetricSample.available(descriptor, Wormholes.portalManager.getLocalPortals().size(), now);
    }

    private IntegrationMetricSample samplePeersConnected(long now) {
        IntegrationMetricDescriptor descriptor = IntegrationMetricSchema.descriptor(IntegrationMetricSchema.WORMHOLES_PEERS_CONNECTED);
        NetworkManager network = networkManager();
        if (network == null) {
            return IntegrationMetricSample.unavailable(descriptor, "network-manager-not-ready", now);
        }

        int connected = 0;
        for (NetworkManager.PeerSnapshot peer : network.peerSnapshots()) {
            if (peer.handshakeComplete() && !peer.disconnected()) {
                connected++;
            }
        }
        return IntegrationMetricSample.available(descriptor, connected, now);
    }

    private IntegrationMetricSample sampleRemotePortals(long now) {
        IntegrationMetricDescriptor descriptor = IntegrationMetricSchema.descriptor(IntegrationMetricSchema.WORMHOLES_REMOTE_PORTALS);
        RemotePortalRegistry registry = Wormholes.remotePortalRegistry;
        if (registry == null) {
            return IntegrationMetricSample.unavailable(descriptor, "remote-portal-registry-not-ready", now);
        }

        return IntegrationMetricSample.available(descriptor, registry.all().size(), now);
    }

    private IntegrationMetricSample samplePeerRttMax(long now) {
        IntegrationMetricDescriptor descriptor = IntegrationMetricSchema.descriptor(IntegrationMetricSchema.WORMHOLES_PEER_RTT_MAX_MS);
        NetworkManager network = networkManager();
        if (network == null) {
            return IntegrationMetricSample.unavailable(descriptor, "network-manager-not-ready", now);
        }

        long maxRtt = 0L;
        for (NetworkManager.PeerSnapshot peer : network.peerSnapshots()) {
            if (peer.handshakeComplete() && !peer.disconnected()) {
                maxRtt = Math.max(maxRtt, peer.rttMillis());
            }
        }
        return IntegrationMetricSample.available(descriptor, maxRtt, now);
    }

    private IntegrationMetricSample sampleWireBytesOutPerSecond(long now) {
        IntegrationMetricDescriptor descriptor = IntegrationMetricSchema.descriptor(IntegrationMetricSchema.WORMHOLES_WIRE_BYTES_OUT_PER_SECOND);
        NetworkManager network = networkManager();
        if (network == null) {
            return IntegrationMetricSample.unavailable(descriptor, "network-manager-not-ready", now);
        }

        WireCompression.Stats stats = network.wireCompressionMetrics().snapshot();
        return IntegrationMetricSample.available(descriptor, wireBytesOutWindow.perSecond(stats.wireBytesOut(), now), now);
    }

    private IntegrationMetricSample sampleWireBytesInPerSecond(long now) {
        IntegrationMetricDescriptor descriptor = IntegrationMetricSchema.descriptor(IntegrationMetricSchema.WORMHOLES_WIRE_BYTES_IN_PER_SECOND);
        NetworkManager network = networkManager();
        if (network == null) {
            return IntegrationMetricSample.unavailable(descriptor, "network-manager-not-ready", now);
        }

        WireCompression.Stats stats = network.wireCompressionMetrics().snapshot();
        return IntegrationMetricSample.available(descriptor, wireBytesInWindow.perSecond(stats.wireBytesIn(), now), now);
    }

    private IntegrationMetricSample sampleCompressionRatioOut(long now) {
        IntegrationMetricDescriptor descriptor = IntegrationMetricSchema.descriptor(IntegrationMetricSchema.WORMHOLES_COMPRESSION_RATIO_OUT);
        NetworkManager network = networkManager();
        if (network == null) {
            return IntegrationMetricSample.unavailable(descriptor, "network-manager-not-ready", now);
        }

        double ratio = network.wireCompressionMetrics().snapshot().ratioOut();
        if (!Double.isFinite(ratio)) {
            ratio = 0.0D;
        }
        return IntegrationMetricSample.available(descriptor, ratio, now);
    }

    private IntegrationMetricSample sampleSidebandQueuedBytes(long now) {
        IntegrationMetricDescriptor descriptor = IntegrationMetricSchema.descriptor(IntegrationMetricSchema.WORMHOLES_SIDEBAND_QUEUED_BYTES);
        NetworkManager network = networkManager();
        if (network == null) {
            return IntegrationMetricSample.unavailable(descriptor, "network-manager-not-ready", now);
        }

        return IntegrationMetricSample.available(descriptor, network.debugSnapshot().sidebandQueuedBytes(), now);
    }

    private IntegrationMetricSample sampleSidebandDropsPerSecond(long now) {
        IntegrationMetricDescriptor descriptor = IntegrationMetricSchema.descriptor(IntegrationMetricSchema.WORMHOLES_SIDEBAND_DROPS_PER_SECOND);
        NetworkManager network = networkManager();
        if (network == null) {
            return IntegrationMetricSample.unavailable(descriptor, "network-manager-not-ready", now);
        }

        return IntegrationMetricSample.available(descriptor, sidebandDropsWindow.perSecond(network.debugSnapshot().sidebandDroppedCount(), now), now);
    }

    private IntegrationMetricSample sampleViewSubscriptions(long now) {
        IntegrationMetricDescriptor descriptor = IntegrationMetricSchema.descriptor(IntegrationMetricSchema.WORMHOLES_VIEW_SUBSCRIPTIONS);
        ViewServer view = viewServer();
        if (view == null) {
            return IntegrationMetricSample.unavailable(descriptor, "view-server-not-ready", now);
        }

        return IntegrationMetricSample.available(descriptor, view.statsSnapshot().subscriptions(), now);
    }

    private IntegrationMetricSample sampleViewTrackedEntities(long now) {
        IntegrationMetricDescriptor descriptor = IntegrationMetricSchema.descriptor(IntegrationMetricSchema.WORMHOLES_VIEW_TRACKED_ENTITIES);
        ViewServer view = viewServer();
        if (view == null) {
            return IntegrationMetricSample.unavailable(descriptor, "view-server-not-ready", now);
        }

        return IntegrationMetricSample.available(descriptor, view.statsSnapshot().trackedEntities(), now);
    }

    private IntegrationMetricSample sampleReplicatedBlocksPerSecond(long now) {
        IntegrationMetricDescriptor descriptor = IntegrationMetricSchema.descriptor(IntegrationMetricSchema.WORMHOLES_REPLICATED_BLOCKS_PER_SECOND);
        NetworkManager network = networkManager();
        if (network == null) {
            return IntegrationMetricSample.unavailable(descriptor, "network-manager-not-ready", now);
        }

        ChunkReplicationManager.Stats stats = network.getReplicationManager().statsSnapshot();
        return IntegrationMetricSample.available(descriptor, replicatedBlocksWindow.perSecond(stats.blocksSent(), now), now);
    }

    private IntegrationMetricSample sampleResyncRequestsTotal(long now) {
        IntegrationMetricDescriptor descriptor = IntegrationMetricSchema.descriptor(IntegrationMetricSchema.WORMHOLES_RESYNC_REQUESTS_TOTAL);
        NetworkManager network = networkManager();
        if (network == null) {
            return IntegrationMetricSample.unavailable(descriptor, "network-manager-not-ready", now);
        }

        return IntegrationMetricSample.available(descriptor, network.getReplicationManager().statsSnapshot().resyncRequests(), now);
    }

    private IntegrationMetricSample sampleTransfersInFlight(long now) {
        IntegrationMetricDescriptor descriptor = IntegrationMetricSchema.descriptor(IntegrationMetricSchema.WORMHOLES_TRANSFERS_IN_FLIGHT);
        TraversalService traversal = traversalService();
        if (traversal == null) {
            return IntegrationMetricSample.unavailable(descriptor, "traversal-service-not-ready", now);
        }

        return IntegrationMetricSample.available(descriptor, traversal.statsSnapshot().inFlight(), now);
    }

    private IntegrationMetricSample sampleTransfersFailedTotal(long now) {
        IntegrationMetricDescriptor descriptor = IntegrationMetricSchema.descriptor(IntegrationMetricSchema.WORMHOLES_TRANSFERS_FAILED_TOTAL);
        TraversalService traversal = traversalService();
        if (traversal == null) {
            return IntegrationMetricSample.unavailable(descriptor, "traversal-service-not-ready", now);
        }

        return IntegrationMetricSample.available(descriptor, traversal.statsSnapshot().failed(), now);
    }

    private NetworkManager networkManager() {
        Wormholes plugin = Wormholes.instance;
        return plugin == null ? null : plugin.getNetworkManager();
    }

    private ViewServer viewServer() {
        Wormholes plugin = Wormholes.instance;
        return plugin == null ? null : plugin.getViewServer();
    }

    private TraversalService traversalService() {
        Wormholes plugin = Wormholes.instance;
        return plugin == null ? null : plugin.getTraversalService();
    }

    private IntegrationMetricSample available(String key, double value, long now) {
        return IntegrationMetricSample.available(IntegrationMetricSchema.descriptor(key), value, now);
    }

    private static final class RateWindow {
        private static final long MIN_WINDOW_MS = 1000L;

        private boolean primed;
        private long windowStartMs;
        private long windowCount;
        private double ratePerSecond;

        synchronized double perSecond(long counter, long now) {
            if (!primed) {
                primed = true;
                windowStartMs = now;
                windowCount = counter;
                ratePerSecond = 0.0D;
                return 0.0D;
            }

            long elapsed = now - windowStartMs;
            if (elapsed < MIN_WINDOW_MS) {
                return ratePerSecond;
            }

            ratePerSecond = Math.max(0.0D, (counter - windowCount) / (elapsed / 1000.0D));
            windowStartMs = now;
            windowCount = counter;
            return ratePerSecond;
        }

        synchronized void clear() {
            primed = false;
            windowStartMs = 0L;
            windowCount = 0L;
            ratePerSecond = 0.0D;
        }
    }
}
