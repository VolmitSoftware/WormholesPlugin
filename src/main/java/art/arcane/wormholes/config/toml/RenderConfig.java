package art.arcane.wormholes.config.toml;

import art.arcane.wormholes.util.project.config.ConfigDescription;
import art.arcane.wormholes.util.project.config.ConfigDoc;

@ConfigDoc({
    "Advanced visual compatibility overrides."
})
public class RenderConfig {
    @ConfigDescription("Send destination-world lighting with projected blocks.")
    public boolean lightingFidelity = false;
    @ConfigDescription("Show destination-side entities inside portal projections.")
    public boolean entitySpoofing = true;
    public int lightingRefreshIntervalTicks = 4;
    public int lightingMaxSectionsPerPass = 2;
    public boolean adaptiveLighting = true;
    public int entityUpdateIntervalTicks = 1;
    public double entitySpoofRange = 48.0;
    public int entityCandidateCacheTicks = 3;
    public int maxSpoofedEntities = 24;
    public double captureZoneRadius = 8.0;
}
