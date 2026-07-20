package art.arcane.wormholes.service;

import art.arcane.wormholes.Wormholes;
import art.arcane.wormholes.network.NetworkManager;
import art.arcane.wormholes.network.TransferGate;
import art.arcane.wormholes.render.ProjectionClientChunkTracker;
import com.github.retrooper.packetevents.PacketEvents;
import com.github.retrooper.packetevents.event.PacketListenerCommon;
import com.github.retrooper.packetevents.settings.PacketEventsSettings;
import io.github.retrooper.packetevents.factory.spigot.SpigotPacketEventsBuilder;

import java.util.Objects;
import java.util.logging.Level;

public final class PacketEventsRuntime {
    private final Wormholes plugin;
    private Object statusBridgeListener;
    private PacketListenerCommon projectionChunkListener;
    private ProjectionClientChunkTracker projectionChunkTracker;
    private boolean loaded;

    public PacketEventsRuntime(Wormholes plugin) {
        this.plugin = Objects.requireNonNull(plugin);
    }

    public void load() {
        SpigotPacketEventsBuilder.clearBuildCache();
        PacketEventsSettings packetEventsSettings = new PacketEventsSettings()
            .checkForUpdates(false);
        PacketEvents.setAPI(SpigotPacketEventsBuilder.buildNoCache(plugin, packetEventsSettings));
        PacketEvents.getAPI().load();
    }

    public void init() {
        PacketEvents.getAPI().init();
        loaded = true;
        projectionChunkTracker = new ProjectionClientChunkTracker();
        projectionChunkListener = PacketEvents.getAPI().getEventManager().registerListener(projectionChunkTracker);
    }

    public ProjectionClientChunkTracker projectionChunkTracker() {
        ProjectionClientChunkTracker tracker = projectionChunkTracker;
        if (tracker == null) {
            throw new IllegalStateException("Projection chunk tracker is not initialized");
        }
        return tracker;
    }

    public void registerTransferGate() {
        PacketEvents.getAPI().getEventManager().registerListener(new TransferGate());
    }

    public void registerStatusBridge(NetworkManager manager) {
        unregisterStatusBridge();
        statusBridgeListener = PacketEvents.getAPI().getEventManager().registerListener(manager.statusBridge());
    }

    public void unregisterStatusBridge() {
        Object listener = statusBridgeListener;
        statusBridgeListener = null;
        if (listener instanceof PacketListenerCommon && PacketEvents.getAPI() != null) {
            PacketListenerCommon packetListener = (PacketListenerCommon) listener;
            PacketEvents.getAPI().getEventManager().unregisterListener(packetListener);
        }
    }

    public void terminate() {
        if (loaded && PacketEvents.getAPI() != null) {
            try {
                PacketListenerCommon chunkListener = projectionChunkListener;
                projectionChunkListener = null;
                if (chunkListener != null) {
                    PacketEvents.getAPI().getEventManager().unregisterListener(chunkListener);
                }
                ProjectionClientChunkTracker chunkTracker = projectionChunkTracker;
                projectionChunkTracker = null;
                if (chunkTracker != null) {
                    chunkTracker.clear();
                }
                PacketEvents.getAPI().terminate();
            } catch (Throwable ex) {
                plugin.getLogger().log(Level.WARNING, "Error during PacketEvents shutdown", ex);
            }
        }
        SpigotPacketEventsBuilder.clearBuildCache();
        loaded = false;
    }
}
