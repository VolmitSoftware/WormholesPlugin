package art.arcane.wormholes;

import art.arcane.wormholes.config.WormholesSettings;
import art.arcane.wormholes.config.toml.AdvancedConfig;
import art.arcane.wormholes.config.toml.MainConfig;
import art.arcane.wormholes.config.toml.ProjectionConfig;
import art.arcane.wormholes.config.toml.RenderConfig;

public final class Settings {
    public static boolean ENABLE_PARTICLES = true;
    public static int MAX_PORTAL_SIZE_CUBED = 24;
    public static double PORTAL_CONSTRUCT_SPEED = 0.975D;
    public static double PORTAL_COLAPSE_SPEED = 0.91D;
    public static double PORTAL_OPEN_SPEED = 1.0D;
    public static double PORTAL_CLOSE_SPEED = 1.0D;
    public static boolean DEBUG_RENDERING = false;
    public static boolean DEBUG_VIEWPORT = false;
    public static boolean DEBUG_TRAVERSABLES = true;
    public static double FRUSTUM_CULLING_RATIO = 0.2D;
    public static double CAPTURE_ZONE_RADIUS = 8.0D;
    public static double PROJECTION_RANGE = 32.0D;
    public static double PROJECTION_FLUSH_TIME = 750.0D;
    public static double NEAR_PLANE_PADDING = 2.0D;
    public static double PROJECTION_APERTURE_PADDING_BLOCKS = 1.0D;
    public static boolean LIGHTING_FIDELITY = true;
    public static int LIGHTING_REFRESH_INTERVAL_TICKS = 4;
    public static boolean ENTITY_SPOOFING = true;
    public static double ENTITY_SPOOF_RANGE = 48.0D;
    public static int MAX_SPOOFED_ENTITIES = 24;
    public static int PROJECTION_REFRESH_INTERVAL_TICKS = 2;
    public static int PROJECTION_DEPTH_BLOCKS = 64;
    public static int PROJECTION_RECURSIVE_PORTAL_DEPTH = 3;
    public static int PROJECTION_STABLE_CELL_RESAMPLE_INTERVAL_TICKS = 1;
    public static boolean PROJECTION_CLIENT_VIEW_DISTANCE_CAP = true;
    public static boolean PROJECTION_FOVEATED_UNRENDERING = false;
    public static double PROJECTION_OBSERVER_INTEREST_DOT = -0.2D;
    public static double PROJECTION_SIDE_GRACE_DOT = 0.12D;
    public static double PROJECTION_STATIONARY_REUSE_DISTANCE_BLOCKS = 0.0D;
    public static double PROJECTION_STATIONARY_REUSE_ANGLE_DEGREES = 1.5D;
    public static int PROJECTION_MAX_PROJECTORS_PER_TICK = 24;
    public static int PROJECTION_INITIAL_RESEND_PASSES = 4;
    public static int OCTREE_LEAF_SIZE = 4;
    public static int PARALLEL_CHUNK_READS = 4;
    public static long OCTREE_REBUILD_INTERVAL_MS = 5000L;
    public static int CHUNK_SNAPSHOT_RADIUS = 6;
    public static boolean DEBUG = false;

    private Settings() {
    }

    public static void refresh(WormholesSettings src) {
        if (src == null) {
            return;
        }
        MainConfig main = src.getMain();
        ProjectionConfig projection = src.getProjection();
        RenderConfig render = src.getRender();
        AdvancedConfig advanced = src.getAdvanced();

        ENABLE_PARTICLES = main.enableParticles;
        MAX_PORTAL_SIZE_CUBED = clampInt(main.maxPortalSizeCubed, 1, 256);
        PORTAL_CONSTRUCT_SPEED = clampDouble(main.portalConstructSpeed, 0.0D, 1.0D);
        PORTAL_COLAPSE_SPEED = clampDouble(main.portalCollapseSpeed, 0.0D, 1.0D);
        PORTAL_OPEN_SPEED = clampDouble(main.portalOpenSpeed, 0.0D, 1.0D);
        PORTAL_CLOSE_SPEED = clampDouble(main.portalCloseSpeed, 0.0D, 1.0D);
        DEBUG_RENDERING = main.debugRendering;
        DEBUG_VIEWPORT = main.debugViewport;
        DEBUG_TRAVERSABLES = main.debugTraversables;
        DEBUG = main.verboseLogging;

        FRUSTUM_CULLING_RATIO = clampDouble(projection.frustumCullingRatio, 0.0D, 1.0D);
        PROJECTION_RANGE = clampDouble(projection.range, 1.0D, 256.0D);
        PROJECTION_FLUSH_TIME = clampDouble(projection.flushTimeMillis, 50.0D, 5000.0D);
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
        PROJECTION_INITIAL_RESEND_PASSES = clampInt(projection.initialResendPasses, 0, 20);

        LIGHTING_FIDELITY = render.lightingFidelity;
        LIGHTING_REFRESH_INTERVAL_TICKS = clampInt(render.lightingRefreshIntervalTicks, 1, 40);
        ENTITY_SPOOFING = render.entitySpoofing;
        ENTITY_SPOOF_RANGE = clampDouble(render.entitySpoofRange, 1.0D, 256.0D);
        MAX_SPOOFED_ENTITIES = clampInt(render.maxSpoofedEntities, 0, 256);
        CAPTURE_ZONE_RADIUS = clampDouble(render.captureZoneRadius, 1.0D, 64.0D);

        OCTREE_LEAF_SIZE = clampInt(advanced.octreeLeafSize, 1, 32);
        PARALLEL_CHUNK_READS = clampInt(advanced.parallelChunkReads, 1, 32);
        OCTREE_REBUILD_INTERVAL_MS = clampLong(advanced.octreeRebuildIntervalMillis, 500L, 600_000L);
        CHUNK_SNAPSHOT_RADIUS = clampInt(advanced.chunkSnapshotRadius, 1, 32);
    }

    private static int clampInt(int value, int min, int max) {
        return Math.max(min, Math.min(max, value));
    }

    private static long clampLong(long value, long min, long max) {
        return Math.max(min, Math.min(max, value));
    }

    private static double clampDouble(double value, double min, double max) {
        return Math.max(min, Math.min(max, value));
    }
}
