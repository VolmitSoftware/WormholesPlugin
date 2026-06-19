package art.arcane.wormholes.config.toml;

import art.arcane.wormholes.util.project.config.ConfigDoc;

@ConfigDoc({
    "Wormholes configuration. Almost everything is auto-determined or fixed by the plugin;",
    "the few settings below are the only operator-facing choices. Changes hot-reload."
})
public class WormholesConfigFile {
    public NetworkConfig network = new NetworkConfig();
    public MainConfig main = new MainConfig();
    public RenderConfig render = new RenderConfig();
}
