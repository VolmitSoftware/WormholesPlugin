package art.arcane.wormholes.survival.doors.dimension;

import io.papermc.paper.datapack.Datapack;
import io.papermc.paper.datapack.DiscoveredDatapack;
import io.papermc.paper.plugin.bootstrap.BootstrapContext;
import io.papermc.paper.plugin.bootstrap.PluginBootstrap;
import io.papermc.paper.plugin.lifecycle.event.types.LifecycleEvents;

import java.io.IOException;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.URL;

/**
 * Makes the bundled pocket-dimension datapack available before Minecraft builds
 * its data-driven world registries.
 *
 * <p>This class is instantiated by Paper from {@code paper-plugin.yml}; it must
 * not call Bukkit because the server is not available during bootstrap.</p>
 */
@SuppressWarnings("UnstableApiUsage")
public final class WormholesBootstrap implements PluginBootstrap {
    static final String PACK_RESOURCE = "/wormholes-pockets-pack";
    static final String PACK_ID = "pocket-dimension";

    @Override
    public void bootstrap(BootstrapContext context) {
        context.getLifecycleManager().registerEventHandler(LifecycleEvents.DATAPACK_DISCOVERY, event -> {
            URI packUri = bundledPackUri();
            try {
                DiscoveredDatapack pack = event.registrar().discoverPack(packUri, PACK_ID, configurer -> configurer
                    .autoEnableOnServerStart(true)
                    .position(true, Datapack.Position.TOP));
                if (pack == null) {
                    throw new IllegalStateException("Paper did not accept the bundled Wormholes pocket-dimension datapack at " + packUri);
                }
            } catch (IOException exception) {
                throw new IllegalStateException("Unable to discover the bundled Wormholes pocket-dimension datapack", exception);
            }
        });
    }

    private static URI bundledPackUri() {
        URL resource = WormholesBootstrap.class.getResource(PACK_RESOURCE);
        if (resource == null) {
            throw new IllegalStateException("Missing bundled datapack resource " + PACK_RESOURCE);
        }
        try {
            return resource.toURI();
        } catch (URISyntaxException exception) {
            throw new IllegalStateException("Invalid bundled datapack URI " + resource, exception);
        }
    }
}
