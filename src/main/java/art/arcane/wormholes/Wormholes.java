package art.arcane.wormholes;

import art.arcane.volmlib.integration.ReloadAware;
import art.arcane.volmlib.util.scheduling.FoliaScheduler;
import art.arcane.volmlib.util.scheduling.SchedulerBridge;
import art.arcane.volmlib.util.scheduling.SchedulerRuntime;
import art.arcane.wormholes.config.WormholesSettings;
import art.arcane.wormholes.door.DimensionalDoorManager;
import art.arcane.wormholes.door.DimensionalDoorRepository;
import art.arcane.wormholes.door.DoorStoreSnapshot;
import art.arcane.wormholes.network.ImportExportService;
import art.arcane.wormholes.network.NetworkManager;
import art.arcane.wormholes.network.NetworkRouter;
import art.arcane.wormholes.network.PlayerTransfer;
import art.arcane.wormholes.network.PortalSyncService;
import art.arcane.wormholes.network.RemotePortalRegistry;
import art.arcane.wormholes.network.TraversalService;
import art.arcane.wormholes.network.replication.capture.CaptureRuntime;
import art.arcane.wormholes.network.replication.capture.CaptureSettings;
import art.arcane.wormholes.network.view.RemoteViewCache;
import art.arcane.wormholes.network.view.ViewServer;
import art.arcane.wormholes.network.view.ViewSubscriptionManager;
import art.arcane.wormholes.portal.ArrivalWarmer;
import art.arcane.wormholes.portal.vanilla.VanillaPortalReplacer;
import art.arcane.wormholes.service.MetricsRuntime;
import art.arcane.wormholes.service.PacketEventsRuntime;
import art.arcane.wormholes.service.StatsSnapshotWriter;
import art.arcane.wormholes.service.WormholesAudience;
import art.arcane.wormholes.service.WormholesCommandService;
import art.arcane.wormholes.service.WormholesIntegrationService;
import art.arcane.wormholes.survival.doors.dimension.PocketWorldService;
import art.arcane.wormholes.util.J;
import art.arcane.wormholes.util.common.SplashScreen;
import art.arcane.wormholes.util.project.config.HotloadManager;
import io.github.slimjar.app.builder.SpigotApplicationBuilder;
import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.World;
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
import java.time.Duration;
import java.time.Instant;
import java.util.List;
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
    public static volatile BlockManager blockManager;
    public static volatile EffectManager effectManager;
    public static volatile ConstructionManager constructionManager;
    public static volatile WandSelectionManager wandSelectionManager;
    public static volatile PortalManager portalManager;
    public static volatile TraversableManager traversableManager;
    public static volatile ProjectionManager projectionManager;
    public static volatile art.arcane.wormholes.render.ProjectionWorldChangeTracker projectionChangeTracker;
    public static volatile ArrivalWarmer arrivalWarmer;
    public static volatile NetworkManager networkManager;
    public static volatile RemotePortalRegistry remotePortalRegistry;
    public static volatile PortalSyncService portalSyncService;
    public static volatile TraversalService traversalService;
    public static volatile RemoteViewCache remoteViewCache;
    public static volatile ViewSubscriptionManager viewSubscriptions;
    public static volatile ViewServer viewServer;
    public static volatile ImportExportService importExportService;
    public static volatile PocketWorldService pocketWorldService;
    public static volatile DimensionalDoorManager dimensionalDoorManager;

    private static final ConcurrentHashMap<UUID, Consumer<String>> CHAT_INPUTS = new ConcurrentHashMap<>();

    private final AtomicBoolean alreadyDrained = new AtomicBoolean(false);
    private final AtomicBoolean dimensionalDoorDisablePending = new AtomicBoolean(false);
    private SchedulerRuntime schedulerRuntime;
    private PacketEventsRuntime packetEventsRuntime;
    private MetricsRuntime metricsRuntime;
    private WormholesCommandService commandService;
    private WormholesIntegrationService integrationService;
    private HotloadManager hotloadManager;
    private StatsSnapshotWriter statsSnapshotWriter;
    private CaptureRuntime captureRuntime;
    private Instant pluginStartedAt;

    public Wormholes() {
        getLogger().info("Loading dependencies...");
        new SpigotApplicationBuilder(this)
            .build();
        getLogger().info("Dependencies loaded.");
    }

    @Override
    public void onLoad() {
        INSTANCE = this;
        instance = this;

        packetEvents().load();
    }

    @Override
    public void onEnable() {
        boolean success = true;
        String errorMessage = "";

        preloadPersistenceClasses();

        try {
            settings = WormholesSettings.loadAll(getDataFolder().toPath());
            Settings.refresh(settings);
            this.schedulerRuntime = installSchedulerBridge();

            packetEvents().init();
            WormholesAudience.start(this);

            blockManager = new BlockManager();
            effectManager = new EffectManager();
            constructionManager = new ConstructionManager();
            wandSelectionManager = new WandSelectionManager();
            portalManager = new PortalManager();
            traversableManager = new TraversableManager();
            projectionManager = new ProjectionManager();
            projectionChangeTracker = new art.arcane.wormholes.render.ProjectionWorldChangeTracker();
            arrivalWarmer = new ArrivalWarmer();
            applyDimensionalDoorSetting(settings);

            getServer().getPluginManager().registerEvents(blockManager, this);
            getServer().getPluginManager().registerEvents(effectManager, this);
            getServer().getPluginManager().registerEvents(constructionManager, this);
            getServer().getPluginManager().registerEvents(wandSelectionManager, this);
            getServer().getPluginManager().registerEvents(portalManager, this);
            getServer().getPluginManager().registerEvents(traversableManager, this);
            getServer().getPluginManager().registerEvents(projectionManager, this);
            getServer().getPluginManager().registerEvents(new art.arcane.wormholes.render.ProjectionChangeListener(projectionChangeTracker), this);
            VanillaPortalReplacer vanillaPortalReplacer = new VanillaPortalReplacer();
            getServer().getPluginManager().registerEvents(vanillaPortalReplacer, this);
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
            NetworkRouter networkRouter = new NetworkRouter(remotePortalRegistry, portalSyncService, traversalService, viewServer, remoteViewCache, viewSubscriptions, networkManager.getReplicationManager(), networkManager);
            networkManager.setMessageSink(networkRouter::onMessage);
            networkManager.setPeerStateSink(networkRouter::onPeerState);
            registerStatusBridgeListener(networkManager);
            getServer().getPluginManager().registerEvents(traversalService, this);
            getServer().getPluginManager().registerEvents(viewServer, this);
            getServer().getMessenger().registerOutgoingPluginChannel(this, PlayerTransfer.PROXY_CHANNEL);
            packetEvents().registerTransferGate();
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
            J.ar(() -> {
                ArrivalWarmer activeWarmer = arrivalWarmer;
                if (activeWarmer != null) {
                    activeWarmer.sweep();
                }
            }, 40);
            J.sr(vanillaPortalReplacer::validateDimensionalFrames, 40);

            commandService = new WormholesCommandService(this);
            commandService.register();

            integrationService = new WormholesIntegrationService();
            integrationService.register();

            hotloadManager = new HotloadManager(getDataFolder().toPath(), getLogger(), this::onConfigHotReload);
            hotloadManager.start();

            this.metricsRuntime = MetricsRuntime.start(this, BSTATS_PLUGIN_ID);
            this.pluginStartedAt = Instant.now();
            startStatsSnapshotWriter();
            startCaptureRuntime();
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
        shutdownDimensionalDoorsBeforeSchedulers();
        shutdownPocketWorldService();
        shutdownProjectionBeforeSchedulers();
        shutdownEffectsBeforeSchedulers();
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
        shutdownDimensionalDoorsBeforeSchedulers();
        shutdownPocketWorldService();
        shutdownProjectionBeforeSchedulers();
        shutdownEffectsBeforeSchedulers();
        if (schedulerRuntime != null) {
            schedulerRuntime.cancelPluginTasks();
        }
        FoliaScheduler.cancelTasks(this);
        drain();
    }

    private void shutdownProjectionBeforeSchedulers() {
        ProjectionManager activeProjection = projectionManager;
        if (activeProjection != null) {
            activeProjection.shutdown();
        }
    }

    private void shutdownPocketWorldService() {
        PocketWorldService activeService = pocketWorldService;
        pocketWorldService = null;
        if (activeService == null) {
            return;
        }
        try {
            activeService.close();
        } catch (Throwable ex) {
            getLogger().log(Level.WARNING, "Error during PocketWorldService shutdown", ex);
        }
    }

    private void shutdownDimensionalDoorsBeforeSchedulers() {
        DimensionalDoorManager activeManager = dimensionalDoorManager;
        dimensionalDoorManager = null;
        if (activeManager == null) {
            return;
        }
        try {
            activeManager.close();
        } catch (Throwable ex) {
            getLogger().log(Level.WARNING, "Error during Dimensional Doors shutdown", ex);
        }
    }

    private void startDimensionalDoorsSafely() {
        try {
            startDimensionalDoorsOrThrow();
        } catch (Throwable ex) {
            getLogger().log(Level.SEVERE,
                "Dimensional Doors could not start. Core Wormholes features will remain available.", ex);
        }
    }

    private void startDimensionalDoorsOrThrow() throws IOException {
        if (dimensionalDoorManager != null) {
            return;
        }
        PocketWorldService activePocketWorld = pocketWorldService;
        boolean createdPocketWorldService = false;
        if (activePocketWorld == null) {
            activePocketWorld = new PocketWorldService(this);
            try {
                activePocketWorld.start();
            } catch (RuntimeException ex) {
                try {
                    activePocketWorld.close();
                } catch (Throwable closeError) {
                    getLogger().log(Level.WARNING, "Error cleaning up a failed pocket-world service startup", closeError);
                }
                throw ex;
            }
            pocketWorldService = activePocketWorld;
            createdPocketWorldService = true;
        }

        DimensionalDoorManager manager = new DimensionalDoorManager(this, activePocketWorld);
        try {
            manager.start();
            dimensionalDoorManager = manager;
        } catch (IOException | RuntimeException ex) {
            try {
                manager.close();
            } catch (Throwable closeError) {
                getLogger().log(Level.WARNING, "Error cleaning up a failed Dimensional Doors startup", closeError);
            }
            if (createdPocketWorldService) {
                shutdownPocketWorldService();
            }
            throw ex;
        }
    }

    private void applyDimensionalDoorSetting(WormholesSettings activeSettings) {
        if (activeSettings.getMain().dimensionalDoorsEnabled) {
            dimensionalDoorDisablePending.set(false);
            DimensionalDoorManager activeManager = dimensionalDoorManager;
            if (activeManager != null) {
                activeManager.resumeEntries();
                return;
            }
            startDimensionalDoorsSafely();
            return;
        }
        DimensionalDoorManager activeManager = dimensionalDoorManager;
        if (activeManager == null) {
            dimensionalDoorDisablePending.set(false);
            shutdownPocketWorldService();
            return;
        }
        activeManager.beginDrain();
        if (!hasDimensionalDoorDrainWork(activeManager)) {
            dimensionalDoorDisablePending.set(false);
            finishDimensionalDoorDisable(activeManager);
            return;
        }
        if (dimensionalDoorDisablePending.compareAndSet(false, true)) {
            getLogger().warning("Dimensional Doors are draining current transits and pocket occupants before disabling.");
            notifyPocketOccupantsOfDisable();
            scheduleDimensionalDoorDisableCheck(activeSettings, activeManager);
        }
    }

    private void scheduleDimensionalDoorDisableCheck(
        WormholesSettings expectedSettings,
        DimensionalDoorManager expectedManager
    ) {
        boolean scheduled = FoliaScheduler.runGlobal(this, () -> {
            if (settings != expectedSettings
                || expectedSettings.getMain().dimensionalDoorsEnabled
                || dimensionalDoorManager != expectedManager) {
                dimensionalDoorDisablePending.set(false);
                return;
            }
            if (hasDimensionalDoorDrainWork(expectedManager)) {
                scheduleDimensionalDoorDisableCheck(expectedSettings, expectedManager);
                return;
            }
            dimensionalDoorDisablePending.set(false);
            finishDimensionalDoorDisable(expectedManager);
        }, 20L);
        if (!scheduled) {
            dimensionalDoorDisablePending.set(false);
            getLogger().warning("Could not schedule the Dimensional Doors disable drain check.");
        }
    }

    private boolean hasDimensionalDoorDrainWork(DimensionalDoorManager manager) {
        World pocketWorld = activePocketWorld();
        return manager.hasActiveTransits()
            || (pocketWorld != null && !pocketWorld.getPlayers().isEmpty());
    }

    private void notifyPocketOccupantsOfDisable() {
        World pocketWorld = activePocketWorld();
        if (pocketWorld == null) {
            return;
        }
        for (Player player : pocketWorld.getPlayers()) {
            FoliaScheduler.runEntity(this, player, () -> player.sendMessage(
                tag + "Dimensional Doors are being disabled. Leave through the pocket return door now."));
        }
    }

    private World activePocketWorld() {
        PocketWorldService activeService = pocketWorldService;
        return activeService == null
            ? Bukkit.getWorld(PocketWorldService.WORLD_KEY)
            : activeService.world().orElseGet(() -> Bukkit.getWorld(PocketWorldService.WORLD_KEY));
    }

    private void finishDimensionalDoorDisable(DimensionalDoorManager expectedManager) {
        if (dimensionalDoorManager != expectedManager) {
            return;
        }
        shutdownDimensionalDoorsBeforeSchedulers();
        shutdownPocketWorldService();
        getLogger().info("Dimensional Doors disabled; placed doors now behave as ordinary physical doors.");
    }

    private void shutdownEffectsBeforeSchedulers() {
        EffectManager activeEffects = effectManager;
        if (activeEffects != null) {
            activeEffects.shutdown();
        }
    }

    private void preloadPersistenceClasses() {
        ClassLoader loader = getClass().getClassLoader();
        String[] names = {
            "art.arcane.wormholes.util.JSONString",
            "art.arcane.wormholes.util.JSONObject",
            "art.arcane.wormholes.util.JSONArray",
            "art.arcane.wormholes.util.JSONStringer",
            "art.arcane.wormholes.util.JSONWriter",
            "art.arcane.wormholes.util.JSONTokener",
            "art.arcane.wormholes.util.JSONException"
        };
        for (String name : names) {
            try {
                Class.forName(name, true, loader);
            } catch (Throwable ignored) {
            }
        }
        try {
            art.arcane.wormholes.util.JSONObject warm = new art.arcane.wormholes.util.JSONObject();
            warm.put("warmup", true);
            warm.put("list", new art.arcane.wormholes.util.JSONArray().put(1));
            warm.toString();
        } catch (Throwable ignored) {
        }
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
        Path dataFolder = getDataFolder().toPath();
        DimensionalDoorManager resetManager = dimensionalDoorManager;
        boolean resumeEntries = resetManager != null && resetManager.beginDrain();
        World pocketWorld = activePocketWorld();
        if ((pocketWorld != null && !pocketWorld.getPlayers().isEmpty())
            || (resetManager != null && resetManager.hasActiveTransits())) {
            if (resumeEntries) {
                resetManager.resumeEntries();
            }
            throw new IOException("Cannot reset Wormholes while players are inside or transiting a pocket dimension");
        }
        long retiredPocketSlots = resetManager == null
            ? loadRetiredPocketSlots(dataFolder)
            : resetManager.state().snapshot().nextPocketSlot();
        dimensionalDoorDisablePending.set(false);
        shutdownDimensionalDoorsBeforeSchedulers();
        stopHotloadManager();
        int deletedPortals = deleteAllPortalsNow();
        resetNetworkRuntime();
        CHAT_INPUTS.clear();
        deletePathTree(dataFolder.resolve("config"));
        deletePathTree(dataFolder.resolve("identity"));
        deletePathTree(dataFolder.resolve("routes"));
        deletePathTree(dataFolder.resolve("trust"));
        deletePathTree(dataFolder.resolve("portals"));
        deletePathTree(dataFolder.resolve("doors"));
        DimensionalDoorRepository.under(dataFolder).save(new DoorStoreSnapshot(
            DoorStoreSnapshot.CURRENT_SCHEMA,
            retiredPocketSlots,
            List.of(),
            List.of(),
            List.of(),
            List.of()
        ));
        WormholesSettings defaults = WormholesSettings.loadAll(dataFolder);
        settings = defaults;
        Settings.refresh(defaults);
        if (defaults.getMain().dimensionalDoorsEnabled) {
            startDimensionalDoorsOrThrow();
        } else {
            shutdownPocketWorldService();
        }
        rebuildNetworkRuntime(defaults);
        WormholesCommandService activeService = commandService;
        if (activeService != null) {
            activeService.invalidateCache();
        }
        hotloadManager = new HotloadManager(getDataFolder().toPath(), getLogger(), this::onConfigHotReload);
        hotloadManager.start();
        return new ResetResult(deletedPortals);
    }

    private long loadRetiredPocketSlots(Path dataFolder) throws IOException {
        DimensionalDoorRepository repository = DimensionalDoorRepository.under(dataFolder);
        try {
            return repository.load().nextPocketSlot();
        } catch (IOException parseFailure) {
            long recovered = repository.recoverNextPocketSlot();
            getLogger().log(Level.WARNING,
                "Recovered the retired pocket slot from malformed Dimensional Doors state during reset.",
                parseFailure);
            return recovered;
        }
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
        applyDimensionalDoorSetting(reloaded);
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
        try {
            stopStatsSnapshotWriter();
            startStatsSnapshotWriter();
        } catch (Throwable ex) {
            getLogger().log(Level.WARNING, "StatsSnapshotWriter rejected hot-reload notification", ex);
        }
        try {
            CaptureRuntime activeCapture = captureRuntime;
            if (!reloaded.getNetwork().enabled) {
                stopCaptureRuntime();
            } else if (activeCapture == null) {
                startCaptureRuntime();
            } else {
                activeCapture.applySettings(reloaded.getNetwork());
            }
        } catch (Throwable ex) {
            getLogger().log(Level.WARNING, "CaptureRuntime rejected hot-reload notification", ex);
        }
        getLogger().info("Configuration hot-reloaded.");
    }

    private void resetNetworkRuntime() {
        unregisterStatusBridgeListener();
        stopCaptureRuntime();
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
        NetworkRouter networkRouter = new NetworkRouter(remotePortalRegistry, portalSyncService, traversalService, viewServer, remoteViewCache, viewSubscriptions, networkManager.getReplicationManager(), networkManager);
        networkManager.setMessageSink(networkRouter::onMessage);
        networkManager.setPeerStateSink(networkRouter::onPeerState);
        registerStatusBridgeListener(networkManager);
        getServer().getPluginManager().registerEvents(traversalService, this);
        getServer().getPluginManager().registerEvents(viewServer, this);
        networkManager.start();
        startCaptureRuntime();
    }

    private void registerStatusBridgeListener(NetworkManager manager) {
        packetEvents().registerStatusBridge(manager);
    }

    private void unregisterStatusBridgeListener() {
        PacketEventsRuntime runtime = packetEventsRuntime;
        if (runtime != null) {
            runtime.unregisterStatusBridge();
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

    public PocketWorldService getPocketWorldService() {
        return pocketWorldService;
    }

    public DimensionalDoorManager getDimensionalDoorManager() {
        return dimensionalDoorManager;
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

    public static void i(String message) {
        if (instance == null) {
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
            stopStatsSnapshotWriter();
        } catch (Throwable ex) {
            getLogger().log(Level.WARNING, "Error during StatsSnapshotWriter stop", ex);
        }

        try {
            stopCaptureRuntime();
        } catch (Throwable ex) {
            getLogger().log(Level.WARNING, "Error during CaptureRuntime stop", ex);
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
                commandService.close();
                commandService = null;
            } catch (Throwable ex) {
                getLogger().log(Level.WARNING, "Error closing command service", ex);
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
            if (arrivalWarmer != null) {
                arrivalWarmer.shutdown();
            }
        } catch (Throwable ex) {
            getLogger().log(Level.WARNING, "Error during ArrivalWarmer shutdown", ex);
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

        PacketEventsRuntime activePacketEvents = packetEventsRuntime;
        if (activePacketEvents != null) {
            activePacketEvents.terminate();
        }

        MetricsRuntime activeMetrics = metricsRuntime;
        metricsRuntime = null;
        if (activeMetrics != null) {
            activeMetrics.shutdown();
        }

        WormholesAudience.stop(getLogger());
    }

    public StatsSnapshotWriter getStatsSnapshotWriter() {
        return statsSnapshotWriter;
    }

    private void startCaptureRuntime() {
        if (captureRuntime != null) {
            return;
        }
        if (networkManager == null) {
            return;
        }
        if (settings == null || settings.getNetwork() == null || !settings.getNetwork().enabled) {
            return;
        }
        CaptureSettings initial = settings == null ? CaptureSettings.defaults() : CaptureSettings.from(settings.getNetwork());
        CaptureRuntime runtime = new CaptureRuntime(this, getLogger(), networkManager.getReplicationManager(), networkManager.getReplicationManager(), initial);
        runtime.start();
        captureRuntime = runtime;
    }

    private void stopCaptureRuntime() {
        CaptureRuntime runtime = captureRuntime;
        captureRuntime = null;
        if (runtime != null) {
            runtime.stop();
        }
    }

    public CaptureRuntime getCaptureRuntime() {
        return captureRuntime;
    }

    private void startStatsSnapshotWriter() {
        if (statsSnapshotWriter != null) {
            return;
        }
        if (settings == null) {
            return;
        }
        if (settings.getNetwork() == null || settings.getNetwork().stats == null) {
            return;
        }
        if (!settings.getNetwork().stats.enabled) {
            return;
        }
        int intervalSec = Math.max(1, settings.getNetwork().stats.intervalSec);
        Path output = resolveStatsOutputPath(settings.getNetwork().stats.pathOverride);
        StatsSnapshotWriter writer = StatsSnapshotWriter.forRuntime(
            this,
            networkManager,
            viewServer,
            traversalService,
            null,
            output,
            Duration.ofSeconds(intervalSec),
            pluginStartedAt == null ? Instant.now() : pluginStartedAt
        );
        writer.start();
        statsSnapshotWriter = writer;
    }

    private void stopStatsSnapshotWriter() {
        StatsSnapshotWriter writer = statsSnapshotWriter;
        statsSnapshotWriter = null;
        if (writer != null) {
            writer.stop();
        }
    }

    private Path resolveStatsOutputPath(String override) {
        if (override == null || override.isBlank()) {
            return getDataFolder().toPath().resolve("wormholes-stats.txt");
        }
        Path path = Path.of(override);
        if (path.isAbsolute()) {
            return path;
        }
        return getDataFolder().toPath().resolve(override);
    }

    private PacketEventsRuntime packetEvents() {
        PacketEventsRuntime runtime = packetEventsRuntime;
        if (runtime == null) {
            runtime = new PacketEventsRuntime(this);
            packetEventsRuntime = runtime;
        }
        return runtime;
    }
}
