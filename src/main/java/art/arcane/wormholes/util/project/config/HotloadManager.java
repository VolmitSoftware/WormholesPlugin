package art.arcane.wormholes.util.project.config;

import art.arcane.wormholes.config.WormholesSettings;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;
import java.util.logging.Logger;

public final class HotloadManager {
    private static final long POLL_INTERVAL_MS = 200L;
    private static final long STABILITY_WINDOW_MS = 350L;
    private static final long STOP_JOIN_TIMEOUT_MS = 2_000L;
    private static final String[] WATCHED_FILES = {"main.toml", "projection.toml", "render.toml", "advanced.toml"};

    private final Path dataFolder;
    private final Path configDir;
    private final Logger logger;
    private final Consumer<WormholesSettings> reloadCallback;
    private final AtomicBoolean running = new AtomicBoolean(false);
    private final Map<String, FileSignature> lastApplied = new HashMap<>();
    private final Map<String, PendingChange> pending = new HashMap<>();
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
        Thread thread = new Thread(this::pollLoop, "Wormholes-Hotload-Watcher");
        thread.setDaemon(true);
        watcherThread = thread;
        thread.start();
        logger.info("[Hotload] Watching " + configDir + " for TOML changes (poll=" + POLL_INTERVAL_MS + "ms, stability=" + STABILITY_WINDOW_MS + "ms)");
    }

    public void stop() {
        if (!running.compareAndSet(true, false)) {
            return;
        }
        Thread thread = watcherThread;
        watcherThread = null;
        if (thread == null) {
            return;
        }
        thread.interrupt();
        try {
            thread.join(STOP_JOIN_TIMEOUT_MS);
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
        }
        if (thread.isAlive()) {
            logger.warning("[Hotload] Watcher thread did not exit within " + STOP_JOIN_TIMEOUT_MS + "ms; abandoning");
        }
    }

    private void captureBaseline() {
        for (String name : WATCHED_FILES) {
            Path file = configDir.resolve(name);
            FileSignature signature = readSignature(name, file);
            if (signature != null) {
                lastApplied.put(name, signature);
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
            if (!running.get()) {
                return;
            }
            checkForChanges();
        }
    }

    private void checkForChanges() {
        long now = System.currentTimeMillis();
        Set<String> stableChangedFiles = new HashSet<>();

        for (String name : WATCHED_FILES) {
            Path file = configDir.resolve(name);
            if (!Files.exists(file)) {
                pending.remove(name);
                continue;
            }
            FileSignature current = readSignature(name, file);
            if (current == null) {
                continue;
            }
            FileSignature applied = lastApplied.get(name);
            if (applied != null && current.matches(applied)) {
                pending.remove(name);
                continue;
            }
            PendingChange existing = pending.get(name);
            if (existing == null || !existing.signature.matches(current)) {
                pending.put(name, new PendingChange(current, now));
                continue;
            }
            if (now - existing.firstSeenMillis < STABILITY_WINDOW_MS) {
                continue;
            }
            stableChangedFiles.add(name);
        }

        if (stableChangedFiles.isEmpty()) {
            return;
        }
        if (!running.get()) {
            return;
        }
        reloadAll(stableChangedFiles);
    }

    private void reloadAll(Set<String> changedFiles) {
        try {
            WormholesSettings reloaded = WormholesSettings.loadAll(dataFolder);
            if (!running.get()) {
                return;
            }
            logger.info("[Hotload] Configuration reloaded: " + String.join(", ", changedFiles));
            reloadCallback.accept(reloaded);
        } catch (Exception e) {
            logger.warning("[Hotload] Failed to reload configuration: " + e.getMessage());
        } finally {
            for (String name : WATCHED_FILES) {
                Path file = configDir.resolve(name);
                FileSignature signature = readSignature(name, file);
                if (signature != null) {
                    lastApplied.put(name, signature);
                }
            }
            pending.clear();
        }
    }

    private FileSignature readSignature(String name, Path file) {
        try {
            if (!Files.exists(file)) {
                return null;
            }
            long mtime = Files.getLastModifiedTime(file).toMillis();
            long size = Files.size(file);
            return new FileSignature(mtime, size);
        } catch (Exception e) {
            logger.warning("[Hotload] mtime/size check failed for " + name + ": " + e.getMessage());
            return null;
        }
    }

    private static final class FileSignature {
        private final long modifiedMillis;
        private final long size;

        private FileSignature(long modifiedMillis, long size) {
            this.modifiedMillis = modifiedMillis;
            this.size = size;
        }

        private boolean matches(FileSignature other) {
            return other != null && this.modifiedMillis == other.modifiedMillis && this.size == other.size;
        }
    }

    private static final class PendingChange {
        private final FileSignature signature;
        private final long firstSeenMillis;

        private PendingChange(FileSignature signature, long firstSeenMillis) {
            this.signature = signature;
            this.firstSeenMillis = firstSeenMillis;
        }
    }
}
