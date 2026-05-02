package art.arcane.wormholes.config.toml;

import art.arcane.wormholes.util.project.config.ConfigDescription;
import art.arcane.wormholes.util.project.config.ConfigDoc;

@ConfigDoc({
    "Wormholes render fidelity options.",
    "Controls optional visual packet fidelity and the physical area used for portal traversal."
})
public class RenderConfig {
    @ConfigDescription({
        "Enable destination-world lighting for projected blocks.",
        "When true, Wormholes sends UpdateLight packets so projected blocks use the remote world's sky and block light.",
        "Disable if another plugin aggressively manages client light packets or if lighting packets become expensive under profiling."
    })
    public boolean lightingFidelity = true;

    @ConfigDescription({
        "Minimum server ticks between projected light packet refreshes.",
        "Projected blocks still update on the normal projection refresh interval; this only coalesces expensive light-array rebuilds.",
        "1 updates lighting every projection pass. 4 is smoother than disabling lighting while avoiding a large per-move packet cost."
    })
    public int lightingRefreshIntervalTicks = 4;

    @ConfigDescription({
        "Enable fake entity spawn/teleport/destroy packets inside portal projections.",
        "When true, nearby non-player entities on the destination side are transformed into the local portal view for each observer.",
        "Disable if entity packets conflict with another disguise/NPC/entity-visibility plugin."
    })
    public boolean entitySpoofing = true;

    @ConfigDescription({
        "Maximum destination-side entity search radius in blocks.",
        "This is capped by projection depth and the player/server view-distance cap, so raising it above depth-blocks only helps when depth is also raised.",
        "Higher values scan and update more entities per projection tick."
    })
    public double entitySpoofRange = 48.0;

    @ConfigDescription({
        "Maximum fake destination entities sent to one observer through one portal.",
        "This prevents dense mob farms or item piles behind a portal from flooding one client with fake entity packets."
    })
    public int maxSpoofedEntities = 24;

    @ConfigDescription({
        "Capture-zone radius in blocks for traversal detection around a portal frame.",
        "Larger values make fast-moving entities less likely to miss the portal, but scan more nearby entities."
    })
    public double captureZoneRadius = 8.0;
}
