package art.arcane.wormholes.config.toml;

import art.arcane.wormholes.util.project.config.ConfigDescription;
import art.arcane.wormholes.util.project.config.ConfigDoc;

@ConfigDoc({
    "Wormholes main configuration.",
    "Edit values and save to apply; changes hot-reload without restarting the server."
})
public class MainConfig {
    @ConfigDescription({
        "Enable ambient, open, close, construction, and traversal particles.",
        "Disable only if particle packet volume is a problem or you want a completely quiet visual style."
    })
    public boolean enableParticles = true;

    @ConfigDescription({
        "Replace vanilla nether and end portals with auto-linking Wormholes portals.",
        "When a nether portal frame is lit, a Wormholes portal forms in it and a paired portal + landing area is created",
        "in the nether (vanilla 1:8 coordinate scaling). When an end portal forms, a Wormholes portal is placed just above",
        "its center so you can hop in, linked to a paired portal above the End platform. Set false to leave vanilla portals untouched."
    })
    public boolean replaceNetherAndEndPortals = true;

    public transient double portalConstructSpeed = 0.975;
    public transient double portalCollapseSpeed = 0.91;
    public transient boolean verboseLogging = false;
    public transient boolean debugRendering = false;
    public transient boolean debugTraversables = true;
    public transient int teleportCooldownMillis = 1000;
    public transient boolean arrivalPrewarmOnInterest = true;
    public transient int arrivalWarmRadiusChunks = 4;
    public transient int arrivalWarmMaxRadiusChunks = 10;
    public transient int arrivalWarmHoldMillis = 5000;
    public transient int arrivalWarmThrottleMillis = 1000;
    public transient boolean arrivalTransitionMask = true;
    public transient int arrivalTransitionMaskTicks = 25;
}
