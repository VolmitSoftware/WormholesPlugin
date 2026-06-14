package art.arcane.wormholes.config;

import art.arcane.wormholes.config.toml.MainConfig;
import art.arcane.wormholes.config.toml.NetworkConfig;
import art.arcane.wormholes.config.toml.ProjectionConfig;
import art.arcane.wormholes.config.toml.RenderConfig;
import art.arcane.wormholes.util.project.config.TomlCodec;

import java.io.File;
import java.nio.file.Path;

public final class WormholesSettings {
    private final MainConfig main;
    private final ProjectionConfig projection;
    private final RenderConfig render;
    private final NetworkConfig network;

    public WormholesSettings(MainConfig main, ProjectionConfig projection, RenderConfig render, NetworkConfig network) {
        this.main = main;
        this.projection = projection;
        this.render = render;
        this.network = network;
    }

    public static WormholesSettings loadAll(Path dataFolder) {
        File configDir = dataFolder.resolve("config").toFile();
        if (!configDir.exists() && !configDir.mkdirs()) {
            throw new IllegalStateException("Could not create config directory: " + configDir);
        }
        MainConfig main = TomlCodec.loadOrCreate(new File(configDir, "main.toml"), MainConfig.class);
        ProjectionConfig projection = TomlCodec.loadOrCreate(new File(configDir, "projection.toml"), ProjectionConfig.class);
        RenderConfig render = TomlCodec.loadOrCreate(new File(configDir, "render.toml"), RenderConfig.class);
        NetworkConfig network = TomlCodec.loadOrCreate(new File(configDir, "network.toml"), NetworkConfig.class);
        return new WormholesSettings(main, projection, render, network);
    }

    public MainConfig getMain() {
        return main;
    }

    public ProjectionConfig getProjection() {
        return projection;
    }

    public RenderConfig getRender() {
        return render;
    }

    public NetworkConfig getNetwork() {
        return network;
    }
}
