package art.arcane.wormholes;

import art.arcane.wormholes.config.WormholesSettings;
import art.arcane.wormholes.config.toml.MainConfig;
import art.arcane.wormholes.config.toml.ProjectionConfig;
import art.arcane.wormholes.config.toml.RenderConfig;

public final class Settings {
    public static volatile boolean ENABLE_PARTICLES = true;
    public static volatile double PORTAL_CONSTRUCT_SPEED = 0.975D;
    public static volatile double PORTAL_COLAPSE_SPEED = 0.91D;
    public static volatile boolean DEBUG_RENDERING = false;
    public static volatile boolean DEBUG_TRAVERSABLES = true;
    public static volatile double FRUSTUM_CULLING_RATIO = 0.2D;
    public static volatile double CAPTURE_ZONE_RADIUS = 8.0D;
    public static volatile double PROJECTION_RANGE = 32.0D;
    public static volatile double NEAR_PLANE_PADDING = 2.0D;
    public static volatile double PROJECTION_APERTURE_PADDING_BLOCKS = 1.0D;
    public static volatile boolean LIGHTING_FIDELITY = true;
    public static volatile int LIGHTING_REFRESH_INTERVAL_TICKS = 4;
    public static volatile int LIGHTING_MAX_SECTIONS_PER_PASS = 2;
    public static volatile boolean ADAPTIVE_LIGHTING = true;
    public static volatile boolean ENTITY_SPOOFING = true;
    public static volatile int ENTITY_UPDATE_INTERVAL_TICKS = 1;
    public static volatile double ENTITY_SPOOF_RANGE = 48.0D;
    public static volatile int ENTITY_CANDIDATE_CACHE_TICKS = 3;
    public static volatile int MAX_SPOOFED_ENTITIES = 24;
    public static volatile int PROJECTION_REFRESH_INTERVAL_TICKS = 1;
    public static volatile int PROJECTION_DEPTH_BLOCKS = 64;
    public static volatile int PROJECTION_RECURSIVE_PORTAL_DEPTH = 3;
    public static volatile int PROJECTION_STABLE_CELL_RESAMPLE_INTERVAL_TICKS = 4;
    public static volatile boolean PROJECTION_CLIENT_VIEW_DISTANCE_CAP = true;
    public static volatile boolean PROJECTION_FOVEATED_UNRENDERING = false;
    public static volatile double PROJECTION_OBSERVER_INTEREST_DOT = -0.2D;
    public static volatile double PROJECTION_SIDE_GRACE_DOT = 0.12D;
    public static volatile double PROJECTION_STATIONARY_REUSE_DISTANCE_BLOCKS = 0.0D;
    public static volatile double PROJECTION_STATIONARY_REUSE_ANGLE_DEGREES = 1.5D;
    public static volatile int PROJECTION_MAX_PROJECTORS_PER_TICK = 24;
    public static volatile int PROJECTION_MAX_PORTALS_PER_OBSERVER_TICK = 4;
    public static volatile int PROJECTION_INTEREST_GRACE_TICKS = 5;
    public static volatile int PROJECTION_INITIAL_RESEND_PASSES = 4;
    public static volatile long TELEPORT_COOLDOWN_MILLIS = 1000L;
    public static volatile boolean ARRIVAL_PREWARM_ON_INTEREST = true;
    public static volatile int ARRIVAL_WARM_RADIUS_CHUNKS = 4;
    public static volatile long ARRIVAL_WARM_HOLD_MILLIS = 5000L;
    public static volatile long ARRIVAL_WARM_THROTTLE_MILLIS = 1000L;
    public static volatile boolean DEBUG = false;

    private Settings() {
    }

    public static void refresh(WormholesSettings src) {
        if (src == null) {
            return;
        }
        MainConfig main = src.getMain();
        ProjectionConfig projection = src.getProjection();
        RenderConfig render = src.getRender();

        ENABLE_PARTICLES = main.enableParticles;
        PORTAL_CONSTRUCT_SPEED = clampDouble(main.portalConstructSpeed, 0.0D, 1.0D);
        PORTAL_COLAPSE_SPEED = clampDouble(main.portalCollapseSpeed, 0.0D, 1.0D);
        DEBUG_RENDERING = main.debugRendering;
        DEBUG_TRAVERSABLES = main.debugTraversables;
        TELEPORT_COOLDOWN_MILLIS = clampInt(main.teleportCooldownMillis, 0, 60_000);
        ARRIVAL_PREWARM_ON_INTEREST = main.arrivalPrewarmOnInterest;
        ARRIVAL_WARM_RADIUS_CHUNKS = clampInt(main.arrivalWarmRadiusChunks, 0, 12);
        ARRIVAL_WARM_HOLD_MILLIS = clampInt(main.arrivalWarmHoldMillis, 0, 60_000);
        ARRIVAL_WARM_THROTTLE_MILLIS = clampInt(main.arrivalWarmThrottleMillis, 0, 60_000);
        DEBUG = main.verboseLogging;

        FRUSTUM_CULLING_RATIO = clampDouble(projection.frustumCullingRatio, 0.0D, 1.0D);
        PROJECTION_RANGE = clampDouble(projection.range, 1.0D, 256.0D);
        NEAR_PLANE_PADDING = clampDouble(projection.nearPlanePadding, 0.0D, 16.0D);
        PROJECTION_APERTURE_PADDING_BLOCKS = clampDouble(projection.aperturePaddingBlocks, 0.0D, 8.0D);
        PROJECTION_REFRESH_INTERVAL_TICKS = clampInt(projection.refreshIntervalTicks, 1, 20);
        PROJECTION_DEPTH_BLOCKS = clampInt(projection.depthBlocks, 1, 256);
        PROJECTION_RECURSIVE_PORTAL_DEPTH = clampInt(projection.recursivePortalDepth, 3, 64);
        PROJECTION_STABLE_CELL_RESAMPLE_INTERVAL_TICKS = clampInt(projection.stableCellResampleIntervalTicks, 1, 200);
        PROJECTION_CLIENT_VIEW_DISTANCE_CAP = projection.clientViewDistanceCap;
        PROJECTION_FOVEATED_UNRENDERING = projection.foveatedUnrendering;
        PROJECTION_OBSERVER_INTEREST_DOT = clampDouble(projection.observerInterestDot, -1.0D, 1.0D);
        PROJECTION_SIDE_GRACE_DOT = clampDouble(projection.sideGraceDot, 0.0D, 1.0D);
        PROJECTION_STATIONARY_REUSE_DISTANCE_BLOCKS = clampDouble(projection.stationaryReuseDistanceBlocks, 0.0D, 2.0D);
        PROJECTION_STATIONARY_REUSE_ANGLE_DEGREES = clampDouble(projection.stationaryReuseAngleDegrees, 0.0D, 45.0D);
        PROJECTION_MAX_PROJECTORS_PER_TICK = clampInt(projection.maxProjectorsPerTick, 1, 512);
        PROJECTION_MAX_PORTALS_PER_OBSERVER_TICK = clampInt(projection.maxPortalsPerObserverTick, 1, 64);
        PROJECTION_INTEREST_GRACE_TICKS = clampInt(projection.interestGraceTicks, 0, 100);
        PROJECTION_INITIAL_RESEND_PASSES = clampInt(projection.initialResendPasses, 0, 20);

        LIGHTING_FIDELITY = render.lightingFidelity;
        LIGHTING_REFRESH_INTERVAL_TICKS = clampInt(render.lightingRefreshIntervalTicks, 1, 40);
        LIGHTING_MAX_SECTIONS_PER_PASS = clampInt(render.lightingMaxSectionsPerPass, 1, 64);
        ADAPTIVE_LIGHTING = render.adaptiveLighting;
        ENTITY_SPOOFING = render.entitySpoofing;
        ENTITY_UPDATE_INTERVAL_TICKS = clampInt(render.entityUpdateIntervalTicks, 1, 20);
        ENTITY_SPOOF_RANGE = clampDouble(render.entitySpoofRange, 1.0D, 256.0D);
        ENTITY_CANDIDATE_CACHE_TICKS = clampInt(render.entityCandidateCacheTicks, 1, 40);
        MAX_SPOOFED_ENTITIES = clampInt(render.maxSpoofedEntities, 0, 256);
        CAPTURE_ZONE_RADIUS = clampDouble(render.captureZoneRadius, 1.0D, 64.0D);
    }

    private static int clampInt(int value, int min, int max) {
        return Math.max(min, Math.min(max, value));
    }

    private static double clampDouble(double value, double min, double max) {
        return Math.max(min, Math.min(max, value));
    }
}
