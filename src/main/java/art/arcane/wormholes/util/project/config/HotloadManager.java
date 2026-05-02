package art.arcane.wormholes.util.project.config;

import art.arcane.wormholes.config.WormholesSettings;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;
import java.util.logging.Logger;

public final class HotloadManager {
    private static final long POLL_INTERVAL_MS = 250L;
    private static final String[] WATCHED_FILES = {"main.toml", "projection.toml", "render.toml", "advanced.toml"};

    private final Path dataFolder;
    private final Path configDir;
    private final Logger logger;
    private final Consumer<WormholesSettings> reloadCallback;
    private final AtomicBoolean running = new AtomicBoolean(false);
    private final Map<String, Long> lastModified = new HashMap<>();
    private Thread watcherThread;

    public HotloadManager(Path dataFolder, Logger logger, Consumer<WormholesSettings> reloadCallback) {
        this.dataFolder = dataFolder;
        this.configDir = dataFolder.resolve("config");
        this.logger = logger;
        this.reloadCallback = reloadCallback;
    }

    public void start() {
        if (!running.compareAndSet(false, true)) {
            return;
        }
        try {
            Files.createDirectories(configDir);
        } catch (Exception e) {
            logger.warning("[Hotload] Failed to create config directory " + configDir + ": " + e.getMessage());
        }
        captureBaseline();
        watcherThread = new Thread(this::pollLoop, "Wormholes-Hotload-Watcher");
        watcherThread.setDaemon(true);
        watcherThread.start();
        logger.info("[Hotload] Watching " + configDir + " for TOML changes (poll=" + POLL_INTERVAL_MS + "ms)");
    }

    public void stop() {
        if (!running.compareAndSet(true, false)) {
            return;
        }
        if (watcherThread != null) {
            watcherThread.interrupt();
            watcherThread = null;
        }
    }

    private void captureBaseline() {
        for (String name : WATCHED_FILES) {
            Path file = configDir.resolve(name);
            try {
                if (Files.exists(file)) {
                    lastModified.put(name, Files.getLastModifiedTime(file).toMillis());
                }
            } catch (Exception e) {
                logger.warning("[Hotload] Failed to read mtime for " + name + ": " + e.getMessage());
            }
        }
    }

    private void pollLoop() {
        while (running.get() && !Thread.currentThread().isInterrupted()) {
            try {
                Thread.sleep(POLL_INTERVAL_MS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            }
            checkForChanges();
        }
    }

    private void checkForChanges() {
        boolean changed = false;
        for (String name : WATCHED_FILES) {
            Path file = configDir.resolve(name);
            try {
                if (!Files.exists(file)) {
                    continue;
                }
                long current = Files.getLastModifiedTime(file).toMillis();
                Long previous = lastModified.get(name);
                if (previous == null || current > previous) {
                    lastModified.put(name, current);
                    if (previous != null) {
                        changed = true;
                    }
                }
            } catch (Exception e) {
                logger.warning("[Hotload] mtime check failed for " + name + ": " + e.getMessage());
            }
        }
        if (changed) {
            reloadAll();
        }
    }

    private void reloadAll() {
        try {
            WormholesSettings reloaded = WormholesSettings.loadAll(dataFolder);
            logger.info("[Hotload] Configuration reloaded.");
            reloadCallback.accept(reloaded);
        } catch (Exception e) {
            logger.warning("[Hotload] Failed to reload configuration: " + e.getMessage());
        } finally {
            captureBaseline();
        }
    }
}
