package art.arcane.wormholes.survival.doors.dimension;

import io.papermc.paper.plugin.bootstrap.BootstrapContext;
import io.papermc.paper.plugin.bootstrap.PluginBootstrap;

import java.io.IOException;
import java.nio.file.Path;

/**
 * Makes the bundled pocket-dimension datapack available before Minecraft builds
 * its data-driven world registries.
 *
 * <p>This class is instantiated by Paper from {@code paper-plugin.yml}; it must
 * not call Bukkit because the server is not available during bootstrap.</p>
 */
@SuppressWarnings("UnstableApiUsage")
public final class WormholesBootstrap implements PluginBootstrap {
    @Override
    public void bootstrap(BootstrapContext context) {
        try {
            Path serverRoot = Path.of("").toAbsolutePath().normalize();
            PocketDatapackInstaller.stageConfigured(serverRoot);
        } catch (IOException exception) {
            throw new IllegalStateException("Unable to stage the bundled Wormholes pocket datapack before registry loading", exception);
        }
    }
}
