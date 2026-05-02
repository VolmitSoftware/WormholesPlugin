package art.arcane.wormholes.config.toml;

import art.arcane.wormholes.util.project.config.ConfigDescription;
import art.arcane.wormholes.util.project.config.ConfigDoc;
import art.arcane.wormholes.util.project.config.RestartRequired;

@ConfigDoc({
    "Wormholes advanced / performance tuning.",
    "Fields here are reserved for deeper spatial-index work and heavier projection experiments.",
    "Restart-required fields are marked individually; ordinary render tuning lives in projection.toml and render.toml."
})
public class AdvancedConfig {
    @ConfigDescription({
        "Reserved OctTree leaf size in blocks for future indexed destination-world sampling.",
        "Smaller values mean finer cached volume queries and more memory; powers of two are preferred.",
        "Current live projector samples transformed block coordinates directly, so changing this only matters once OctTree indexing is enabled."
    })
    @RestartRequired
    public int octreeLeafSize = 4;

    @ConfigDescription({
        "Reserved parallel chunk-snapshot read count for future OctTree builds.",
        "Higher values can build destination caches faster but increase async CPU and scheduler pressure."
    })
    public int parallelChunkReads = 4;

    @ConfigDescription({
        "Reserved full OctTree rebuild cadence in milliseconds.",
        "Future indexed mode will use this to refresh destination-world caches when block events are missed."
    })
    public long octreeRebuildIntervalMillis = 5000L;

    @ConfigDescription({
        "Reserved chunk radius around each linked destination portal for future destination cache population.",
        "Each chunk adds a 16x16 column to scan, so memory and build cost grow quickly with this value."
    })
    public int chunkSnapshotRadius = 6;
}
