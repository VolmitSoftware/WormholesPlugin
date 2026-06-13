package art.arcane.wormholes;

import art.arcane.volmlib.integration.ReloadAware;
import art.arcane.volmlib.util.scheduling.FoliaScheduler;
import art.arcane.volmlib.util.scheduling.SchedulerBridge;
import art.arcane.volmlib.util.scheduling.SchedulerRuntime;
import art.arcane.wormholes.config.WormholesSettings;
import art.arcane.wormholes.network.ImportExportService;
import art.arcane.wormholes.network.NetworkManager;
import art.arcane.wormholes.network.NetworkRouter;
import art.arcane.wormholes.network.PlayerTransfer;
import art.arcane.wormholes.network.PortalSyncService;
import art.arcane.wormholes.network.RemotePortalRegistry;
import art.arcane.wormholes.network.TransferGate;
import art.arcane.wormholes.network.TraversalService;
import art.arcane.wormholes.network.view.RemoteViewCache;
import art.arcane.wormholes.network.view.ViewServer;
import art.arcane.wormholes.network.view.ViewSubscriptionManager;
import art.arcane.wormholes.util.J;
import art.arcane.wormholes.service.WormholesCommandService;
import art.arcane.wormholes.service.WormholesIntegrationService;
import art.arcane.wormholes.util.common.SplashScreen;
import art.arcane.wormholes.util.project.config.HotloadManager;
import com.github.retrooper.packetevents.PacketEvents;
import com.github.retrooper.packetevents.event.PacketListenerCommon;
import com.github.retrooper.packetevents.settings.PacketEventsSettings;
import io.github.retrooper.packetevents.factory.spigot.SpigotPacketEventsBuilder;
import net.kyori.adventure.platform.bukkit.BukkitAudiences;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.title.Title;
import org.bstats.bukkit.Metrics;
import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.HandlerList;
import org.bukkit.event.Listener;
import org.bukkit.event.player.AsyncPlayerChatEvent;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.IOException;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
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

    public static volatile WormholesSettings settings;
    private static volatile BukkitAudiences audiences;
    public static volatile BlockManager blockManager;
    public static volatile EffectManager effectManager;
    public static volatile ConstructionManager constructionManager;
    public static volatile PortalManager portalManager;
    public static volatile TraversableManager traversableManager;
    public static volatile ProjectionManager projectionManager;
    public static volatile NetworkManager networkManager;
    public static volatile RemotePortalRegistry remotePortalRegistry;
    public static volatile PortalSyncService portalSyncService;
    public static volatile TraversalService traversalService;
    public static volatile RemoteViewCache remoteViewCache;
    public static volatile ViewSubscriptionManager viewSubscriptions;
    public static volatile ViewServer viewServer;
    public static volatile ImportExportService importExportService;

    private static final ConcurrentHashMap<UUID, Consumer<String>> CHAT_INPUTS = new ConcurrentHashMap<>();

    private final AtomicBoolean alreadyDrained = new AtomicBoolean(false);
    private SchedulerRuntime schedulerRuntime;
    private Metrics metrics;
    private WormholesCommandService commandService;
    private WormholesIntegrationService integrationService;
    private HotloadManager hotloadManager;
    private PacketListenerCommon statusBridgeListener;
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

            audiences = BukkitAudiences.create(this);

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

            remotePortalRegistry = new RemotePortalRegistry();
            networkManager = new NetworkManager(getLogger(), settings.getNetwork(), Bukkit.getMinecraftVersion(), getPluginMeta().getVersion(), Bukkit.getPort(), getDataFolder().toPath());
            importExportService = new ImportExportService(networkManager);
            portalSyncService = new PortalSyncService(
                networkManager,
                () -> portalManager.getLocalPortals(),
                runnable -> FoliaScheduler.runGlobal(this, runnable)
            );
            traversalService = new TraversalService(networkManager);
            remoteViewCache = new RemoteViewCache();
            viewSubscriptions = new ViewSubscriptionManager(networkManager, remoteViewCache);
            viewServer = new ViewServer(networkManager);
            NetworkRouter networkRouter = new NetworkRouter(remotePortalRegistry, portalSyncService, traversalService, viewServer, remoteViewCache, viewSubscriptions);
            networkManager.setMessageSink(networkRouter::onMessage);
            networkManager.setPeerStateSink(networkRouter::onPeerState);
            registerStatusBridgeListener(networkManager);
            getServer().getPluginManager().registerEvents(traversalService, this);
            getServer().getPluginManager().registerEvents(viewServer, this);
            getServer().getMessenger().registerOutgoingPluginChannel(this, PlayerTransfer.PROXY_CHANNEL);
            PacketEvents.getAPI().getEventManager().registerListener(new TransferGate());
            networkManager.start();
            J.ar(() -> {
                ViewSubscriptionManager subscriptions = viewSubscriptions;
                if (subscriptions != null) {
                    subscriptions.sweep();
                }
                ViewServer activeViewServer = viewServer;
                if (activeViewServer != null) {
                    activeViewServer.syncGatewayTickets();
                }
            }, 100);

            commandService = new WormholesCommandService(this);
            commandService.register();

            integrationService = new WormholesIntegrationService();
            integrationService.register();

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
        unregisterIntegrationService();
        if (schedulerRuntime != null) {
            schedulerRuntime.cancelPluginTasks();
        }
        FoliaScheduler.cancelTasks(this);
        drain();
    }

    @Override
    public void onPreUnload(ReloadAware.PreUnloadReason reason) {
        getLogger().info("BileTools pre-unload hook fired (" + reason + "). Tearing down Wormholes managers and PacketEvents.");
        unregisterIntegrationService();
        if (schedulerRuntime != null) {
            schedulerRuntime.cancelPluginTasks();
        }
        FoliaScheduler.cancelTasks(this);
        drain();
    }

    private void unregisterIntegrationService() {
        if (integrationService != null) {
            integrationService.unregister();
            integrationService = null;
        }
    }

    public void reloadAll() {
        WormholesSettings reloaded = WormholesSettings.loadAll(getDataFolder().toPath());
        applyReloadedSettings(reloaded);
    }

    public int deleteAllPortalsNow() {
        PortalManager activePortalManager = portalManager;
        if (activePortalManager == null) {
            return 0;
        }
        return activePortalManager.deleteAllPortals();
    }

    public ResetResult resetEverythingNow() throws IOException {
        stopHotloadManager();
        int deletedPortals = deleteAllPortalsNow();
        resetNetworkRuntime();
        CHAT_INPUTS.clear();
        Path dataFolder = getDataFolder().toPath();
        deletePathTree(dataFolder.resolve("config"));
        deletePathTree(dataFolder.resolve("identity"));
        deletePathTree(dataFolder.resolve("routes"));
        deletePathTree(dataFolder.resolve("trust"));
        deletePathTree(dataFolder.resolve("portals"));
        WormholesSettings defaults = WormholesSettings.loadAll(dataFolder);
        settings = defaults;
        Settings.refresh(defaults);
        rebuildNetworkRuntime(defaults);
        WormholesCommandService activeService = commandService;
        if (activeService != null) {
            activeService.invalidateCache();
        }
        hotloadManager = new HotloadManager(getDataFolder().toPath(), getLogger(), this::onConfigHotReload);
        hotloadManager.start();
        return new ResetResult(deletedPortals);
    }

    private void onConfigHotReload(WormholesSettings reloaded) {
        applyReloadedSettings(reloaded);
    }

    private void applyReloadedSettings(WormholesSettings reloaded) {
        if (reloaded == null) {
            return;
        }
        settings = reloaded;
        Settings.refresh(reloaded);
        FoliaScheduler.runGlobal(this, () -> applyReloadedManagers(reloaded));
    }

    private void applyReloadedManagers(WormholesSettings reloaded) {
        if (settings != reloaded) {
            return;
        }
        ProjectionManager activeProjection = projectionManager;
        if (activeProjection != null) {
            try {
                activeProjection.onSettingsReloaded();
            } catch (Throwable ex) {
                getLogger().log(Level.WARNING, "ProjectionManager rejected hot-reload notification", ex);
            }
        }
        WormholesCommandService activeService = commandService;
        if (activeService != null) {
            try {
                activeService.invalidateCache();
            } catch (Throwable ex) {
                getLogger().log(Level.WARNING, "CommandService cache invalidation failed", ex);
            }
        }
        NetworkManager activeNetwork = networkManager;
        if (activeNetwork != null) {
            try {
                activeNetwork.applyConfig(reloaded.getNetwork());
            } catch (Throwable ex) {
                getLogger().log(Level.WARNING, "NetworkManager rejected hot-reload notification", ex);
            }
        }
        getLogger().info("Configuration hot-reloaded.");
    }

    private void resetNetworkRuntime() {
        unregisterStatusBridgeListener();
        if (viewServer != null) {
            try {
                viewServer.shutdown();
            } catch (Throwable ex) {
                getLogger().log(Level.WARNING, "Error during ViewServer reset", ex);
            }
            HandlerList.unregisterAll(viewServer);
        }
        if (traversalService != null) {
            HandlerList.unregisterAll(traversalService);
        }
        if (networkManager != null) {
            try {
                networkManager.stop();
            } catch (Throwable ex) {
                getLogger().log(Level.WARNING, "Error during NetworkManager reset", ex);
            }
        }
        remotePortalRegistry = new RemotePortalRegistry();
        portalSyncService = null;
        traversalService = null;
        remoteViewCache = new RemoteViewCache();
        viewSubscriptions = null;
        viewServer = null;
        importExportService = null;
        networkManager = null;
    }

    private void rebuildNetworkRuntime(WormholesSettings activeSettings) {
        remotePortalRegistry = new RemotePortalRegistry();
        networkManager = new NetworkManager(getLogger(), activeSettings.getNetwork(), Bukkit.getMinecraftVersion(), getPluginMeta().getVersion(), Bukkit.getPort(), getDataFolder().toPath());
        importExportService = new ImportExportService(networkManager);
        portalSyncService = new PortalSyncService(
            networkManager,
            () -> portalManager.getLocalPortals(),
            runnable -> FoliaScheduler.runGlobal(this, runnable)
        );
        traversalService = new TraversalService(networkManager);
        remoteViewCache = new RemoteViewCache();
        viewSubscriptions = new ViewSubscriptionManager(networkManager, remoteViewCache);
        viewServer = new ViewServer(networkManager);
        NetworkRouter networkRouter = new NetworkRouter(remotePortalRegistry, portalSyncService, traversalService, viewServer, remoteViewCache, viewSubscriptions);
        networkManager.setMessageSink(networkRouter::onMessage);
        networkManager.setPeerStateSink(networkRouter::onPeerState);
        registerStatusBridgeListener(networkManager);
        getServer().getPluginManager().registerEvents(traversalService, this);
        getServer().getPluginManager().registerEvents(viewServer, this);
        networkManager.start();
    }

    private void registerStatusBridgeListener(NetworkManager manager) {
        unregisterStatusBridgeListener();
        statusBridgeListener = PacketEvents.getAPI().getEventManager().registerListener(manager.statusBridge());
    }

    private void unregisterStatusBridgeListener() {
        PacketListenerCommon listener = statusBridgeListener;
        statusBridgeListener = null;
        if (listener != null && PacketEvents.getAPI() != null) {
            PacketEvents.getAPI().getEventManager().unregisterListener(listener);
        }
    }

    private void stopHotloadManager() {
        HotloadManager activeHotload = hotloadManager;
        hotloadManager = null;
        if (activeHotload != null) {
            activeHotload.stop();
        }
    }

    private static void deletePathTree(Path path) throws IOException {
        if (!Files.exists(path)) {
            return;
        }
        Files.walkFileTree(path, new SimpleFileVisitor<Path>() {
            @Override
            public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
                Files.deleteIfExists(file);
                return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult postVisitDirectory(Path dir, IOException exc) throws IOException {
                if (exc != null) {
                    throw exc;
                }
                Files.deleteIfExists(dir);
                return FileVisitResult.CONTINUE;
            }
        });
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

    public NetworkManager getNetworkManager() {
        return networkManager;
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

    public static BukkitAudiences audiences() {
        return audiences;
    }

    public static void sendActionBar(Player player, Component component) {
        BukkitAudiences a = audiences;
        if (a != null && player != null && component != null) {
            a.player(player).sendActionBar(component);
        }
    }

    public static void showTitle(Player player, Title title) {
        BukkitAudiences a = audiences;
        if (a != null && player != null && title != null) {
            a.player(player).showTitle(title);
        }
    }

    public static void sendMessage(CommandSender sender, Component component) {
        BukkitAudiences a = audiences;
        if (a != null && sender != null && component != null) {
            a.sender(sender).sendMessage(component);
        }
    }

    public static void awaitChatInput(Player player, Consumer<String> callback) {
        if (player == null || callback == null) {
            return;
        }
        CHAT_INPUTS.put(player.getUniqueId(), callback);
    }

    public record ResetResult(int deletedPortals) {
    }

    private static final class ChatInputListener implements Listener {
        @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
        public void onChat(AsyncPlayerChatEvent event) {
            UUID id = event.getPlayer().getUniqueId();
            Consumer<String> consumer = CHAT_INPUTS.remove(id);
            if (consumer == null) {
                return;
            }
            event.setCancelled(true);
            String text = event.getMessage();
            FoliaScheduler.runEntity(Wormholes.instance, event.getPlayer(), () -> consumer.accept(text));
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
            HandlerList.unregisterAll(this);
        } catch (Throwable ex) {
            getLogger().log(Level.WARNING, "Error unregistering plugin listeners", ex);
        }

        if (commandService != null) {
            try {
                commandService.invalidateCache();
            } catch (Throwable ex) {
                getLogger().log(Level.WARNING, "Error invalidating command service cache", ex);
            }
        }

        try {
            if (viewServer != null) {
                viewServer.shutdown();
            }
        } catch (Throwable ex) {
            getLogger().log(Level.WARNING, "Error during ViewServer shutdown", ex);
        }

        try {
            if (networkManager != null) {
                networkManager.stop();
            }
        } catch (Throwable ex) {
            getLogger().log(Level.WARNING, "Error during NetworkManager shutdown", ex);
        }

        try {
            unregisterStatusBridgeListener();
        } catch (Throwable ex) {
            getLogger().log(Level.WARNING, "Error unregistering status bridge listener", ex);
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
            getLogger().log(Level.WARNING, "Error during BlockManager teardown", ex);
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

        if (audiences != null) {
            try {
                audiences.close();
            } catch (Throwable ex) {
                getLogger().log(Level.WARNING, "Error closing Adventure audiences", ex);
            }
            audiences = null;
        }
    }
}
