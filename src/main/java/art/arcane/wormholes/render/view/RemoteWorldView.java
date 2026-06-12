package art.arcane.wormholes.render.view;

import art.arcane.wormholes.network.view.EntityVisual;
import art.arcane.wormholes.network.view.RemoteViewCache;
import art.arcane.wormholes.network.view.ViewBox;

import com.github.retrooper.packetevents.protocol.entity.data.EntityData;
import com.github.retrooper.packetevents.protocol.player.Equipment;

import org.bukkit.World;
import org.bukkit.block.data.BlockData;

import java.util.List;
import java.util.UUID;

public final class RemoteWorldView implements ProjectionWorldView {
    private final RemoteViewCache.RemoteView view;
    private final BlockData fallback;

    public RemoteWorldView(RemoteViewCache.RemoteView view, BlockData fallback) {
        this.view = view;
        this.fallback = fallback;
    }

    @Override
    public World getWorld() {
        return null;
    }

    @Override
    public int getMinHeight() {
        ViewBox box = view.getBox();
        return box == null ? 0 : box.minY();
    }

    @Override
    public int getMaxHeight() {
        ViewBox box = view.getBox();
        return box == null ? 0 : box.maxY() + 1;
    }

    @Override
    public BlockData sampleBlockData(int x, int y, int z) {
        ViewBox box = view.getBox();
        if (box == null) {
            return null;
        }
        if (!box.contains(x, y, z)) {
            return fallback;
        }
        RemoteViewCache.DecodedSlice slice = view.sliceAt(x, z);
        if (slice == null) {
            return null;
        }
        return slice.blockAt(x, y, z);
    }

    @Override
    public int getLight(int x, int y, int z) {
        ViewBox box = view.getBox();
        if (box == null || !box.contains(x, y, z)) {
            return LIGHT_UNAVAILABLE;
        }
        RemoteViewCache.DecodedSlice slice = view.sliceAt(x, z);
        if (slice == null) {
            return LIGHT_UNAVAILABLE;
        }
        return slice.lightAt(x, y, z);
    }

    public List<EntityVisual> getEntities() {
        return view.getEntities();
    }

    public RemoteViewCache.RemoteProfile getProfile(UUID entityId) {
        return view.getProfile(entityId);
    }

    public List<EntityData<?>> getMetadata(UUID entityId) {
        return view.getMetadata(entityId);
    }

    public List<Equipment> getEquipment(UUID entityId) {
        return view.getEquipment(entityId);
    }

    @Override
    public int getSkyDarken() {
        return view.getSkyDarken();
    }

    public long getRevision() {
        return view.getRevision();
    }

    public int getStateVersion(UUID entityId) {
        return view.getStateVersion(entityId);
    }

    @Override
    public boolean equals(Object other) {
        if (this == other) {
            return true;
        }
        if (!(other instanceof RemoteWorldView remote)) {
            return false;
        }
        return view.equals(remote.view);
    }

    @Override
    public int hashCode() {
        return view.hashCode();
    }
}
