package art.arcane.wormholes;

import art.arcane.volmlib.integration.ReloadAware;
import art.arcane.volmlib.util.scheduling.FoliaScheduler;
import art.arcane.volmlib.util.scheduling.SchedulerBridge;
import art.arcane.volmlib.util.scheduling.SchedulerRuntime;
import art.arcane.wormholes.config.WormholesSettings;
import art.arcane.wormholes.service.WormholesCommandService;
import art.arcane.wormholes.util.common.SplashScreen;
import art.arcane.wormholes.util.project.config.HotloadManager;
import com.github.retrooper.packetevents.PacketEvents;
import com.github.retrooper.packetevents.settings.PacketEventsSettings;
import io.github.retrooper.packetevents.factory.spigot.SpigotPacketEventsBuilder;
import io.papermc.paper.event.player.AsyncChatEvent;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.bstats.bukkit.Metrics;
import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.HandlerList;
import org.bukkit.event.Listener;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;
import java.util.logging.Level;

public final class Wormholes extends JavaPlugin implements ReloadAware {
    private static final int BSTATS_PLUGIN_ID = 27950;

    public static Wormholes INSTANCE;
    public static Wormholes instance;
    public static String tag = ChatColor.DARK_GRAY + "[" + ChatColor.GRAY + "Wormholes" + ChatColor.DARK_GRAY + "] " + ChatColor.GRAY;

    public static WormholesSettings settings;
    public static BlockManager blockManager;
    public static EffectManager effectManager;
    public static ConstructionManager constructionManager;
    public static PortalManager portalManager;
    public static TraversableManager traversableManager;
    public static ProjectionManager projectionManager;

    private static final ConcurrentHashMap<UUID, Consumer<String>> CHAT_INPUTS = new ConcurrentHashMap<>();

    private final AtomicBoolean alreadyDrained = new AtomicBoolean(false);
    private SchedulerRuntime schedulerRuntime;
    private Metrics metrics;
    private WormholesCommandService commandService;
    private HotloadManager hotloadManager;
    private boolean packetEventsLoaded = false;

    @Override
    public void onLoad() {
        INSTANCE = this;
        instance = this;

        SpigotPacketEventsBuilder.clearBuildCache();
        PacketEventsSettings packetEventsSettings = new PacketEventsSettings()
            .checkForUpdates(false);
        PacketEvents.setAPI(SpigotPacketEventsBuilder.buildNoCache(this, packetEventsSettings));
        PacketEvents.getAPI().load();
    }

    @Override
    public void onEnable() {
        boolean success = true;
        String errorMessage = "";

        try {
            settings = WormholesSettings.loadAll(getDataFolder().toPath());
            Settings.refresh(settings);
            this.schedulerRuntime = installSchedulerBridge();

            PacketEvents.getAPI().init();
            packetEventsLoaded = true;

            blockManager = new BlockManager();
            effectManager = new EffectManager();
            constructionManager = new ConstructionManager();
            portalManager = new PortalManager();
            traversableManager = new TraversableManager();
            projectionManager = new ProjectionManager();

            getServer().getPluginManager().registerEvents(blockManager, this);
            getServer().getPluginManager().registerEvents(effectManager, this);
            getServer().getPluginManager().registerEvents(constructionManager, this);
            getServer().getPluginManager().registerEvents(portalManager, this);
            getServer().getPluginManager().registerEvents(traversableManager, this);
            getServer().getPluginManager().registerEvents(projectionManager, this);
            getServer().getPluginManager().registerEvents(new ChatInputListener(), this);

            commandService = new WormholesCommandService(this);
            commandService.register();

            hotloadManager = new HotloadManager(getDataFolder().toPath(), getLogger(), this::onConfigHotReload);
            hotloadManager.start();

            this.metrics = new Metrics(this, BSTATS_PLUGIN_ID);
        } catch (Exception ex) {
            success = false;
            errorMessage = ex.getMessage() == null ? ex.getClass().getSimpleName() : ex.getMessage();
            getLogger().log(Level.SEVERE, "Error enabling plugin", ex);
        }

        SplashScreen.print(this, success, errorMessage);
    }

    @Override
    public void onDisable() {
        if (schedulerRuntime != null) {
            schedulerRuntime.cancelPluginTasks();
        }
        FoliaScheduler.cancelTasks(this);
        drain();
    }

    @Override
    public void onPreUnload(ReloadAware.PreUnloadReason reason) {
        getLogger().info("BileTools pre-unload hook fired (" + reason + "). Tearing down Wormholes managers and PacketEvents.");
        if (schedulerRuntime != null) {
            schedulerRuntime.cancelPluginTasks();
        }
        FoliaScheduler.cancelTasks(this);
        drain();
    }

    public void reloadAll() {
        settings = WormholesSettings.loadAll(getDataFolder().toPath());
        Settings.refresh(settings);
        if (projectionManager != null) {
            projectionManager.onSettingsReloaded();
        }
    }

    private void onConfigHotReload(WormholesSettings reloaded) {
        settings = reloaded;
        Settings.refresh(reloaded);
        if (projectionManager != null) {
            projectionManager.onSettingsReloaded();
        }
        if (commandService != null) {
            commandService.invalidateCache();
        }
        getLogger().info("Configuration hot-reloaded.");
    }

    public WormholesSettings getSettings() {
        return settings;
    }

    public SchedulerRuntime getSchedulerRuntime() {
        return schedulerRuntime;
    }

    public BlockManager getBlockManager() {
        return blockManager;
    }

    public EffectManager getEffectManager() {
        return effectManager;
    }

    public ConstructionManager getConstructionManager() {
        return constructionManager;
    }

    public PortalManager getPortalManager() {
        return portalManager;
    }

    public TraversableManager getTraversableManager() {
        return traversableManager;
    }

    public ProjectionManager getProjectionManager() {
        return projectionManager;
    }

    public void registerListener(Listener listener) {
        getServer().getPluginManager().registerEvents(listener, this);
    }

    public void unregisterListener(Listener listener) {
        HandlerList.unregisterAll(listener);
    }

    public static void v(String message) {
        if (instance == null) {
            return;
        }
        if (!Settings.DEBUG) {
            return;
        }
        instance.getLogger().info(message);
    }

    public static void w(String message) {
        if (instance == null) {
            return;
        }
        instance.getLogger().warning(message);
    }

    public static void f(String message) {
        if (instance == null) {
            return;
        }
        instance.getLogger().severe(message);
    }

    public static void awaitChatInput(Player player, Consumer<String> callback) {
        if (player == null || callback == null) {
            return;
        }
        CHAT_INPUTS.put(player.getUniqueId(), callback);
    }

    private static final class ChatInputListener implements Listener {
        @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
        public void onChat(AsyncChatEvent event) {
            UUID id = event.getPlayer().getUniqueId();
            Consumer<String> consumer = CHAT_INPUTS.remove(id);
            if (consumer == null) {
                return;
            }
            event.setCancelled(true);
            String text = PlainTextComponentSerializer.plainText().serialize(event.message());
            Bukkit.getScheduler().runTask(Wormholes.instance, () -> consumer.accept(text));
        }
    }

    private SchedulerRuntime installSchedulerBridge() {
        SchedulerRuntime runtime = new SchedulerRuntime(
            () -> this,
            runnable -> FoliaScheduler.runAsync(this, runnable),
            message -> getLogger().fine(message),
            message -> getLogger().warning(message),
            throwable -> getLogger().log(Level.SEVERE, "Wormholes scheduler error", throwable)
        );

        SchedulerBridge.setSyncScheduler(runtime::s);
        SchedulerBridge.setDelayedSyncScheduler(runtime::s);
        SchedulerBridge.setAsyncScheduler(runnable -> runtime.a(runnable, 0));
        SchedulerBridge.setDelayedAsyncScheduler(runtime::a);
        SchedulerBridge.setSyncRepeatingScheduler(runtime::sr);
        SchedulerBridge.setAsyncRepeatingScheduler(runtime::ar);
        SchedulerBridge.setCancelScheduler(runtime::csr);
        SchedulerBridge.setErrorHandler(throwable -> getLogger().log(Level.SEVERE, "Wormholes scheduler error", throwable));
        SchedulerBridge.setInfoLogger(message -> getLogger().info(message));
        return runtime;
    }

    private void drain() {
        if (!alreadyDrained.compareAndSet(false, true)) {
            return;
        }

        try {
            if (hotloadManager != null) {
                hotloadManager.stop();
                hotloadManager = null;
            }
        } catch (Throwable ex) {
            getLogger().log(Level.WARNING, "Error during HotloadManager stop", ex);
        }

        try {
            if (projectionManager != null) {
                projectionManager.shutdown();
            }
        } catch (Throwable ex) {
            getLogger().log(Level.WARNING, "Error during ProjectionManager shutdown", ex);
        }

        try {
            if (portalManager != null) {
                portalManager.shutDown();
            }
        } catch (Throwable ex) {
            getLogger().log(Level.WARNING, "Error during PortalManager shutdown", ex);
        }

        try {
            if (blockManager != null) {
                blockManager.destroyAll();
            }
        } catch (Throwable ex) {
            getLogger().log(Level.WARNING, "Error during BlockManager destroyAll", ex);
        }

        if (packetEventsLoaded && PacketEvents.getAPI() != null) {
            try {
                PacketEvents.getAPI().terminate();
            } catch (Throwable ex) {
                getLogger().log(Level.WARNING, "Error during PacketEvents shutdown", ex);
            }
        }
        SpigotPacketEventsBuilder.clearBuildCache();
        packetEventsLoaded = false;

        if (metrics != null) {
            try {
                metrics.shutdown();
            } catch (Throwable ex) {
                getLogger().log(Level.WARNING, "Error during bStats shutdown", ex);
            }
            metrics = null;
        }
    }
}
