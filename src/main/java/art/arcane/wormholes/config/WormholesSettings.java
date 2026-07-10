package art.arcane.wormholes.config;

import art.arcane.wormholes.config.toml.MainConfig;
import art.arcane.wormholes.config.toml.NetworkConfig;
import art.arcane.wormholes.config.toml.ProjectionConfig;
import art.arcane.wormholes.config.toml.RenderConfig;
import art.arcane.wormholes.config.toml.WormholesConfigFile;
import art.arcane.wormholes.util.project.config.TomlCodec;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

public final class WormholesSettings {
    public static final String CONFIG_FILE_NAME = "wormholes.toml";

    private static final Object IO_LOCK = new Object();

    private final MainConfig main;
    private final ProjectionConfig projection;
    private final RenderConfig render;
    private final NetworkConfig network;
    private final VisualQualityProfile visualQualityProfile;

    public WormholesSettings(MainConfig main, ProjectionConfig projection, RenderConfig render, NetworkConfig network) {
        this(main, projection, render, network, VisualQualityProfile.AUTO);
    }

    private WormholesSettings(MainConfig main, ProjectionConfig projection, RenderConfig render, NetworkConfig network, VisualQualityProfile visualQualityProfile) {
        this.main = main;
        this.projection = projection;
        this.render = render;
        this.network = network;
        this.visualQualityProfile = visualQualityProfile;
    }

    public static WormholesSettings loadAll(Path dataFolder) {
        synchronized (IO_LOCK) {
            Path configDir = dataFolder.resolve("config");
            createDirectories(configDir);
            Path consolidated = configDir.resolve(CONFIG_FILE_NAME);
            WormholesConfigFile file;

            if (Files.isRegularFile(consolidated)) {
                if (!hasSchemaMarker(consolidated)) {
                    throw new IllegalArgumentException("Unsupported schema-less Wormholes config " + consolidated + "; schema = " + WormholesConfigFile.CURRENT_SCHEMA + " is required.");
                }
                file = loadRequired(consolidated.toFile(), WormholesConfigFile.class);
                VisualQualityProfile profile = validateAndNormalize(file);
                return fromFile(file, profile);
            }

            file = new WormholesConfigFile();
            TomlCodec.writeCanonical(consolidated.toFile(), file);
            return fromFile(file, VisualQualityProfile.AUTO);
        }
    }

    public void save(Path dataFolder) {
        synchronized (IO_LOCK) {
            Path configDir = dataFolder.resolve("config");
            createDirectories(configDir);
            WormholesConfigFile file = new WormholesConfigFile();
            file.schema = WormholesConfigFile.CURRENT_SCHEMA;
            file.quality = visualQualityProfile.configValue();
            file.main = main;
            file.network = network;
            file.projection = projection;
            file.render = render;
            TomlCodec.writeCanonical(configDir.resolve(CONFIG_FILE_NAME).toFile(), file);
        }
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

    public VisualQualityProfile getVisualQualityProfile() {
        return visualQualityProfile;
    }

    private static <T> T loadRequired(File file, Class<T> type) {
        TomlCodec.LoadResult<T> result = TomlCodec.readExisting(file, type);
        if (!result.isSuccess()) {
            throw new IllegalStateException("Failed to parse configuration " + file.getAbsolutePath() + "; keeping the previous live settings.", result.error());
        }
        return result.value();
    }

    private static VisualQualityProfile validateAndNormalize(WormholesConfigFile file) {
        if (file == null) {
            throw new IllegalStateException("Configuration did not produce a settings document.");
        }
        if (file.schema != WormholesConfigFile.CURRENT_SCHEMA) {
            throw new IllegalArgumentException("Unsupported Wormholes config schema " + file.schema + "; expected " + WormholesConfigFile.CURRENT_SCHEMA + ".");
        }
        VisualQualityProfile profile = VisualQualityProfile.parse(file.quality);
        file.quality = profile.configValue();
        return profile;
    }

    private static WormholesSettings fromFile(WormholesConfigFile file, VisualQualityProfile profile) {
        MainConfig main = file.main == null ? new MainConfig() : file.main;
        ProjectionConfig projection = file.projection == null ? new ProjectionConfig() : file.projection;
        RenderConfig render = file.render == null ? new RenderConfig() : file.render;
        NetworkConfig network = file.network == null ? new NetworkConfig() : file.network;
        return new WormholesSettings(main, projection, render, network, profile);
    }

    private static boolean hasSchemaMarker(Path file) {
        try {
            for (String line : Files.readAllLines(file)) {
                String trimmed = line.trim();
                if (trimmed.startsWith("[")) {
                    return false;
                }
                int separator = trimmed.indexOf('=');
                if (!trimmed.startsWith("#") && separator > 0 && trimmed.substring(0, separator).trim().equals("schema")) {
                    return true;
                }
            }
            return false;
        } catch (IOException e) {
            throw new IllegalStateException("Failed to inspect configuration schema in " + file, e);
        }
    }

    private static void createDirectories(Path directory) {
        try {
            Files.createDirectories(directory);
        } catch (IOException e) {
            throw new IllegalStateException("Could not create directory: " + directory, e);
        }
    }
}
