package art.arcane.wormholes.render.view;

import art.arcane.volmlib.util.scheduling.FoliaScheduler;
import art.arcane.wormholes.EffectManager;
import art.arcane.wormholes.Wormholes;
import art.arcane.wormholes.network.view.EntityVisual;
import art.arcane.wormholes.network.view.PacketBlobs;
import art.arcane.wormholes.network.view.RemoteViewCache;
import art.arcane.wormholes.render.ProjectionWorldChangeTracker;

import com.github.retrooper.packetevents.PacketEvents;
import com.github.retrooper.packetevents.protocol.entity.data.EntityData;
import com.github.retrooper.packetevents.protocol.player.Equipment;
import com.github.retrooper.packetevents.protocol.player.TextureProperty;
import com.github.retrooper.packetevents.protocol.player.UserProfile;

import org.bukkit.Chunk;
import org.bukkit.ChunkSnapshot;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.data.BlockData;
import org.bukkit.entity.Entity;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;
import org.bukkit.util.Vector;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.logging.Level;

public final class RegionSnapshotWorldViewProvider implements ProjectionWorldViewProvider {
    private static final long REFRESH_INTERVAL_MILLIS = 250L;
    private static final int MAX_CHUNKS_PER_WORLD = 256;
    private static final int MAX_ENTITIES_PER_CHUNK = 128;
    private static final long ENTITY_STATE_REFRESH_MILLIS = 500L;

    private final Plugin plugin;
    private final Map<World, SnapshotWorldView> views;
    private volatile boolean closed;

    public RegionSnapshotWorldViewProvider(Plugin plugin) {
        this.plugin = plugin;
        this.views = new ConcurrentHashMap<World, SnapshotWorldView>();
        this.closed = false;
    }

    @Override
    public ProjectionWorldView view(World world) {
        if (closed || world == null) {
            return null;
        }
        return views.computeIfAbsent(world, key -> new SnapshotWorldView(key));
    }

    @Override
    public boolean usesRegionSnapshots() {
        return true;
    }

    @Override
    public void close() {
        closed = true;
        views.clear();
    }

    private void capture(SnapshotWorldView view, int chunkX, int chunkZ, long key) {
        if (closed) {
            view.finishCapture(key);
            return;
        }
        World world = view.world;
        try {
            if (!world.isChunkLoaded(chunkX, chunkZ)) {
                world.getChunkAtAsync(chunkX, chunkZ).whenComplete((chunk, error) -> {
                    if (error != null) {
                        view.finishCapture(key);
                        plugin.getLogger().log(Level.WARNING, "Projection snapshot chunk load failed at " + chunkX + "," + chunkZ, error);
                        return;
                    }
                    boolean scheduled = FoliaScheduler.runRegion(plugin, world, chunkX, chunkZ,
                        () -> captureLoaded(view, chunkX, chunkZ, key));
                    if (!scheduled) {
                        view.finishCapture(key);
                    }
                });
                return;
            }
            captureLoaded(view, chunkX, chunkZ, key);
        } catch (Throwable ex) {
            view.finishCapture(key);
            plugin.getLogger().log(Level.WARNING, "Projection snapshot capture failed at " + chunkX + "," + chunkZ, ex);
        }
    }

    private void captureLoaded(SnapshotWorldView view, int chunkX, int chunkZ, long key) {
        if (closed) {
            view.finishCapture(key);
            return;
        }
        World world = view.world;
        try {
            if (!world.isChunkLoaded(chunkX, chunkZ)) {
                view.finishCapture(key);
                return;
            }
            Chunk chunk = world.getChunkAt(chunkX, chunkZ);
            ProjectionWorldChangeTracker tracker = Wormholes.projectionChangeTracker;
            long trackerVersion = tracker == null ? Long.MIN_VALUE : tracker.currentVersion();
            ChunkSnapshot snapshot = chunk.getChunkSnapshot(false, true, false, true);
            List<CapturedEntity> entities = captureEntities(view, chunk, key);
            CapturedChunk captured = new CapturedChunk(snapshot, world.getMinHeight(), world.getMaxHeight(),
                ProjectionWorldView.computeSkyDarken(world.getTime()), System.currentTimeMillis(), entities,
                chunkX, chunkZ, trackerVersion);
            view.publish(key, captured);
        } catch (Throwable ex) {
            view.finishCapture(key);
            plugin.getLogger().log(Level.WARNING, "Projection snapshot capture failed at " + chunkX + "," + chunkZ, ex);
        }
    }

    private List<CapturedEntity> captureEntities(SnapshotWorldView view, Chunk chunk, long chunkKey) {
        Entity[] source = chunk.getEntities();
        int limit = Math.min(source.length, MAX_ENTITIES_PER_CHUNK);
        List<CapturedEntity> captured = new ArrayList<CapturedEntity>(limit);
        for (int i = 0; i < source.length && captured.size() < limit; i++) {
            Entity entity = source[i];
            if (entity == null || entity.isDead() || !entity.isValid() || EffectManager.isPortalEffectEntity(entity)) {
                continue;
            }
            captured.add(captureEntity(view, entity, chunkKey));
        }
        return List.copyOf(captured);
    }

    private CapturedEntity captureEntity(SnapshotWorldView view, Entity entity, long chunkKey) {
        long capturedAtMillis = System.currentTimeMillis();
        EntityState previousState = view.entityStates.get(entity.getUniqueId());
        CapturedEntity previous = previousState == null ? null : previousState.entity;
        Location location = entity.getLocation();
        Vector look = entity instanceof LivingEntity living ? living.getEyeLocation().getDirection() : location.getDirection();
        Vector velocity = entity.getVelocity();
        String playerName = "";
        String textureValue = "";
        String textureSignature = "";
        if (entity instanceof Player player) {
            playerName = player.getName();
            if (previous != null && previous.profile != null) {
                textureValue = previous.profile.textureValue();
                textureSignature = previous.profile.textureSignature();
            } else {
                String[] textures = playerTextures(player);
                textureValue = textures[0];
                textureSignature = textures[1];
            }
        }
        Entity vehicle = entity.getVehicle();
        UUID passengerOf = vehicle == null ? null : vehicle.getUniqueId();
        UUID leashHolder = null;
        if (entity instanceof LivingEntity living && living.isLeashed()) {
            try {
                Entity holder = living.getLeashHolder();
                leashHolder = holder == null ? null : holder.getUniqueId();
            } catch (IllegalStateException ignored) {
                leashHolder = null;
            }
        }
        boolean reuseState = previousState != null
            && capturedAtMillis - previousState.capturedAtMillis < ENTITY_STATE_REFRESH_MILLIS;
        byte[] metadataBlob = reuseState ? previous.visual.metadata() : PacketBlobs.captureMetadata(entity);
        byte[] equipmentBlob = reuseState ? previous.visual.equipment() : PacketBlobs.captureEquipment(entity);
        List<EntityData<?>> metadata = reuseState ? previous.metadata : List.copyOf(PacketBlobs.readMetadata(metadataBlob));
        List<Equipment> equipment = reuseState ? previous.equipment : List.copyOf(PacketBlobs.readEquipment(equipmentBlob));
        EntityVisual visual = EntityVisual.full(entity.getUniqueId(), entity.getType().getKey().toString(),
            location.getX(), location.getY(), location.getZ(), entity.getHeight(),
            look.getX(), look.getY(), look.getZ(), location.getYaw(), location.getPitch(),
            velocity.getX(), velocity.getY(), velocity.getZ(), entity.isOnGround(),
            playerName, textureValue, textureSignature, passengerOf, leashHolder,
            metadataBlob, equipmentBlob, 0);
        RemoteViewCache.RemoteProfile profile = entity instanceof Player
            ? new RemoteViewCache.RemoteProfile(playerName, textureValue, textureSignature)
            : null;
        return new CapturedEntity(chunkKey, visual, profile, metadata, equipment, capturedAtMillis);
    }

    private static String[] playerTextures(Player player) {
        try {
            UserProfile profile = PacketEvents.getAPI().getPlayerManager().getUser(player).getProfile();
            if (profile != null) {
                for (TextureProperty property : profile.getTextureProperties()) {
                    if ("textures".equals(property.getName())) {
                        String signature = property.getSignature() == null ? "" : property.getSignature();
                        return new String[] {property.getValue(), signature};
                    }
                }
            }
        } catch (Throwable ignored) {
        }
        return new String[] {"", ""};
    }

    private final class SnapshotWorldView implements ProjectionWorldView, ProjectionEntityView {
        private final World world;
        private final BlockData sharedAir;
        private final Map<Long, CapturedChunk> chunks;
        private final Map<UUID, EntityState> entityStates;
        private final Set<Long> capturesInFlight;
        private final AtomicLong revision;
        private final AtomicInteger entityVersion;
        private volatile int minHeight;
        private volatile int maxHeight;
        private volatile int skyDarken;

        private SnapshotWorldView(World world) {
            this.world = world;
            this.sharedAir = Material.AIR.createBlockData();
            this.chunks = new ConcurrentHashMap<Long, CapturedChunk>();
            this.entityStates = new ConcurrentHashMap<UUID, EntityState>();
            this.capturesInFlight = ConcurrentHashMap.newKeySet();
            this.revision = new AtomicLong();
            this.entityVersion = new AtomicInteger();
            this.minHeight = 0;
            this.maxHeight = 0;
            this.skyDarken = 0;
        }

        @Override
        public World getWorld() {
            return world;
        }

        @Override
        public int getMinHeight() {
            return minHeight;
        }

        @Override
        public int getMaxHeight() {
            return maxHeight;
        }

        @Override
        public BlockData sampleBlockData(int x, int y, int z) {
            CapturedChunk chunk = capturedChunk(x, z);
            if (chunk == null || y < chunk.minHeight || y >= chunk.maxHeight) {
                return null;
            }
            Material material = chunk.snapshot.getBlockType(x & 15, y, z & 15);
            if (ProjectionWorldView.isAir(material)) {
                return sharedAir;
            }
            return chunk.snapshot.getBlockData(x & 15, y, z & 15);
        }

        @Override
        public String sampleBiome(int x, int y, int z) {
            CapturedChunk chunk = capturedChunk(x, z);
            if (chunk == null || y < chunk.minHeight || y >= chunk.maxHeight) {
                return null;
            }
            return chunk.snapshot.getBiome(x & 15, y, z & 15).getKey().asString();
        }

        @Override
        public int getLight(int x, int y, int z) {
            CapturedChunk chunk = capturedChunk(x, z);
            if (chunk == null || y < chunk.minHeight || y >= chunk.maxHeight) {
                return LIGHT_UNAVAILABLE;
            }
            return ProjectionWorldView.packLight(chunk.snapshot.getBlockSkyLight(x & 15, y, z & 15),
                chunk.snapshot.getBlockEmittedLight(x & 15, y, z & 15));
        }

        @Override
        public int getSkyDarken() {
            return skyDarken;
        }

        @Override
        public long getRevision() {
            return revision.get();
        }

        @Override
        public boolean isChunkReady(int x, int z) {
            long key = chunkKey(x >> 4, z >> 4);
            CapturedChunk chunk = chunks.get(key);
            if (chunk != null && isDirty(chunk)) {
                requestCapture(x >> 4, z >> 4, key);
                return false;
            }
            requestIfStale(x >> 4, z >> 4, key, chunk);
            return chunk != null;
        }

        @Override
        public void requestChunk(int x, int z) {
            int chunkX = x >> 4;
            int chunkZ = z >> 4;
            long key = chunkKey(chunkX, chunkZ);
            requestIfStale(chunkX, chunkZ, key, chunks.get(key));
        }

        @Override
        public List<EntityVisual> getEntities(double centerX, double centerY, double centerZ, double range) {
            int minChunkX = ((int) Math.floor(centerX - range)) >> 4;
            int maxChunkX = ((int) Math.floor(centerX + range)) >> 4;
            int minChunkZ = ((int) Math.floor(centerZ - range)) >> 4;
            int maxChunkZ = ((int) Math.floor(centerZ + range)) >> 4;
            List<EntityVisual> result = new ArrayList<EntityVisual>();
            Set<UUID> seen = new HashSet<UUID>();
            for (int chunkX = minChunkX; chunkX <= maxChunkX; chunkX++) {
                for (int chunkZ = minChunkZ; chunkZ <= maxChunkZ; chunkZ++) {
                    long key = chunkKey(chunkX, chunkZ);
                    CapturedChunk chunk = chunks.get(key);
                    requestIfStale(chunkX, chunkZ, key, chunk);
                    if (chunk == null) {
                        continue;
                    }
                    for (CapturedEntity entity : chunk.entities) {
                        EntityState state = entityStates.get(entity.visual.id());
                        EntityVisual visual = state == null ? entity.visual : state.entity.visual;
                        if (!seen.add(visual.id())) {
                            continue;
                        }
                        if (Math.abs(visual.x() - centerX) > range
                            || Math.abs(visual.y() - centerY) > range
                            || Math.abs(visual.z() - centerZ) > range) {
                            continue;
                        }
                        result.add(visual);
                    }
                }
            }
            return result;
        }

        @Override
        public RemoteViewCache.RemoteProfile getProfile(UUID entityId) {
            EntityState state = entityStates.get(entityId);
            return state == null ? null : state.entity.profile;
        }

        @Override
        public List<EntityData<?>> getMetadata(UUID entityId) {
            EntityState state = entityStates.get(entityId);
            return state == null ? null : state.entity.metadata;
        }

        @Override
        public List<Equipment> getEquipment(UUID entityId) {
            EntityState state = entityStates.get(entityId);
            return state == null ? null : state.entity.equipment;
        }

        @Override
        public int getStateVersion(UUID entityId) {
            EntityState state = entityStates.get(entityId);
            return state == null ? 0 : state.version;
        }

        private CapturedChunk capturedChunk(int x, int z) {
            int chunkX = x >> 4;
            int chunkZ = z >> 4;
            long key = chunkKey(chunkX, chunkZ);
            CapturedChunk chunk = chunks.get(key);
            if (chunk != null && isDirty(chunk)) {
                requestCapture(chunkX, chunkZ, key);
                return null;
            }
            requestIfStale(chunkX, chunkZ, key, chunk);
            return chunk;
        }

        private void requestIfStale(int chunkX, int chunkZ, long key, CapturedChunk current) {
            long now = System.currentTimeMillis();
            if (current != null && now - current.capturedAtMillis < REFRESH_INTERVAL_MILLIS) {
                return;
            }
            requestCapture(chunkX, chunkZ, key);
        }

        private void requestCapture(int chunkX, int chunkZ, long key) {
            if (!capturesInFlight.add(Long.valueOf(key))) {
                return;
            }
            boolean scheduled = FoliaScheduler.runRegion(plugin, world, chunkX, chunkZ,
                () -> capture(this, chunkX, chunkZ, key));
            if (!scheduled) {
                finishCapture(key);
            }
        }

        private boolean isDirty(CapturedChunk chunk) {
            return isChunkDirty(Wormholes.projectionChangeTracker, world.getUID(), chunk.chunkX, chunk.chunkZ,
                chunk.trackerVersion);
        }

        private void publish(long key, CapturedChunk captured) {
            CapturedChunk previous = chunks.put(Long.valueOf(key), captured);
            minHeight = captured.minHeight;
            maxHeight = captured.maxHeight;
            skyDarken = captured.skyDarken;
            for (CapturedEntity entity : captured.entities) {
                entityStates.compute(entity.visual.id(), (id, prior) -> {
                    if (prior != null && prior.capturedAtMillis > entity.capturedAtMillis) {
                        return prior;
                    }
                    int version = prior != null && sameEntityState(prior.entity.visual, prior.entity.profile,
                        entity.visual, entity.profile)
                        ? prior.version
                        : entityVersion.incrementAndGet();
                    return new EntityState(entity, version, entity.capturedAtMillis);
                });
            }
            if (previous != null) {
                Set<UUID> currentIds = new HashSet<UUID>(captured.entities.size());
                for (CapturedEntity entity : captured.entities) {
                    currentIds.add(entity.visual.id());
                }
                for (CapturedEntity entity : previous.entities) {
                    if (currentIds.contains(entity.visual.id())) {
                        continue;
                    }
                    entityStates.computeIfPresent(entity.visual.id(), (id, state) -> state.entity.chunkKey == key ? null : state);
                }
            }
            capturesInFlight.remove(Long.valueOf(key));
            if (blocksChanged(previous, captured)) {
                revision.incrementAndGet();
            }
            evictOldest();
        }

        private boolean blocksChanged(CapturedChunk previous, CapturedChunk captured) {
            if (previous == null || previous.skyDarken != captured.skyDarken) {
                return true;
            }
            ProjectionWorldChangeTracker tracker = Wormholes.projectionChangeTracker;
            return tracker == null || previous.trackerVersion == Long.MIN_VALUE
                || isChunkDirty(tracker, world.getUID(), captured.chunkX, captured.chunkZ, previous.trackerVersion);
        }

        private void finishCapture(long key) {
            capturesInFlight.remove(Long.valueOf(key));
        }

        private void evictOldest() {
            while (chunks.size() > MAX_CHUNKS_PER_WORLD) {
                long oldestKey = 0L;
                long oldestMillis = Long.MAX_VALUE;
                for (Map.Entry<Long, CapturedChunk> entry : chunks.entrySet()) {
                    if (entry.getValue().capturedAtMillis < oldestMillis) {
                        oldestKey = entry.getKey().longValue();
                        oldestMillis = entry.getValue().capturedAtMillis;
                    }
                }
                CapturedChunk removed = chunks.remove(Long.valueOf(oldestKey));
                if (removed == null) {
                    return;
                }
                final long evictedKey = oldestKey;
                for (CapturedEntity entity : removed.entities) {
                    entityStates.computeIfPresent(entity.visual.id(), (id, state) -> state.entity.chunkKey == evictedKey ? null : state);
                }
            }
        }
    }

    private static long chunkKey(int chunkX, int chunkZ) {
        return (((long) chunkX) << 32) | (((long) chunkZ) & 0xFFFFFFFFL);
    }

    static boolean sameEntityState(EntityVisual previousVisual,
                                   RemoteViewCache.RemoteProfile previousProfile,
                                   EntityVisual currentVisual,
                                   RemoteViewCache.RemoteProfile currentProfile) {
        if (previousVisual == null || currentVisual == null) {
            return false;
        }
        return Arrays.equals(previousVisual.metadata(), currentVisual.metadata())
            && Arrays.equals(previousVisual.equipment(), currentVisual.equipment())
            && Objects.equals(previousProfile, currentProfile);
    }

    static boolean isChunkDirty(ProjectionWorldChangeTracker tracker, UUID worldId, int chunkX, int chunkZ,
                                long trackerVersion) {
        return tracker != null && trackerVersion != Long.MIN_VALUE
            && tracker.dirtySince(worldId, chunkX, chunkZ, chunkX, chunkZ, trackerVersion);
    }

    private static final class CapturedChunk {
        private final ChunkSnapshot snapshot;
        private final int minHeight;
        private final int maxHeight;
        private final int skyDarken;
        private final long capturedAtMillis;
        private final List<CapturedEntity> entities;
        private final int chunkX;
        private final int chunkZ;
        private final long trackerVersion;

        private CapturedChunk(ChunkSnapshot snapshot, int minHeight, int maxHeight, int skyDarken,
                              long capturedAtMillis, List<CapturedEntity> entities, int chunkX, int chunkZ,
                              long trackerVersion) {
            this.snapshot = snapshot;
            this.minHeight = minHeight;
            this.maxHeight = maxHeight;
            this.skyDarken = skyDarken;
            this.capturedAtMillis = capturedAtMillis;
            this.entities = entities;
            this.chunkX = chunkX;
            this.chunkZ = chunkZ;
            this.trackerVersion = trackerVersion;
        }
    }

    private static final class CapturedEntity {
        private final long chunkKey;
        private final EntityVisual visual;
        private final RemoteViewCache.RemoteProfile profile;
        private final List<EntityData<?>> metadata;
        private final List<Equipment> equipment;
        private final long capturedAtMillis;

        private CapturedEntity(long chunkKey, EntityVisual visual, RemoteViewCache.RemoteProfile profile,
                               List<EntityData<?>> metadata, List<Equipment> equipment, long capturedAtMillis) {
            this.chunkKey = chunkKey;
            this.visual = visual;
            this.profile = profile;
            this.metadata = metadata;
            this.equipment = equipment;
            this.capturedAtMillis = capturedAtMillis;
        }
    }

    private static final class EntityState {
        private final CapturedEntity entity;
        private final int version;
        private final long capturedAtMillis;

        private EntityState(CapturedEntity entity, int version, long capturedAtMillis) {
            this.entity = entity;
            this.version = version;
            this.capturedAtMillis = capturedAtMillis;
        }
    }
}
