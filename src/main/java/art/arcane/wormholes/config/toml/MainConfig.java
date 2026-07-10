package art.arcane.wormholes.config.toml;

import art.arcane.wormholes.util.project.config.ConfigAdvanced;
import art.arcane.wormholes.util.project.config.ConfigDescription;
import art.arcane.wormholes.util.project.config.ConfigDoc;

@ConfigDoc({
    "Gameplay settings. Changes hot-reload."
})
public class MainConfig {
    @ConfigAdvanced
    @ConfigDescription("Compatibility override for portal particles. Prefer the top-level quality profile.")
    public boolean enableParticles = true;

    @ConfigDescription("Replace vanilla Nether and End portals with auto-linking Wormholes portals.")
    public boolean replaceNetherAndEndPortals = true;

    @ConfigAdvanced
    public double portalConstructSpeed = 0.975;
    @ConfigAdvanced
    public double portalCollapseSpeed = 0.91;
    @ConfigAdvanced
    public boolean verboseLogging = false;
    @ConfigAdvanced
    public boolean debugRendering = false;
    @ConfigAdvanced
    public boolean debugTraversables = true;
    @ConfigAdvanced
    public int teleportCooldownMillis = 1000;
    @ConfigAdvanced
    public boolean arrivalPrewarmOnInterest = true;
    @ConfigAdvanced
    public int arrivalWarmRadiusChunks = 4;
    @ConfigAdvanced
    public int arrivalWarmMaxRadiusChunks = 10;
    @ConfigAdvanced
    public int arrivalWarmHoldMillis = 5000;
    @ConfigAdvanced
    public int arrivalWarmThrottleMillis = 1000;
    @ConfigAdvanced
    public boolean arrivalTransitionMask = true;
    @ConfigAdvanced
    public int arrivalTransitionMaskTicks = 25;
}
