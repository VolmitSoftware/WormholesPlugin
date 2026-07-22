package art.arcane.wormholes.config.toml;

import art.arcane.wormholes.util.project.config.ConfigAdvanced;
import art.arcane.wormholes.util.project.config.ConfigDescription;
import art.arcane.wormholes.util.project.config.ConfigDoc;

@ConfigDoc({
    "Gameplay settings. Changes hot-reload."
})
public class MainConfig {
    @ConfigDescription("Bundled locale to use. An optional plugins/Wormholes/languages/<locale>.toml file overrides individual bundled messages.")
    public String language = "en_US";

    @ConfigAdvanced
    @ConfigDescription("Comma-separated bundled or custom fallback locales in priority order. Built-in English is always the final fallback.")
    public String languageFallbacks = "";

    @ConfigAdvanced
    @ConfigDescription("Compatibility override for portal particles. Prefer the top-level quality profile.")
    public boolean enableParticles = true;

    @ConfigDescription("Replace vanilla Nether and End portals with auto-linking Wormholes portals.")
    public boolean replaceNetherAndEndPortals = true;

    @ConfigDescription("Enable the complete Dimensional Doors survival feature set.")
    public boolean dimensionalDoorsEnabled = true;

    @ConfigAdvanced
    public double portalConstructSpeed = 0.975;
    @ConfigAdvanced
    public double portalCollapseSpeed = 0.91;
    @ConfigAdvanced
    public boolean verboseLogging = false;
    @ConfigAdvanced
    public boolean debugRendering = false;
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
