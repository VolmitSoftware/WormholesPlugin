package art.arcane.wormholes.config.toml;

import art.arcane.wormholes.util.project.config.ConfigDescription;
import art.arcane.wormholes.util.project.config.ConfigDoc;

@ConfigDoc({
    "Wormholes render fidelity options."
})
public class RenderConfig {
    @ConfigDescription({
        "Enable destination-world lighting for projected blocks.",
        "When true, Wormholes sends UpdateLight packets so projected blocks use the remote world's sky and block light.",
        "Disable if another plugin aggressively manages client light packets."
    })
    public boolean lightingFidelity = true;

    @ConfigDescription({
        "Enable fake entity spawn/teleport/destroy packets inside portal projections.",
        "When true, nearby non-player entities on the destination side are transformed into the local portal view for each observer.",
        "Disable if entity packets conflict with another disguise/NPC/entity-visibility plugin."
    })
    public boolean entitySpoofing = true;

    public transient int lightingRefreshIntervalTicks = 4;
    public transient int lightingMaxSectionsPerPass = 2;
    public transient boolean adaptiveLighting = true;
    public transient int entityUpdateIntervalTicks = 1;
    public transient double entitySpoofRange = 48.0;
    public transient int entityCandidateCacheTicks = 3;
    public transient int maxSpoofedEntities = 24;
    public transient double captureZoneRadius = 8.0;
}
