package art.arcane.wormholes.config.toml;

import art.arcane.wormholes.util.project.config.ConfigAdvanced;
import art.arcane.wormholes.util.project.config.ConfigDoc;

@ConfigDoc({
    "Advanced projection compatibility overrides."
})
public class ProjectionConfig {
    @ConfigAdvanced
    public double range = 48.0;
    @ConfigAdvanced
    public int refreshIntervalTicks = 1;
    @ConfigAdvanced
    public double nearPlanePadding = 2.0;
    @ConfigAdvanced
    public double aperturePaddingBlocks = 0.5;
    @ConfigAdvanced
    public double frustumCullingRatio = 0.2;
    @ConfigAdvanced
    public int depthBlocks = 64;
    @ConfigAdvanced
    public int recursivePortalDepth = 3;
    @ConfigAdvanced
    public int stableCellResampleIntervalTicks = 4;
    @ConfigAdvanced
    public boolean clientViewDistanceCap = true;
    @ConfigAdvanced
    public boolean foveatedUnrendering = false;
    @ConfigAdvanced
    public double observerInterestDot = -0.2;
    @ConfigAdvanced
    public double sideGraceDot = 0.12;
    @ConfigAdvanced
    public int maxProjectorsPerTick = 24;
    @ConfigAdvanced
    public int maxPortalsPerObserverTick = 4;
    @ConfigAdvanced
    public int interestGraceTicks = 5;
    @ConfigAdvanced
    public int initialResendPasses = 4;
}
