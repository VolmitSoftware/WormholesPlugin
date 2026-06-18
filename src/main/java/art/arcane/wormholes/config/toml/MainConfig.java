package art.arcane.wormholes.config.toml;

import art.arcane.wormholes.util.project.config.ConfigDescription;
import art.arcane.wormholes.util.project.config.ConfigDoc;

@ConfigDoc({
    "Wormholes main configuration.",
    "Edit values and save to apply; most fields hot-reload without restarting the server.",
    "Portal animation values are probabilities sampled over time, not fixed durations."
})
public class MainConfig {
    @ConfigDescription({
        "Enable ambient, open, close, construction, and traversal particles.",
        "Disable only if particle packet volume is a problem or you want a completely quiet visual style."
    })
    public boolean enableParticles = true;

    @ConfigDescription({
        "Portal construction animation speed from 0.0 to 1.0.",
        "Higher values consume placed runes into the portal faster; 1.0 completes as fast as the scheduler can process it."
    })
    public double portalConstructSpeed = 0.975;

    @ConfigDescription({
        "Portal collapse/refund animation speed from 0.0 to 1.0.",
        "Higher values return portal rune drops faster when a portal fails or is removed."
    })
    public double portalCollapseSpeed = 0.91;

    @ConfigDescription({
        "Verbose console logging for projection and portal lifecycle diagnostics.",
        "Enable only while debugging; it can be noisy when several players are viewing portals."
    })
    public boolean verboseLogging = false;

    @ConfigDescription({
        "Visualize projection cone behavior in-world.",
        "Developer aid for tuning projection geometry; leave false during normal gameplay."
    })
    public boolean debugRendering = false;

    @ConfigDescription({
        "Send traversal debug output when entities or players pass through portals.",
        "Useful for diagnosing velocity/look rotation; disable on live servers if players should not see debug messages."
    })
    public boolean debugTraversables = true;

    @ConfigDescription({
        "Milliseconds an entity must wait before the same portal can teleport it again.",
        "This is only a safety backstop against double-fires: an entity must also fully leave the portal opening and",
        "re-enter before another teleport triggers, so this can be kept low. Set to 0 to rely purely on the leave-and-return rule."
    })
    public int teleportCooldownMillis = 1000;

    @ConfigDescription({
        "Pre-load and pin the destination's chunks while a player is looking at / approaching a portal.",
        "This is what makes traversal feel seamless: the far side is already generated and resident before you cross,",
        "so there is no region-thread hitch and the client streams chunks in a burst instead of trickling them in.",
        "Disable to fall back to warming only at the instant of teleport."
    })
    public boolean arrivalPrewarmOnInterest = true;

    @ConfigDescription({
        "Radius, in chunks, of the destination area kept loaded around a portal exit (0 = just the landing chunk).",
        "Larger values cover more of the player's view distance for a smoother arrival but pin more chunks in memory.",
        "4 covers a 9x9 chunk area; raise toward your server view-distance for the most seamless feel."
    })
    public int arrivalWarmRadiusChunks = 4;

    @ConfigDescription({
        "Milliseconds the destination chunks stay pinned after the last warm before being released.",
        "Acts as a grace window so chunks do not unload while the client is still streaming them in after arrival."
    })
    public int arrivalWarmHoldMillis = 5000;

    @ConfigDescription({
        "Minimum milliseconds between proactive re-warms of the same destination while a player keeps looking at a portal.",
        "Keeps the pin alive without re-issuing chunk work every tick; keep this below the hold time."
    })
    public int arrivalWarmThrottleMillis = 1000;
}
