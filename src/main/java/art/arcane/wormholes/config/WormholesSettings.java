package art.arcane.wormholes.config;

import art.arcane.wormholes.Wormholes;
import org.bukkit.configuration.file.FileConfiguration;

public final class WormholesSettings {
    private final boolean enableParticles;
    private final int maxPortalSizeCubed;
    private final double portalConstructSpeed;
    private final double portalCollapseSpeed;
    private final double portalOpenSpeed;
    private final double portalCloseSpeed;
    private final boolean debugRendering;
    private final boolean debugViewport;
    private final boolean debugTraversables;
    private final double frustumCullingRatio;
    private final double captureZoneRadius;
    private final double projectionRange;
    private final double projectionFlushTimeMillis;
    private final boolean debug;

    private WormholesSettings(
        boolean enableParticles,
        int maxPortalSizeCubed,
        double portalConstructSpeed,
        double portalCollapseSpeed,
        double portalOpenSpeed,
        double portalCloseSpeed,
        boolean debugRendering,
        boolean debugViewport,
        boolean debugTraversables,
        double frustumCullingRatio,
        double captureZoneRadius,
        double projectionRange,
        double projectionFlushTimeMillis,
        boolean debug
    ) {
        this.enableParticles = enableParticles;
        this.maxPortalSizeCubed = maxPortalSizeCubed;
        this.portalConstructSpeed = portalConstructSpeed;
        this.portalCollapseSpeed = portalCollapseSpeed;
        this.portalOpenSpeed = portalOpenSpeed;
        this.portalCloseSpeed = portalCloseSpeed;
        this.debugRendering = debugRendering;
        this.debugViewport = debugViewport;
        this.debugTraversables = debugTraversables;
        this.frustumCullingRatio = frustumCullingRatio;
        this.captureZoneRadius = captureZoneRadius;
        this.projectionRange = projectionRange;
        this.projectionFlushTimeMillis = projectionFlushTimeMillis;
        this.debug = debug;
    }

    public static WormholesSettings load(Wormholes plugin) {
        FileConfiguration config = plugin.getConfig();
        return new WormholesSettings(
            config.getBoolean("portal.enable-particles", true),
            clampInt(config.getInt("portal.max-portal-size-cubed", 24), 1, 256),
            clampDouble(config.getDouble("portal.construct-speed", 0.975), 0.0D, 1.0D),
            clampDouble(config.getDouble("portal.collapse-speed", 0.91D), 0.0D, 1.0D),
            clampDouble(config.getDouble("portal.open-speed", 1.0D), 0.0D, 1.0D),
            clampDouble(config.getDouble("portal.close-speed", 1.0D), 0.0D, 1.0D),
            config.getBoolean("debug.rendering", false),
            config.getBoolean("debug.viewport", false),
            config.getBoolean("debug.traversables", true),
            clampDouble(config.getDouble("projection.frustum-culling-ratio", 0.2D), 0.0D, 1.0D),
            clampDouble(config.getDouble("projection.capture-zone-radius", 8.0D), 1.0D, 64.0D),
            clampDouble(config.getDouble("projection.range", 16.0D), 1.0D, 256.0D),
            clampDouble(config.getDouble("projection.flush-time-millis", 750.0D), 50.0D, 5000.0D),
            config.getBoolean("debug.verbose-logging", false)
        );
    }

    public boolean isEnableParticles() {
        return enableParticles;
    }

    public int getMaxPortalSizeCubed() {
        return maxPortalSizeCubed;
    }

    public double getPortalConstructSpeed() {
        return portalConstructSpeed;
    }

    public double getPortalCollapseSpeed() {
        return portalCollapseSpeed;
    }

    public double getPortalOpenSpeed() {
        return portalOpenSpeed;
    }

    public double getPortalCloseSpeed() {
        return portalCloseSpeed;
    }

    public boolean isDebugRendering() {
        return debugRendering;
    }

    public boolean isDebugViewport() {
        return debugViewport;
    }

    public boolean isDebugTraversables() {
        return debugTraversables;
    }

    public double getFrustumCullingRatio() {
        return frustumCullingRatio;
    }

    public double getCaptureZoneRadius() {
        return captureZoneRadius;
    }

    public double getProjectionRange() {
        return projectionRange;
    }

    public double getProjectionFlushTimeMillis() {
        return projectionFlushTimeMillis;
    }

    public boolean isDebug() {
        return debug;
    }

    private static int clampInt(int value, int min, int max) {
        return Math.max(min, Math.min(max, value));
    }

    private static double clampDouble(double value, double min, double max) {
        return Math.max(min, Math.min(max, value));
    }
}
