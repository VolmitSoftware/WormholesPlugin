package art.arcane.wormholes;

import art.arcane.wormholes.config.WormholesSettings;

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
    public static double PROJECTION_RANGE = 16.0D;
    public static double PROJECTION_FLUSH_TIME = 750.0D;
    public static boolean DEBUG = false;

    private Settings() {
    }

    public static void refresh(WormholesSettings src) {
        if (src == null) {
            return;
        }
        ENABLE_PARTICLES = src.isEnableParticles();
        MAX_PORTAL_SIZE_CUBED = src.getMaxPortalSizeCubed();
        PORTAL_CONSTRUCT_SPEED = src.getPortalConstructSpeed();
        PORTAL_COLAPSE_SPEED = src.getPortalCollapseSpeed();
        PORTAL_OPEN_SPEED = src.getPortalOpenSpeed();
        PORTAL_CLOSE_SPEED = src.getPortalCloseSpeed();
        DEBUG_RENDERING = src.isDebugRendering();
        DEBUG_VIEWPORT = src.isDebugViewport();
        DEBUG_TRAVERSABLES = src.isDebugTraversables();
        FRUSTUM_CULLING_RATIO = src.getFrustumCullingRatio();
        CAPTURE_ZONE_RADIUS = src.getCaptureZoneRadius();
        PROJECTION_RANGE = src.getProjectionRange();
        PROJECTION_FLUSH_TIME = src.getProjectionFlushTimeMillis();
        DEBUG = src.isDebug();
    }
}
