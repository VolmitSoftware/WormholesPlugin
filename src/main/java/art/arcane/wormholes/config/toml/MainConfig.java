package art.arcane.wormholes.config.toml;

import art.arcane.wormholes.util.project.config.ConfigDescription;
import art.arcane.wormholes.util.project.config.ConfigDoc;

@ConfigDoc({
    "Gameplay settings. Changes hot-reload."
})
public class MainConfig {
    @ConfigDescription("Bundled locale to use. An optional plugins/Wormholes/languages/<locale>.toml file overrides individual bundled messages.")
    public String language = "en_US";
    @ConfigDescription("Comma-separated bundled or custom fallback locales in priority order. Built-in English is always the final fallback.")
    public String languageFallbacks = "";
    @ConfigDescription("Compatibility override for portal particles. Prefer the top-level quality profile.")
    public boolean enableParticles = true;

    @ConfigDescription("Replace vanilla Nether and End portals with auto-linking Wormholes portals.")
    public boolean replaceNetherAndEndPortals = true;

    @ConfigDescription("Enable the complete Dimensional Doors survival feature set.")
    public boolean dimensionalDoorsEnabled = true;
    public double portalConstructSpeed = 0.975;
    public double portalCollapseSpeed = 0.91;
    public boolean verboseLogging = false;
    public boolean debugRendering = false;
    public int teleportCooldownMillis = 1000;
    public boolean arrivalPrewarmOnInterest = true;
    public int arrivalWarmRadiusChunks = 4;
    public int arrivalWarmMaxRadiusChunks = 10;
    public int arrivalWarmHoldMillis = 5000;
    public int arrivalWarmThrottleMillis = 1000;
    public boolean arrivalTransitionMask = true;
    public int arrivalTransitionMaskTicks = 25;
}
