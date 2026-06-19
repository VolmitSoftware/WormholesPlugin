package art.arcane.wormholes.config;

import art.arcane.wormholes.config.toml.MainConfig;
import art.arcane.wormholes.config.toml.NetworkConfig;
import art.arcane.wormholes.config.toml.ProjectionConfig;
import art.arcane.wormholes.config.toml.RenderConfig;
import art.arcane.wormholes.config.toml.WormholesConfigFile;
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

    public static final String CONFIG_FILE_NAME = "wormholes.toml";

    public static WormholesSettings loadAll(Path dataFolder) {
        File configDir = dataFolder.resolve("config").toFile();
        if (!configDir.exists() && !configDir.mkdirs()) {
            throw new IllegalStateException("Could not create config directory: " + configDir);
        }
        File consolidated = new File(configDir, CONFIG_FILE_NAME);
        if (!consolidated.exists()) {
            migrateLegacyConfig(configDir, consolidated);
        }
        WormholesConfigFile file = TomlCodec.loadOrCreate(consolidated, WormholesConfigFile.class);
        return new WormholesSettings(file.main, new ProjectionConfig(), file.render, file.network);
    }

    private static void migrateLegacyConfig(File configDir, File consolidated) {
        File legacyNetwork = new File(configDir, "network.toml");
        File legacyMain = new File(configDir, "main.toml");
        File legacyRender = new File(configDir, "render.toml");
        if (!legacyNetwork.exists() && !legacyMain.exists() && !legacyRender.exists()) {
            return;
        }
        WormholesConfigFile merged = new WormholesConfigFile();
        if (legacyNetwork.isFile()) {
            merged.network = TomlCodec.loadOrCreate(legacyNetwork, NetworkConfig.class);
        }
        if (legacyMain.isFile()) {
            merged.main = TomlCodec.loadOrCreate(legacyMain, MainConfig.class);
        }
        if (legacyRender.isFile()) {
            merged.render = TomlCodec.loadOrCreate(legacyRender, RenderConfig.class);
        }
        TomlCodec.writeCanonical(consolidated, merged);
    }

    public void save(Path dataFolder) {
        File configDir = dataFolder.resolve("config").toFile();
        if (!configDir.exists() && !configDir.mkdirs()) {
            throw new IllegalStateException("Could not create config directory: " + configDir);
        }
        WormholesConfigFile file = new WormholesConfigFile();
        file.network = network;
        file.main = main;
        file.render = render;
        TomlCodec.writeCanonical(new File(configDir, CONFIG_FILE_NAME), file);
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
