package art.arcane.wormholes.survival.doors.dimension;

import art.arcane.wormholes.platform.WormholesPlatform;
import org.bukkit.Bukkit;
import org.bukkit.NamespacedKey;
import org.bukkit.World;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.HandlerList;
import org.bukkit.event.Listener;
import org.bukkit.event.server.ServerLoadEvent;
import org.bukkit.event.world.WorldLoadEvent;
import org.bukkit.event.world.WorldUnloadEvent;
import org.bukkit.plugin.Plugin;

import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Resolves the persistent world created from the bundled data-driven dimension.
 *
 * <p>The plugin currently loads at STARTUP, so its Java plugin can be enabled
 * before custom dimensions finish loading. Consumers may either query
 * {@link #world()} or compose work from {@link #whenReady()}. Completion occurs
 * on Paper's world-load thread; entity or region work still belongs on the
 * relevant Folia scheduler.</p>
 */
public final class PocketWorldService implements Listener, AutoCloseable {
    public static final NamespacedKey WORLD_KEY = new NamespacedKey("wormholes", "pockets");

    private final Plugin plugin;
    private final AtomicBoolean started = new AtomicBoolean();
    private final CompletableFuture<World> ready = new CompletableFuture<>();
    private volatile World world;

    public PocketWorldService(Plugin plugin) {
        this.plugin = Objects.requireNonNull(plugin, "plugin");
    }

    public void start() {
        if (!started.compareAndSet(false, true)) {
            return;
        }
        plugin.getServer().getPluginManager().registerEvents(this, plugin);
        accept(WormholesPlatform.getWorld(WORLD_KEY));
    }

    public Optional<World> world() {
        return Optional.ofNullable(world);
    }

    public CompletableFuture<World> whenReady() {
        return ready;
    }

    public static boolean isPocketWorld(World candidate) {
        return candidate != null && WORLD_KEY.equals(candidate.getKey());
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onWorldLoad(WorldLoadEvent event) {
        accept(event.getWorld());
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onWorldUnload(WorldUnloadEvent event) {
        if (isPocketWorld(event.getWorld())) {
            world = null;
            plugin.getLogger().warning("The Wormholes pocket dimension unloaded; dimensional doors will remain dormant until it loads again.");
        }
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onServerLoad(ServerLoadEvent event) {
        if (world().isEmpty()) {
            plugin.getLogger().severe("The bundled dimension wormholes:pockets was not loaded. Dimensional doors cannot provision or enter pocket spaces.");
        }
    }

    @Override
    public void close() {
        if (started.compareAndSet(true, false)) {
            HandlerList.unregisterAll(this);
        }
        world = null;
    }

    private void accept(World candidate) {
        if (!isPocketWorld(candidate)) {
            return;
        }
        world = candidate;
        ready.complete(candidate);
    }
}
