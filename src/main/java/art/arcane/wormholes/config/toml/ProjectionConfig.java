package art.arcane.wormholes.config.toml;

import art.arcane.wormholes.util.project.config.ConfigDoc;

@ConfigDoc({
    "Wormholes projection cone parameters (fixed)."
})
public class ProjectionConfig {
    public transient double range = 48.0;
    public transient int refreshIntervalTicks = 1;
    public transient double nearPlanePadding = 2.0;
    public transient double aperturePaddingBlocks = 0.5;
    public transient double frustumCullingRatio = 0.2;
    public transient int depthBlocks = 64;
    public transient int recursivePortalDepth = 3;
    public transient int stableCellResampleIntervalTicks = 4;
    public transient boolean clientViewDistanceCap = true;
    public transient boolean foveatedUnrendering = false;
    public transient double observerInterestDot = -0.2;
    public transient double sideGraceDot = 0.12;
    public transient int maxProjectorsPerTick = 24;
    public transient int maxPortalsPerObserverTick = 4;
    public transient int interestGraceTicks = 5;
    public transient int initialResendPasses = 4;
}
