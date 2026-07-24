package art.arcane.wormholes.config.toml;

import art.arcane.wormholes.util.project.config.ConfigDescription;
import art.arcane.wormholes.util.project.config.ConfigDoc;

@ConfigDoc({
    "Advanced projection compatibility overrides."
})
public class ProjectionConfig {
    public double range = 48.0;
    public int refreshIntervalTicks = 1;
    public double nearPlanePadding = 2.0;
    @ConfigDescription({
        "How far (in blocks) the projected image extends outward past the portal aperture edges.",
        "Raise this if real blocks bleed through at the rim of the projection; each +0.25 widens the rendered window by a quarter block on every side."
    })
    public double aperturePaddingBlocks = 0.75;
    public double frustumCullingRatio = 0.2;
    public int depthBlocks = 64;
    public int recursivePortalDepth = 3;
    public int stableCellResampleIntervalTicks = 4;
    public boolean clientViewDistanceCap = true;
    public boolean foveatedUnrendering = false;
    public double observerInterestDot = -0.2;
    public double sideGraceDot = 0.12;
    public int maxProjectorsPerTick = 24;
    public int maxPortalsPerObserverTick = 4;
    public int maxNewObserverScansPerTick = 64;
    public int interestGraceTicks = 5;
    public int initialResendPasses = 4;
}
