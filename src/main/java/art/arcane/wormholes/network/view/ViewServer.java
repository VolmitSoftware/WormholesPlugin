package art.arcane.wormholes.network.view;

import art.arcane.volmlib.util.scheduling.FoliaScheduler;

import com.github.retrooper.packetevents.PacketEvents;
import com.github.retrooper.packetevents.protocol.player.TextureProperty;
import com.github.retrooper.packetevents.protocol.player.UserProfile;
import art.arcane.wormholes.Wormholes;
import art.arcane.wormholes.config.toml.NetworkConfig;
import art.arcane.wormholes.network.NetworkManager;
import art.arcane.wormholes.network.WireMessage;
import art.arcane.wormholes.portal.ILocalPortal;
import art.arcane.wormholes.util.AxisAlignedBB;
import art.arcane.wormholes.util.Direction;

import org.bukkit.Chunk;
import org.bukkit.ChunkSnapshot;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.block.data.BlockData;
import org.bukkit.entity.Entity;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.util.BoundingBox;
import org.bukkit.util.Vector;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

public final class ViewServer implements Listener {
    private static final long DIRTY_DRAIN_INTERVAL_TICKS = 2L;

    private final NetworkManager network;
    private final Map<UUID, Session> sessions = new ConcurrentHashMap<>();
    private final Map<BlockData, String> blockDataStrings = new ConcurrentHashMap<>();
    private final AtomicBoolean taskRunning = new AtomicBoolean(false);
    private long tickCounter;

    private static final class Session {
        private final UUID portalId;
        private final World world;
        private final ViewBox box;
        private final int centerChunkX;
        private final int centerChunkZ;
        private final List<long[]> columns;
        private final Set<String> peers = ConcurrentHashMap.newKeySet();
        private final Set<String> pendingFullPeers = ConcurrentHashMap.newKeySet();
        private final Set<UUID> sentProfiles = ConcurrentHashMap.newKeySet();
        private final Map<UUID, Long> metadataHashes = new ConcurrentHashMap<>();
        private final Map<UUID, Long> equipmentHashes = new ConcurrentHashMap<>();
        private final Set<Long> dirtyColumns = ConcurrentHashMap.newKeySet();
        private final Map<Long, Long> sliceHashes = new ConcurrentHashMap<>();
        private final Map<Long, ViewSlice> latestSlices = new ConcurrentHashMap<>();
        private final AtomicInteger captureCountdown = new AtomicInteger(0);
        private final AtomicBoolean entityCaptureRunning = new AtomicBoolean(false);
        private volatile List<EntityVisual> lastVisuals = List.of();
        private volatile boolean ticketsHeld;

        private Session(UUID portalId, World world, ViewBox box, int centerChunkX, int centerChunkZ) {
            this.portalId = portalId;
            this.world = world;
            this.box = box;
            this.centerChunkX = centerChunkX;
            this.centerChunkZ = centerChunkZ;
            this.columns = new ArrayList<>();
            for (int cx = box.minX() >> 4; cx <= box.maxX() >> 4; cx++) {
                for (int cz = box.minZ() >> 4; cz <= box.maxZ() >> 4; cz++) {
                    columns.add(new long[]{cx, cz});
                }
            }
        }
    }

    public ViewServer(NetworkManager network) {
        this.network = network;
    }

    public static ViewBox computeBox(ILocalPortal portal, int depth, int lateralPad) {
        AxisAlignedBB area = portal.getStructure().getArea();
        World world = portal.getStructure().getWorld();
        Direction normal = portal.getFrame().getNormal();
        int padX = normal.x() != 0 ? depth : lateralPad;
        int padY = normal.y() != 0 ? depth : lateralPad;
        int padZ = normal.z() != 0 ? depth : lateralPad;
        int minX = (int) Math.floor(Math.min(area.getXa(), area.getXb())) - padX;
        int minY = (int) Math.floor(Math.min(area.getYa(), area.getYb())) - padY;
        int minZ = (int) Math.floor(Math.min(area.getZa(), area.getZb())) - padZ;
        int maxX = (int) Math.floor(Math.max(area.getXa(), area.getXb())) + padX;
        int maxY = (int) Math.floor(Math.max(area.getYa(), area.getYb())) + padY;
        int maxZ = (int) Math.floor(Math.max(area.getZa(), area.getZb())) + padZ;
        minY = Math.max(minY, world.getMinHeight());
        maxY = Math.min(maxY, world.getMaxHeight() - 1);
        return new ViewBox(minX, minY, minZ, maxX, maxY, maxZ);
    }

    public void onSubscribe(String peerName, UUID portalId) {
        ILocalPortal portal = Wormholes.portalManager == null ? null : Wormholes.portalManager.getLocalPortal(portalId);
        if (portal == null || portal.getStructure() == null || portal.getStructure().getWorld() == null) {
            return;
        }
        NetworkConfig config = Wormholes.settings.getNetwork();
        Session session = sessions.computeIfAbsent(portalId, id -> new Session(
            id,
            portal.getStructure().getWorld(),
            computeBox(portal, config.viewDepth, config.viewLateralPad),
            ((int) Math.floor(portal.getOrigin().getX())) >> 4,
            ((int) Math.floor(portal.getOrigin().getZ())) >> 4
        ));
        session.peers.add(peerName);
        session.pendingFullPeers.add(peerName);
        session.sentProfiles.clear();
        session.metadataHashes.clear();
        session.equipmentHashes.clear();
        startTask();
        requestFullCapture(session);
    }

    public void onUnsubscribe(String peerName, UUID portalId) {
        Session session = sessions.get(portalId);
        if (session == null) {
            return;
        }
        session.peers.remove(peerName);
        session.pendingFullPeers.remove(peerName);
        if (session.peers.isEmpty()) {
            sessions.remove(portalId);
            releaseTickets(session);
        }
    }

    public void onPeerDisconnected(String peerName) {
        for (Session session : sessions.values()) {
            onUnsubscribe(peerName, session.portalId);
        }
    }

    public void shutdown() {
        for (Session session : sessions.values()) {
            releaseTickets(session);
        }
        sessions.clear();
    }

    @EventHandler(ignoreCancelled = true)
    public void on(BlockBreakEvent event) {
        markDirty(event.getBlock().getWorld(), event.getBlock().getX(), event.getBlock().getY(), event.getBlock().getZ());
    }

    @EventHandler(ignoreCancelled = true)
    public void on(BlockPlaceEvent event) {
        markDirty(event.getBlock().getWorld(), event.getBlock().getX(), event.getBlock().getY(), event.getBlock().getZ());
    }

    private void markDirty(World world, int x, int y, int z) {
        if (sessions.isEmpty()) {
            return;
        }
        for (Session session : sessions.values()) {
            if (session.world.equals(world) && session.box.contains(x, y, z)) {
                session.dirtyColumns.add(ViewSlice.columnKey(x >> 4, z >> 4));
            }
        }
    }

    private void startTask() {
        if (!taskRunning.compareAndSet(false, true)) {
            return;
        }
        scheduleTick();
    }

    private void scheduleTick() {
        FoliaScheduler.runAsync(Wormholes.instance, () -> {
            tick();
            if (sessions.isEmpty()) {
                taskRunning.set(false);
                return;
            }
            scheduleTick();
        }, DIRTY_DRAIN_INTERVAL_TICKS);
    }

    private void tick() {
        tickCounter += DIRTY_DRAIN_INTERVAL_TICKS;
        NetworkConfig config = Wormholes.settings.getNetwork();
        int heartbeat = Math.max(20, config.viewHeartbeatTicks);
        boolean heartbeatDue = tickCounter % ((heartbeat / DIRTY_DRAIN_INTERVAL_TICKS) * DIRTY_DRAIN_INTERVAL_TICKS) < DIRTY_DRAIN_INTERVAL_TICKS;
        int entityInterval = Math.max((int) DIRTY_DRAIN_INTERVAL_TICKS, config.viewEntityIntervalTicks);
        boolean entitiesDue = tickCounter % ((entityInterval / DIRTY_DRAIN_INTERVAL_TICKS) * DIRTY_DRAIN_INTERVAL_TICKS) < DIRTY_DRAIN_INTERVAL_TICKS;

        for (Session session : sessions.values()) {
            if (Wormholes.portalManager == null || Wormholes.portalManager.getLocalPortal(session.portalId) == null) {
                sessions.remove(session.portalId);
                releaseTickets(session);
                continue;
            }
            if (entitiesDue && session.entityCaptureRunning.compareAndSet(false, true)) {
                FoliaScheduler.runRegion(Wormholes.instance, session.world, session.centerChunkX, session.centerChunkZ, () -> captureEntities(session));
            }
            if (heartbeatDue || !session.pendingFullPeers.isEmpty()) {
                requestFullCapture(session);
                continue;
            }
            if (session.dirtyColumns.isEmpty()) {
                continue;
            }
            List<Long> dirty = new ArrayList<>(session.dirtyColumns);
            session.dirtyColumns.clear();
            for (long columnKey : dirty) {
                captureColumn(session, (int) (columnKey >> 32), (int) columnKey, false);
            }
        }
    }

    private void captureEntities(Session session) {
        try {
            BoundingBox bounds = new BoundingBox(session.box.minX(), session.box.minY(), session.box.minZ(),
                session.box.maxX() + 1, session.box.maxY() + 1, session.box.maxZ() + 1);
            List<EntityVisual> visuals = new ArrayList<>(16);
            for (Entity entity : session.world.getNearbyEntities(bounds)) {
                if (visuals.size() >= 64) {
                    break;
                }
                if (entity.isDead() || !entity.isValid()) {
                    continue;
                }
                Location location = entity.getLocation();
                Vector look = entity instanceof LivingEntity living ? living.getEyeLocation().getDirection() : location.getDirection();
                Vector velocity = entity.getVelocity();
                String playerName = "";
                String textureValue = "";
                String textureSignature = "";
                if (entity instanceof Player player) {
                    playerName = player.getName();
                    if (session.sentProfiles.add(player.getUniqueId())) {
                        String[] textures = playerTextures(player);
                        textureValue = textures[0];
                        textureSignature = textures[1];
                    }
                }
                byte[] metadata = blobIfChanged(session.metadataHashes, entity.getUniqueId(), PacketBlobs.captureMetadata(entity));
                byte[] equipment = blobIfChanged(session.equipmentHashes, entity.getUniqueId(), PacketBlobs.captureEquipment(entity));
                visuals.add(new EntityVisual(
                    entity.getUniqueId(),
                    entity.getType().getKey().toString(),
                    location.getX(), location.getY(), location.getZ(),
                    entity.getHeight(),
                    look.getX(), look.getY(), look.getZ(),
                    velocity.getX(), velocity.getY(), velocity.getZ(),
                    entity.isOnGround(),
                    playerName,
                    textureValue,
                    textureSignature,
                    metadata,
                    equipment
                ));
            }
            Set<UUID> presentIds = ConcurrentHashMap.newKeySet();
            for (EntityVisual visual : visuals) {
                presentIds.add(visual.id());
            }
            session.sentProfiles.retainAll(presentIds);
            session.metadataHashes.keySet().retainAll(presentIds);
            session.equipmentHashes.keySet().retainAll(presentIds);
            if (visuals.equals(session.lastVisuals)) {
                return;
            }
            session.lastVisuals = visuals;
            WireMessage.ViewEntities message = new WireMessage.ViewEntities(session.portalId, visuals);
            for (String peerName : session.peers) {
                network.send(peerName, message);
            }
        } catch (Throwable e) {
            Wormholes.v("net: entity view capture failed for portal " + session.portalId + ": " + e.getMessage());
        } finally {
            session.entityCaptureRunning.set(false);
        }
    }

    public void forwardAnimation(UUID entityId, com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerEntityAnimation.EntityAnimationType type) {
        forwardEntityEvent(entityId, false, type.ordinal(), 0.0F);
    }

    public void forwardHurt(UUID entityId, float yaw) {
        forwardEntityEvent(entityId, true, 0, yaw);
    }

    private void forwardEntityEvent(UUID entityId, boolean hurt, int animationOrdinal, float yaw) {
        if (sessions.isEmpty()) {
            return;
        }
        for (Session session : sessions.values()) {
            for (EntityVisual visual : session.lastVisuals) {
                if (visual.id().equals(entityId)) {
                    WireMessage.ViewEntityAnimation message = new WireMessage.ViewEntityAnimation(session.portalId, entityId, hurt, animationOrdinal, yaw);
                    for (String peerName : session.peers) {
                        network.send(peerName, message);
                    }
                    break;
                }
            }
        }
    }

    private static byte[] blobIfChanged(Map<UUID, Long> hashes, UUID entityId, byte[] blob) {
        long hash = blobHash(blob);
        Long previous = hashes.put(entityId, hash);
        if (previous != null && previous == hash) {
            return PacketBlobs.EMPTY;
        }
        return blob;
    }

    private static long blobHash(byte[] blob) {
        long hash = 0xcbf29ce484222325L;
        for (byte value : blob) {
            hash = (hash ^ (value & 0xFF)) * 0x100000001b3L;
        }
        return hash;
    }

    private static String[] playerTextures(Player player) {
        try {
            UserProfile profile = PacketEvents.getAPI().getPlayerManager().getUser(player).getProfile();
            if (profile != null) {
                for (TextureProperty property : profile.getTextureProperties()) {
                    if ("textures".equals(property.getName())) {
                        return new String[]{property.getValue(), property.getSignature() == null ? "" : property.getSignature()};
                    }
                }
            }
        } catch (Throwable ignored) {
        }
        return new String[]{"", ""};
    }

    private void requestFullCapture(Session session) {
        if (!session.captureCountdown.compareAndSet(0, session.columns.size())) {
            return;
        }
        session.dirtyColumns.clear();
        for (long[] column : session.columns) {
            captureColumn(session, (int) column[0], (int) column[1], true);
        }
    }

    private void captureColumn(Session session, int chunkX, int chunkZ, boolean partOfFullPass) {
        session.world.getChunkAtAsync(chunkX, chunkZ).whenComplete((chunk, error) -> {
            if (error != null || chunk == null) {
                if (partOfFullPass) {
                    completeFullCaptureColumn(session);
                }
                return;
            }
            holdTicket(session, chunk);
            ChunkSnapshot snapshot = chunk.getChunkSnapshot(false, false, false);
            FoliaScheduler.runAsync(Wormholes.instance, () -> {
                encodeAndSend(session, chunkX, chunkZ, snapshot);
                if (partOfFullPass) {
                    completeFullCaptureColumn(session);
                }
            });
        });
    }

    private void holdTicket(Session session, Chunk chunk) {
        if (!session.ticketsHeld) {
            session.ticketsHeld = true;
        }
        chunk.addPluginChunkTicket(Wormholes.instance);
    }

    private void releaseTickets(Session session) {
        if (!session.ticketsHeld) {
            return;
        }
        for (long[] column : session.columns) {
            int chunkX = (int) column[0];
            int chunkZ = (int) column[1];
            FoliaScheduler.runRegion(Wormholes.instance, session.world, chunkX, chunkZ, () -> {
                if (session.world.isChunkLoaded(chunkX, chunkZ)) {
                    session.world.getChunkAt(chunkX, chunkZ).removePluginChunkTicket(Wormholes.instance);
                }
            });
        }
    }

    private void completeFullCaptureColumn(Session session) {
        if (session.captureCountdown.decrementAndGet() != 0) {
            return;
        }
        if (session.pendingFullPeers.isEmpty()) {
            return;
        }
        List<ViewSlice> slices = new ArrayList<>(session.latestSlices.values());
        WireMessage.ViewSnapshot snapshot = new WireMessage.ViewSnapshot(session.portalId, session.box, slices);
        for (String peerName : session.pendingFullPeers) {
            network.send(peerName, snapshot);
        }
        session.pendingFullPeers.clear();
    }

    private void encodeAndSend(Session session, int chunkX, int chunkZ, ChunkSnapshot snapshot) {
        ViewBox box = session.box;
        int minX = Math.max(box.minX(), chunkX << 4);
        int maxX = Math.min(box.maxX(), (chunkX << 4) + 15);
        int minZ = Math.max(box.minZ(), chunkZ << 4);
        int maxZ = Math.min(box.maxZ(), (chunkZ << 4) + 15);
        if (minX > maxX || minZ > maxZ) {
            return;
        }
        int minY = box.minY();
        int maxY = box.maxY();
        int sizeX = maxX - minX + 1;
        int sizeY = maxY - minY + 1;
        int sizeZ = maxZ - minZ + 1;
        int cells = sizeX * sizeY * sizeZ;

        List<String> palette = new ArrayList<>(16);
        HashMap<String, Integer> paletteLookup = new HashMap<>(32);
        short[] indices = new short[cells];
        byte[] light = new byte[cells];

        int cell = 0;
        for (int y = minY; y <= maxY; y++) {
            int ly = y;
            for (int z = minZ; z <= maxZ; z++) {
                int lz = z & 0xF;
                for (int x = minX; x <= maxX; x++) {
                    int lx = x & 0xF;
                    BlockData data = snapshot.getBlockData(lx, ly, lz);
                    String stateString = blockDataStrings.computeIfAbsent(data, BlockData::getAsString);
                    Integer paletteIndex = paletteLookup.get(stateString);
                    if (paletteIndex == null) {
                        paletteIndex = palette.size();
                        palette.add(stateString);
                        paletteLookup.put(stateString, paletteIndex);
                    }
                    indices[cell] = (short) paletteIndex.intValue();
                    int sky = snapshot.getBlockSkyLight(lx, ly, lz);
                    int emitted = snapshot.getBlockEmittedLight(lx, ly, lz);
                    light[cell] = (byte) (((sky & 0x0F) << 4) | (emitted & 0x0F));
                    cell++;
                }
            }
        }

        ViewSlice slice = new ViewSlice(minX, minY, minZ, sizeX, sizeY, sizeZ, palette, indices, light);
        long columnKey = ViewSlice.columnKey(chunkX, chunkZ);
        long hash = slice.contentHash();
        Long previous = session.sliceHashes.put(columnKey, hash);
        session.latestSlices.put(columnKey, slice);
        if (previous != null && previous == hash) {
            return;
        }

        WireMessage.ViewDelta delta = new WireMessage.ViewDelta(session.portalId, List.of(slice));
        for (String peerName : session.peers) {
            if (!session.pendingFullPeers.contains(peerName)) {
                network.send(peerName, delta);
            }
        }
    }
}
