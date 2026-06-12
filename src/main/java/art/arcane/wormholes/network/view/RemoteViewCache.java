package art.arcane.wormholes.network.view;

import com.github.retrooper.packetevents.protocol.entity.data.EntityData;
import com.github.retrooper.packetevents.protocol.player.Equipment;

import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.block.data.BlockData;

import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

public final class RemoteViewCache {
    public record RemoteProfile(String name, String textureValue, String textureSignature) {
    }

    public static final class DecodedSlice {
        private final int minX;
        private final int minY;
        private final int minZ;
        private final int sizeX;
        private final int sizeY;
        private final int sizeZ;
        private final BlockData[] palette;
        private final short[] indices;
        private final byte[] light;

        private DecodedSlice(ViewSlice slice, BlockData[] palette) {
            this.minX = slice.minX();
            this.minY = slice.minY();
            this.minZ = slice.minZ();
            this.sizeX = slice.sizeX();
            this.sizeY = slice.sizeY();
            this.sizeZ = slice.sizeZ();
            this.palette = palette;
            this.indices = slice.indices();
            this.light = slice.light();
        }

        public BlockData blockAt(int x, int y, int z) {
            int index = (((y - minY) * sizeZ + (z - minZ)) * sizeX) + (x - minX);
            if (index < 0 || index >= indices.length) {
                return null;
            }
            int paletteIndex = indices[index] & 0xFFFF;
            return paletteIndex < palette.length ? palette[paletteIndex] : null;
        }

        public int lightAt(int x, int y, int z) {
            int index = (((y - minY) * sizeZ + (z - minZ)) * sizeX) + (x - minX);
            if (index < 0 || index >= light.length) {
                return -1;
            }
            return light[index] & 0xFF;
        }
    }

    public static final class RemoteView {
        private final String peerName;
        private final UUID portalId;
        private volatile ViewBox box;
        private volatile long lastUpdateMillis;
        private volatile long revision;
        private volatile List<EntityVisual> entities = List.of();
        private final Map<UUID, Integer> stateVersions = new ConcurrentHashMap<>();
        private final Map<Long, DecodedSlice> slices = new ConcurrentHashMap<>();
        private final Map<UUID, RemoteProfile> profiles = new ConcurrentHashMap<>();
        private final Map<UUID, List<EntityData<?>>> entityMetadata = new ConcurrentHashMap<>();
        private final Map<UUID, List<Equipment>> entityEquipment = new ConcurrentHashMap<>();

        private RemoteView(String peerName, UUID portalId) {
            this.peerName = peerName;
            this.portalId = portalId;
        }

        public String getPeerName() {
            return peerName;
        }

        public UUID getPortalId() {
            return portalId;
        }

        public ViewBox getBox() {
            return box;
        }

        public long getLastUpdateMillis() {
            return lastUpdateMillis;
        }

        public DecodedSlice sliceAt(int x, int z) {
            return slices.get(ViewSlice.columnKey(x >> 4, z >> 4));
        }

        public boolean hasData() {
            return box != null && !slices.isEmpty();
        }

        public List<EntityVisual> getEntities() {
            return entities;
        }

        public RemoteProfile getProfile(UUID entityId) {
            return profiles.get(entityId);
        }

        public List<EntityData<?>> getMetadata(UUID entityId) {
            return entityMetadata.get(entityId);
        }

        public List<Equipment> getEquipment(UUID entityId) {
            return entityEquipment.get(entityId);
        }

        public long getRevision() {
            return revision;
        }

        public int getStateVersion(UUID entityId) {
            Integer version = stateVersions.get(entityId);
            return version == null ? 0 : version;
        }

        @Override
        public boolean equals(Object other) {
            if (this == other) {
                return true;
            }
            if (!(other instanceof RemoteView view)) {
                return false;
            }
            return peerName.equals(view.peerName) && portalId.equals(view.portalId);
        }

        @Override
        public int hashCode() {
            return peerName.hashCode() * 31 + portalId.hashCode();
        }
    }

    private final Map<String, RemoteView> views = new ConcurrentHashMap<>();
    private final Map<String, BlockData> parsedBlockData = new ConcurrentHashMap<>();

    public RemoteView getOrCreate(String peerName, UUID portalId) {
        return views.computeIfAbsent(key(peerName, portalId), k -> new RemoteView(peerName, portalId));
    }

    public RemoteView get(String peerName, UUID portalId) {
        return views.get(key(peerName, portalId));
    }

    public void remove(String peerName, UUID portalId) {
        views.remove(key(peerName, portalId));
    }

    public void applySnapshot(String peerName, UUID portalId, ViewBox box, List<ViewSlice> slices) {
        RemoteView view = getOrCreate(peerName, portalId);
        view.box = box;
        view.slices.clear();
        for (ViewSlice slice : slices) {
            view.slices.put(slice.columnKey(), decode(slice));
        }
        view.revision++;
        view.lastUpdateMillis = System.currentTimeMillis();
    }

    public void applyDelta(String peerName, UUID portalId, List<ViewSlice> slices) {
        RemoteView view = views.get(key(peerName, portalId));
        if (view == null || view.box == null) {
            return;
        }
        for (ViewSlice slice : slices) {
            view.slices.put(slice.columnKey(), decode(slice));
        }
        view.lastUpdateMillis = System.currentTimeMillis();
    }

    public void applyEntities(String peerName, UUID portalId, List<EntityVisual> entities) {
        RemoteView view = views.get(key(peerName, portalId));
        if (view == null) {
            return;
        }
        Set<UUID> present = new HashSet<>(entities.size());
        for (EntityVisual visual : entities) {
            present.add(visual.id());
            if (visual.isPlayer() && visual.playerName() != null && !visual.playerName().isEmpty()) {
                RemoteProfile previous = view.profiles.get(visual.id());
                String textureValue = visual.hasTextures() ? visual.textureValue() : previous == null ? "" : previous.textureValue();
                String textureSignature = visual.hasTextures() ? visual.textureSignature() : previous == null ? "" : previous.textureSignature();
                view.profiles.put(visual.id(), new RemoteProfile(visual.playerName(), textureValue, textureSignature));
            }
            boolean stateChanged = false;
            if (visual.metadata() != null && visual.metadata().length > 0) {
                try {
                    view.entityMetadata.put(visual.id(), List.copyOf(PacketBlobs.readMetadata(visual.metadata())));
                    stateChanged = true;
                } catch (Throwable ignored) {
                }
            }
            if (visual.equipment() != null && visual.equipment().length > 0) {
                try {
                    view.entityEquipment.put(visual.id(), List.copyOf(PacketBlobs.readEquipment(visual.equipment())));
                    stateChanged = true;
                } catch (Throwable ignored) {
                }
            }
            if (stateChanged) {
                view.stateVersions.merge(visual.id(), 1, Integer::sum);
            }
        }
        view.profiles.keySet().retainAll(present);
        view.entityMetadata.keySet().retainAll(present);
        view.entityEquipment.keySet().retainAll(present);
        view.stateVersions.keySet().retainAll(present);
        view.entities = List.copyOf(entities);
        view.lastUpdateMillis = System.currentTimeMillis();
    }

    public void clear() {
        views.clear();
    }

    private DecodedSlice decode(ViewSlice slice) {
        BlockData[] palette = new BlockData[slice.palette().size()];
        for (int i = 0; i < palette.length; i++) {
            palette[i] = parseBlockData(slice.palette().get(i));
        }
        return new DecodedSlice(slice, palette);
    }

    private BlockData parseBlockData(String stateString) {
        BlockData cached = parsedBlockData.get(stateString);
        if (cached != null) {
            return cached;
        }
        BlockData parsed;
        try {
            parsed = Bukkit.createBlockData(stateString);
        } catch (IllegalArgumentException e) {
            parsed = Material.AIR.createBlockData();
        }
        if (parsedBlockData.size() < 65536) {
            parsedBlockData.put(stateString, parsed);
        }
        return parsed;
    }

    private static String key(String peerName, UUID portalId) {
        return peerName + ":" + portalId;
    }
}
