package art.arcane.wormholes.config.toml;

import art.arcane.wormholes.util.project.config.ConfigDescription;
import art.arcane.wormholes.util.project.config.ConfigDoc;

@ConfigDoc({
    "Wormholes settings. Changes hot-reload."
})
public class WormholesConfigFile {
    public static final int CURRENT_SCHEMA = 2;

    @ConfigDescription("Configuration format.")
    public int schema = CURRENT_SCHEMA;

    @ConfigDescription("Visual profile: auto, performance, balanced, or cinematic.")
    public String quality = "auto";

    public MainConfig main = new MainConfig();
    public NetworkConfig network = new NetworkConfig();
    public ProjectionConfig projection = new ProjectionConfig();
    public RenderConfig render = new RenderConfig();
}
