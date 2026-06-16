package art.arcane.wormholes.network.view;

import art.arcane.wormholes.network.replication.ChunkBulk;
import art.arcane.wormholes.network.replication.ChunkDiffBatch;
import art.arcane.wormholes.network.replication.RemoteChunkStore;

import com.github.retrooper.packetevents.protocol.entity.data.EntityData;
import com.github.retrooper.packetevents.protocol.player.Equipment;

import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.block.data.BlockData;

import java.io.IOException;
import java.util.ArrayList;
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
        private final String[] biomePalette;
        private final short[] biomes;

        private DecodedSlice(ViewSlice slice, BlockData[] palette, String[] biomePalette) {
            this.minX = slice.minX();
            this.minY = slice.minY();
            this.minZ = slice.minZ();
            this.sizeX = slice.sizeX();
            this.sizeY = slice.sizeY();
            this.sizeZ = slice.sizeZ();
            this.palette = palette;
            this.indices = slice.indices();
            this.light = slice.light();
            this.biomePalette = biomePalette;
            this.biomes = slice.biomes();
        }

        public BlockData blockAt(int x, int y, int z) {
            int index = (((y - minY) * sizeZ + (z - minZ)) * sizeX) + (x - minX);
            if (index < 0 || index >= indices.length) {
                return null;
            }
            int paletteIndex = indices[index] & 0xFFFF;
            return paletteIndex < palette.length ? palette[paletteIndex] : null;
        }

        public String biomeAt(int x, int y, int z) {
            int index = (((y - minY) * sizeZ + (z - minZ)) * sizeX) + (x - minX);
            if (index < 0 || index >= biomes.length) {
                return null;
            }
            int paletteIndex = biomes[index] & 0xFFFF;
            return paletteIndex < biomePalette.length ? biomePalette[paletteIndex] : null;
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
        private volatile int skyDarken;
        private volatile boolean viewReady;
        private volatile List<EntityVisual> entities = List.of();
        private final Map<UUID, EntityVisual> lastEntityState = new ConcurrentHashMap<>();
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

        public int getSkyDarken() {
            return skyDarken;
        }

        public boolean isViewReady() {
            return viewReady;
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
    private final Map<String, RemoteChunkStore> chunkStores = new ConcurrentHashMap<>();

    public RemoteChunkStore chunkStore(String peerName) {
        return chunkStores.computeIfAbsent(peerName, ignored -> new RemoteChunkStore());
    }

    public RemoteChunkStore chunkStoreIfPresent(String peerName) {
        return chunkStores.get(peerName);
    }

    public void clearChunkStore(String peerName) {
        chunkStores.remove(peerName);
    }

    public void applyChunkBulk(String peerName, List<ChunkBulk> bulks) {
        if (bulks == null || bulks.isEmpty()) {
            return;
        }
        RemoteChunkStore store = chunkStore(peerName);
        for (ChunkBulk bulk : bulks) {
            try {
                RemoteChunkStore.ReplicatedChunk chunk = store.applyBulk(bulk);
                publishSliceToViews(peerName, chunk);
            } catch (IOException ignored) {
            }
        }
    }

    public List<RemoteChunkStore.ApplyOutcome> applyChunkDiff(String peerName, List<ChunkDiffBatch> batches) {
        if (batches == null || batches.isEmpty()) {
            return List.of();
        }
        RemoteChunkStore store = chunkStore(peerName);
        List<RemoteChunkStore.ApplyOutcome> outcomes = new ArrayList<>(batches.size());
        for (ChunkDiffBatch batch : batches) {
            RemoteChunkStore.ApplyOutcome outcome = store.applyDiff(batch);
            outcomes.add(outcome);
            if (outcome.applied()) {
                RemoteChunkStore.ReplicatedChunk chunk = store.get(batch.chunkKey());
                if (chunk != null) {
                    publishSliceToViews(peerName, chunk);
                }
            }
        }
        return outcomes;
    }

    private void publishSliceToViews(String peerName, RemoteChunkStore.ReplicatedChunk chunk) {
        if (chunk == null) {
            return;
        }
        ViewSlice slice = chunk.slice();
        if (slice == null) {
            return;
        }
        long columnKey = chunk.chunkKey();
        DecodedSlice decoded = decode(slice);
        for (RemoteView view : views.values()) {
            if (!view.peerName.equals(peerName)) {
                continue;
            }
            ViewBox existing = view.box;
            ViewBox sliceBox = new ViewBox(slice.minX(), slice.minY(), slice.minZ(),
                slice.minX() + slice.sizeX() - 1, slice.minY() + slice.sizeY() - 1, slice.minZ() + slice.sizeZ() - 1);
            if (existing == null) {
                view.box = sliceBox;
            } else if (!columnIntersectsBox(columnKey, existing)) {
                view.box = unionBoxes(existing, sliceBox);
            }
            view.slices.put(columnKey, decoded);
            view.revision++;
            view.lastUpdateMillis = System.currentTimeMillis();
        }
    }

    private static ViewBox unionBoxes(ViewBox a, ViewBox b) {
        return new ViewBox(
            Math.min(a.minX(), b.minX()),
            Math.min(a.minY(), b.minY()),
            Math.min(a.minZ(), b.minZ()),
            Math.max(a.maxX(), b.maxX()),
            Math.max(a.maxY(), b.maxY()),
            Math.max(a.maxZ(), b.maxZ())
        );
    }

    private static boolean columnIntersectsBox(long columnKey, ViewBox box) {
        int chunkX = (int) (columnKey >> 32);
        int chunkZ = (int) columnKey;
        int minChunkX = box.minX() >> 4;
        int maxChunkX = box.maxX() >> 4;
        int minChunkZ = box.minZ() >> 4;
        int maxChunkZ = box.maxZ() >> 4;
        return chunkX >= minChunkX && chunkX <= maxChunkX && chunkZ >= minChunkZ && chunkZ <= maxChunkZ;
    }

    public RemoteView getOrCreate(String peerName, UUID portalId) {
        return views.computeIfAbsent(key(peerName, portalId), k -> new RemoteView(peerName, portalId));
    }

    public RemoteView get(String peerName, UUID portalId) {
        return views.get(key(peerName, portalId));
    }

    public void remove(String peerName, UUID portalId) {
        views.remove(key(peerName, portalId));
    }

    public void markViewReady(String peerName, UUID portalId) {
        RemoteView view = views.computeIfAbsent(key(peerName, portalId), k -> new RemoteView(peerName, portalId));
        view.viewReady = true;
        view.lastUpdateMillis = System.currentTimeMillis();
    }

    public boolean isViewReady(UUID portalId) {
        if (portalId == null) {
            return false;
        }
        for (RemoteView view : views.values()) {
            if (view.portalId.equals(portalId) && view.viewReady) {
                return true;
            }
        }
        return false;
    }

    public boolean hasSlicesFor(UUID portalId) {
        if (portalId == null) {
            return false;
        }
        for (RemoteView view : views.values()) {
            if (view.portalId.equals(portalId) && !view.slices.isEmpty()) {
                return true;
            }
        }
        return false;
    }

    public void applyTime(String peerName, UUID portalId, int skyDarken) {
        RemoteView view = views.get(key(peerName, portalId));
        if (view == null) {
            return;
        }
        view.skyDarken = Math.max(0, Math.min(11, skyDarken));
        view.lastUpdateMillis = System.currentTimeMillis();
    }

    public void applyEntities(String peerName, UUID portalId, List<EntityVisual> entities, List<UUID> presentIds) {
        RemoteView view = views.get(key(peerName, portalId));
        if (view == null) {
            return;
        }
        for (EntityVisual incoming : entities) {
            EntityVisual lastKnown = view.lastEntityState.get(incoming.id());
            EntityVisual full = EntityDeltaCodec.applyDelta(incoming, lastKnown);
            view.lastEntityState.put(incoming.id(), full);
            if (full.isPlayer() && full.playerName() != null && !full.playerName().isEmpty()) {
                RemoteProfile previous = view.profiles.get(full.id());
                String textureValue = full.hasTextures() ? full.textureValue() : previous == null ? "" : previous.textureValue();
                String textureSignature = full.hasTextures() ? full.textureSignature() : previous == null ? "" : previous.textureSignature();
                view.profiles.put(full.id(), new RemoteProfile(full.playerName(), textureValue, textureSignature));
            }
            boolean stateChanged = false;
            int presentMask = incoming.presentMask();
            boolean metadataIncluded = incoming.isFull() || (presentMask & EntityVisual.FIELD_METADATA) != 0;
            boolean equipmentIncluded = incoming.isFull() || (presentMask & EntityVisual.FIELD_EQUIPMENT) != 0;
            if (metadataIncluded && full.metadata() != null && full.metadata().length > 0) {
                try {
                    view.entityMetadata.put(full.id(), List.copyOf(PacketBlobs.readMetadata(full.metadata())));
                    stateChanged = true;
                } catch (Throwable ignored) {
                }
            }
            if (equipmentIncluded && full.equipment() != null && full.equipment().length > 0) {
                try {
                    view.entityEquipment.put(full.id(), List.copyOf(PacketBlobs.readEquipment(full.equipment())));
                    stateChanged = true;
                } catch (Throwable ignored) {
                }
            }
            if (stateChanged) {
                view.stateVersions.merge(full.id(), 1, Integer::sum);
            }
        }
        if (presentIds != null) {
            Set<UUID> present = new HashSet<>(presentIds);
            view.profiles.keySet().retainAll(present);
            view.entityMetadata.keySet().retainAll(present);
            view.entityEquipment.keySet().retainAll(present);
            view.stateVersions.keySet().retainAll(present);
            view.lastEntityState.keySet().retainAll(present);
        }
        view.entities = List.copyOf(view.lastEntityState.values());
        view.lastUpdateMillis = System.currentTimeMillis();
    }

    public void clear() {
        views.clear();
        chunkStores.clear();
    }

    private DecodedSlice decode(ViewSlice slice) {
        BlockData[] palette = new BlockData[slice.palette().size()];
        for (int i = 0; i < palette.length; i++) {
            palette[i] = parseBlockData(slice.palette().get(i));
        }
        String[] biomePalette = slice.biomePalette().toArray(new String[0]);
        return new DecodedSlice(slice, palette, biomePalette);
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
