package art.arcane.wormholes.config.toml;

import art.arcane.wormholes.util.project.config.ConfigAdvanced;
import art.arcane.wormholes.util.project.config.ConfigDescription;
import art.arcane.wormholes.util.project.config.ConfigDoc;

@ConfigDoc({
    "Advanced visual compatibility overrides."
})
public class RenderConfig {
    @ConfigAdvanced
    @ConfigDescription("Send destination-world lighting with projected blocks.")
    public boolean lightingFidelity = true;

    @ConfigAdvanced
    @ConfigDescription("Show destination-side entities inside portal projections.")
    public boolean entitySpoofing = true;

    @ConfigAdvanced
    public int lightingRefreshIntervalTicks = 4;
    @ConfigAdvanced
    public int lightingMaxSectionsPerPass = 2;
    @ConfigAdvanced
    public boolean adaptiveLighting = true;
    @ConfigAdvanced
    public int entityUpdateIntervalTicks = 1;
    @ConfigAdvanced
    public double entitySpoofRange = 48.0;
    @ConfigAdvanced
    public int entityCandidateCacheTicks = 3;
    @ConfigAdvanced
    public int maxSpoofedEntities = 24;
    @ConfigAdvanced
    public double captureZoneRadius = 8.0;
}
