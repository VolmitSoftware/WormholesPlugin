package art.arcane.wormholes.config.toml;

import art.arcane.wormholes.util.project.config.ConfigDescription;
import art.arcane.wormholes.util.project.config.ConfigDoc;

@ConfigDoc({
    "Wormholes projection cone parameters.",
    "Controls how far the local fake-block volume reaches, how deep the transformed destination volume can be,",
    "and how often the projection is rebuilt for nearby players."
})
public class ProjectionConfig {
    @ConfigDescription({
        "Maximum local fake-block cone range in blocks.",
        "Higher values let the client see a deeper portal volume, but cost grows quickly because more local cells must be tested.",
        "This is capped by the player's negotiated client view distance when client-view-distance-cap is true."
    })
    public double range = 32.0;

    @ConfigDescription({
        "Projector refresh interval in server ticks.",
        "1 updates every tick, 2 updates ten times per second, and higher values reduce CPU/network load at the cost of responsiveness."
    })
    public int refreshIntervalTicks = 2;

    @ConfigDescription({
        "Maximum stale-cell flush window in milliseconds.",
        "Kept for compatibility with older projection timing; lower values make stale projected blocks revert more aggressively."
    })
    public double flushTimeMillis = 750.0;

    @ConfigDescription({
        "Virtual-iris apex padding behind the player's eye in blocks.",
        "Higher values widen the cone when the player is close to the frame, reducing edge pop-out.",
        "Too high can project more cells than needed, so keep this near 1-3 unless close-up portals still clip."
    })
    public double nearPlanePadding = 2.0;

    @ConfigDescription({
        "Cube-face culling ratio for portal-frame face selection.",
        "Higher values test fewer frame faces and cost less, but can miss edge cases when viewing portals from steep angles.",
        "Lower values are more accurate for odd axes/heights but increase cell tests."
    })
    public double frustumCullingRatio = 0.2;

    @ConfigDescription({
        "Maximum transformed destination volume depth in blocks.",
        "A visible local cell farther behind the portal plane than this is skipped, even if projection.range is larger.",
        "Raise this when linked portals should show deeper rooms or terrain beyond the destination portal."
    })
    public int depthBlocks = 64;

    @ConfigDescription({
        "Cap projection range/depth by the player's client view distance and the server view distance.",
        "Leave enabled for public servers; disabling lets operators force larger projection distances for controlled tests."
    })
    public boolean clientViewDistanceCap = true;
}
