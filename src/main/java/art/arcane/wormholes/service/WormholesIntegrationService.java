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

    public void register() {
        Bukkit.getServicesManager().register(IntegrationServiceContract.class, this, Wormholes.instance, ServicePriority.Normal);
        Wormholes.v("Integration provider registered for Wormholes");
    }

    public void unregister() {
        Bukkit.getServicesManager().unregister(IntegrationServiceContract.class, this);
        WormholesTelemetry.clear();
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

    private IntegrationMetricSample available(String key, double value, long now) {
        return IntegrationMetricSample.available(IntegrationMetricSchema.descriptor(key), value, now);
    }
}
