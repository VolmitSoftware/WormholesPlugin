package art.arcane.wormholes.network.view;

import art.arcane.volmlib.util.scheduling.FoliaScheduler;

import com.github.retrooper.packetevents.PacketEvents;
import com.github.retrooper.packetevents.protocol.player.TextureProperty;
import com.github.retrooper.packetevents.protocol.player.UserProfile;
import art.arcane.wormholes.Settings;
import art.arcane.wormholes.Wormholes;
import art.arcane.wormholes.config.toml.NetworkConfig;
import art.arcane.wormholes.network.NetworkManager;
import art.arcane.wormholes.network.WireMessage;
import art.arcane.wormholes.network.replication.ChunkBulkBuilder;
import art.arcane.wormholes.network.replication.ChunkReplicationManager;
import art.arcane.wormholes.network.replication.ChunkResyncRequest;
import art.arcane.wormholes.portal.ILocalPortal;
import art.arcane.wormholes.util.AxisAlignedBB;

import org.bukkit.ChunkSnapshot;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.block.data.BlockData;
import org.bukkit.entity.Entity;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.event.Listener;
import org.bukkit.util.BoundingBox;
import org.bukkit.util.Vector;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

public final class ViewServer implements Listener {
    public record Stats(int subscriptions, int trackedEntities, long chunkBulkSentCount, long chunkDiffSentCount, long entitySendCount, long timeSendCount) {
    }

    private static final long DIRTY_DRAIN_INTERVAL_TICKS = 2L;
    private static final int MAX_BULK_SNAPSHOTS_PER_TICK = 8;
    private static final int SIDEBAND_MAX_ENTITIES = 24;
    private static final long SIDEBAND_ENTITY_INTERVAL_TICKS = 2L;

    private final NetworkManager network;
    private final Map<UUID, Session> sessions = new ConcurrentHashMap<>();
    private final Map<UUID, TicketLease> gatewayTickets = new ConcurrentHashMap<>();
    private final Map<ChunkTicketKey, TicketHold> chunkTickets = new HashMap<>();
    private final Map<BlockData, String> blockDataStrings = new ConcurrentHashMap<>();
    private final ChunkBulkBuilder chunkBulkBuilder;
    private final AtomicBoolean taskRunning = new AtomicBoolean(false);
    private final AtomicLong entitySendCount = new AtomicLong();
    private final AtomicLong timeSendCount = new AtomicLong();
    private final PreShipPredictor preShipPredictor = new PreShipPredictor();
    private volatile EntityRateScheduler entityRateScheduler;
    private volatile EntityRateScheduler.Bands lastBands;
    private long tickCounter;

    private static final class Session {
        private final UUID portalId;
        private final World world;
        private final ViewBox box;
        private final int centerChunkX;
        private final int centerChunkZ;
        private final double portalCenterX;
        private final double portalCenterY;
        private final double portalCenterZ;
        private final List<long[]> columns;
        private final Set<String> peers = ConcurrentHashMap.newKeySet();
        private final Set<UUID> sentProfiles = ConcurrentHashMap.newKeySet();
        private final Map<String, Map<UUID, EntitySendState>> sendStates = new ConcurrentHashMap<>();
        private final Map<String, Set<UUID>> lastSentPresentIds = new ConcurrentHashMap<>();
        private final Map<String, Long> sidebandEntityNextTick = new ConcurrentHashMap<>();
        private final Map<UUID, EntityVisual> lastCapturedSnapshots = new ConcurrentHashMap<>();
        private final AtomicBoolean entityCaptureRunning = new AtomicBoolean(false);
        private volatile TicketLease ticketLease;
        private volatile int lastSkyDarken = -1;

        private Session(UUID portalId, World world, ViewBox box, int centerChunkX, int centerChunkZ,
                        double portalCenterX, double portalCenterY, double portalCenterZ) {
            this.portalId = portalId;
            this.world = world;
            this.box = box;
            this.centerChunkX = centerChunkX;
            this.centerChunkZ = centerChunkZ;
            this.portalCenterX = portalCenterX;
            this.portalCenterY = portalCenterY;
            this.portalCenterZ = portalCenterZ;
            this.columns = columnsFor(box);
        }

        private Map<UUID, EntitySendState> sendStatesFor(String peerName) {
            return sendStates.computeIfAbsent(peerName, name -> new ConcurrentHashMap<>());
        }
    }

    private record ChunkTicketKey(UUID worldId, int chunkX, int chunkZ) {
    }

    private static final class TicketHold {
        private final World world;
        private final int chunkX;
        private final int chunkZ;
        private int references;
        private boolean applied;
        private boolean applying;

        private TicketHold(World world, int chunkX, int chunkZ) {
            this.world = world;
            this.chunkX = chunkX;
            this.chunkZ = chunkZ;
            this.references = 1;
            this.applied = false;
            this.applying = false;
        }
    }

    private static final class TicketLease {
        private final UUID portalId;
        private final World world;
        private final ViewBox box;
        private final List<long[]> columns;
        private final AtomicBoolean released = new AtomicBoolean(false);

        private TicketLease(UUID portalId, World world, ViewBox box) {
            this.portalId = portalId;
            this.world = world;
            this.box = box;
            this.columns = columnsFor(box);
        }

        private boolean matches(World candidateWorld, ViewBox candidateBox) {
            return world.equals(candidateWorld) && box.equals(candidateBox);
        }
    }

    public ViewServer(NetworkManager network) {
        this.network = network;
        this.chunkBulkBuilder = new ChunkBulkBuilder(blockDataStrings);
    }

    public static ViewBox computeBox(ILocalPortal portal, int radius) {
        AxisAlignedBB area = portal.getStructure().getArea();
        World world = portal.getStructure().getWorld();
        int minX = (int) Math.floor(Math.min(area.getXa(), area.getXb())) - radius;
        int minY = (int) Math.floor(Math.min(area.getYa(), area.getYb())) - radius;
        int minZ = (int) Math.floor(Math.min(area.getZa(), area.getZb())) - radius;
        int maxX = (int) Math.floor(Math.max(area.getXa(), area.getXb())) + radius;
        int maxY = (int) Math.floor(Math.max(area.getYa(), area.getYb())) + radius;
        int maxZ = (int) Math.floor(Math.max(area.getZa(), area.getZb())) + radius;
        minY = Math.max(minY, world.getMinHeight());
        maxY = Math.min(maxY, world.getMaxHeight() - 1);
        return new ViewBox(minX, minY, minZ, maxX, maxY, maxZ);
    }

    public void onSubscribe(String peerName, UUID portalId) {
        ILocalPortal portal = Wormholes.portalManager == null ? null : Wormholes.portalManager.getLocalPortal(portalId);
        if (portal == null || portal.getStructure() == null || portal.getStructure().getWorld() == null) {
            return;
        }
        Session session = sessions.computeIfAbsent(portalId, id -> new Session(
            id,
            portal.getStructure().getWorld(),
            computeBox(portal, portal.getNetworkViewDepth()),
            ((int) Math.floor(portal.getOrigin().getX())) >> 4,
            ((int) Math.floor(portal.getOrigin().getZ())) >> 4,
            portal.getOrigin().getX(),
            portal.getOrigin().getY(),
            portal.getOrigin().getZ()
        ));
        retainSessionTickets(session);
        session.peers.add(peerName);
        session.sentProfiles.clear();
        session.sendStates.remove(peerName);
        ChunkReplicationManager replication = network.getReplicationManager();
        for (long[] column : session.columns) {
            replication.subscribe(peerName, session.world, ViewSlice.columnKey((int) column[0], (int) column[1]));
        }
        int totalColumns = session.columns.size();
        if (totalColumns == 0) {
            network.send(peerName, new WireMessage.ViewBulkComplete(portalId));
            startTask();
            return;
        }
        AtomicInteger remainingBulks = new AtomicInteger(totalColumns);
        int columnIndex = 0;
        for (long[] column : session.columns) {
            int chunkX = (int) column[0];
            int chunkZ = (int) column[1];
            long delayTicks = (long) (columnIndex / MAX_BULK_SNAPSHOTS_PER_TICK) * DIRTY_DRAIN_INTERVAL_TICKS;
            columnIndex++;
            FoliaScheduler.runAsync(Wormholes.instance, () -> {
                if (!session.peers.contains(peerName)) {
                    remainingBulks.decrementAndGet();
                    return;
                }
                sendInitialBulk(session, peerName, chunkX, chunkZ).whenComplete((unused, error) -> {
                    if (remainingBulks.decrementAndGet() == 0 && session.peers.contains(peerName)) {
                        network.send(peerName, new WireMessage.ViewBulkComplete(portalId));
                    }
                });
            }, delayTicks);
        }
        startTask();
    }

    public void onUnsubscribe(String peerName, UUID portalId) {
        Session session = sessions.get(portalId);
        if (session == null) {
            return;
        }
        session.peers.remove(peerName);
        session.sendStates.remove(peerName);
        session.lastSentPresentIds.remove(peerName);
        EntityRateScheduler scheduler = entityRateScheduler;
        if (scheduler != null) {
            scheduler.clearSubscriber(peerName);
        }
        ChunkReplicationManager replication = network.getReplicationManager();
        for (long[] column : session.columns) {
            int chunkX = (int) column[0];
            int chunkZ = (int) column[1];
            long chunkKey = ViewSlice.columnKey(chunkX, chunkZ);
            replication.unsubscribe(peerName, chunkKey);
        }
        if (session.peers.isEmpty()) {
            sessions.remove(portalId);
            releaseSessionTickets(session);
        }
    }

    public void onChunkResyncRequest(String peerName, ChunkResyncRequest request) {
        ChunkReplicationManager replication = network.getReplicationManager();
        if (!replication.isBulked(peerName, request.chunkKey())) {
            // The initial bulk for this chunk is still in flight; an early diff merely outran it. The
            // pending bulk will deliver current state, so do NOT re-bulk from a (stale) fresh snapshot
            // here -- that is the spurious resync loop that was clobbering live block edits.
            return;
        }
        replication.requestResync(peerName, request.chunkKey());
        for (Session session : sessions.values()) {
            if (!session.peers.contains(peerName)) {
                continue;
            }
            int chunkX = (int) (request.chunkKey() >> 32);
            int chunkZ = (int) request.chunkKey();
            if (!sessionContainsChunk(session, chunkX, chunkZ)) {
                continue;
            }
            sendInitialBulk(session, peerName, chunkX, chunkZ);
            return;
        }
    }

    public void requestChunkResync(String peerName, long chunkKey, long expectedSequence) {
        onChunkResyncRequest(peerName, new ChunkResyncRequest(chunkKey, expectedSequence));
    }

    private boolean sessionContainsChunk(Session session, int chunkX, int chunkZ) {
        for (long[] column : session.columns) {
            if ((int) column[0] == chunkX && (int) column[1] == chunkZ) {
                return true;
            }
        }
        return false;
    }

    public void refreshPortal(ILocalPortal portal) {
        if (portal == null || portal.getId() == null) {
            return;
        }
        Session removed = sessions.remove(portal.getId());
        List<String> peers = new ArrayList<>();
        if (removed != null) {
            peers.addAll(removed.peers);
            releaseSessionTickets(removed);
        }
        releaseGatewayTicket(portal.getId());
        if (isTicketedGateway(portal)) {
            retainGatewayTickets(portal);
        }
        for (String peer : peers) {
            onSubscribe(peer, portal.getId());
        }
    }

    public void onPeerDisconnected(String peerName) {
        for (Session session : sessions.values()) {
            onUnsubscribe(peerName, session.portalId);
        }
    }

    public void shutdown() {
        for (Session session : sessions.values()) {
            releaseSessionTickets(session);
        }
        sessions.clear();
        releaseAllGatewayTickets();
        releaseAllChunkTickets();
    }

    public void syncGatewayTickets() {
        if (Wormholes.settings == null || Wormholes.portalManager == null || !Wormholes.settings.getNetwork().enabled) {
            releaseAllGatewayTickets();
            return;
        }
        Set<UUID> active = new HashSet<>();
        for (ILocalPortal portal : Wormholes.portalManager.getLocalPortals()) {
            if (!isTicketedGateway(portal)) {
                continue;
            }
            active.add(portal.getId());
            retainGatewayTickets(portal);
        }
        releaseMissingGatewayTickets(active);
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

        ChunkReplicationManager replication = network.getReplicationManager();
        replication.onTickEnd();

        runPreShipPredictor();

        for (Session session : sessions.values()) {
            ILocalPortal portal = Wormholes.portalManager == null ? null : Wormholes.portalManager.getLocalPortal(session.portalId);
            if (portal == null) {
                sessions.remove(session.portalId);
                releaseSessionTickets(session);
                continue;
            }
            boolean entitiesDue = isIntervalDue(portal.getNetworkViewEntityIntervalTicks());
            if (entitiesDue && session.entityCaptureRunning.compareAndSet(false, true)) {
                FoliaScheduler.runRegion(Wormholes.instance, session.world, session.centerChunkX, session.centerChunkZ, () -> captureEntities(session));
            }
        }
    }

    private void runPreShipPredictor() {
        if (Wormholes.settings == null || Wormholes.portalManager == null) {
            return;
        }
        NetworkConfig networkConfig = Wormholes.settings.getNetwork();
        if (networkConfig == null || networkConfig.view == null) {
            return;
        }
        ViewSubscriptionManager subscriptions = Wormholes.viewSubscriptions;
        if (subscriptions == null) {
            return;
        }
        PreShipPredictor.Settings settings = preShipSettings(networkConfig.view);
        if (!settings.enabled()) {
            return;
        }
        long nowMillis = System.currentTimeMillis();
        ChunkReplicationManager replication = network.getReplicationManager();
        for (Session session : sessions.values()) {
            org.bukkit.Location origin = new org.bukkit.Location(session.world, session.portalCenterX, session.portalCenterY, session.portalCenterZ);
            List<art.arcane.wormholes.PortalManager.GatewayPortalInfo> nearby = Wormholes.portalManager.listGatewayPortalsNear(origin, settings.distance());
            if (nearby.isEmpty()) {
                continue;
            }
            List<PreShipPredictor.GatewayInfo> gateways = new ArrayList<>(nearby.size());
            for (art.arcane.wormholes.PortalManager.GatewayPortalInfo info : nearby) {
                gateways.add(new PreShipPredictor.GatewayInfo(info.portalId(), info.centerX(), info.centerY(), info.centerZ(), info.normalX(), info.normalY(), info.normalZ()));
            }
            PreShipPredictor.GatewayAccessor accessor = (x, z, radius) -> gateways;
            for (String peerName : session.peers) {
                ViewSubscriptionManager.SubscriberPose subscriberPose = subscriptions.getSubscriberPose(peerName);
                if (subscriberPose == null) {
                    continue;
                }
                PreShipPredictor.PlayerPose pose = new PreShipPredictor.PlayerPose(
                    session.portalId, peerName,
                    subscriberPose.x(), subscriberPose.y(), subscriberPose.z(),
                    subscriberPose.forwardX(), subscriberPose.forwardY(), subscriberPose.forwardZ());
                List<PreShipPredictor.PreShipTicket> opened = preShipPredictor.tick(pose, accessor, settings, nowMillis);
                for (PreShipPredictor.PreShipTicket ticket : opened) {
                    if (ticket.isPromoted()) {
                        continue;
                    }
                    List<long[]> ticketColumns = sessionColumnsForPortal(ticket.getPortalId());
                    if (ticketColumns.isEmpty()) {
                        continue;
                    }
                    List<Long> chunkKeys = new ArrayList<>(ticketColumns.size());
                    for (long[] column : ticketColumns) {
                        chunkKeys.add(ViewSlice.columnKey((int) column[0], (int) column[1]));
                    }
                    replication.subscribePreShip(peerName, ticket.getPortalId(), session.world, chunkKeys);
                }
            }
            List<PreShipPredictor.PreShipTicket> cancelled = preShipPredictor.sweepCanceled(settings, nowMillis);
            for (PreShipPredictor.PreShipTicket ticket : cancelled) {
                replication.cancelPreShip(ticket.getSubscriberId(), ticket.getPortalId());
            }
        }
    }

    private List<long[]> sessionColumnsForPortal(UUID portalId) {
        Session session = sessions.get(portalId);
        if (session == null) {
            return List.of();
        }
        return session.columns;
    }

    private static PreShipPredictor.Settings preShipSettings(NetworkConfig.ViewConfig view) {
        return new PreShipPredictor.Settings(view.preshipEnabled, view.preshipDistance, view.preshipMinSpeed, view.preshipRateFraction, view.preshipCancelGraceSeconds);
    }

    private void captureEntities(Session session) {
        try {
            int skyDarken = art.arcane.wormholes.render.view.ProjectionWorldView.computeSkyDarken(session.world.getTime());
            if (skyDarken != session.lastSkyDarken) {
                session.lastSkyDarken = skyDarken;
                WireMessage.ViewTime time = new WireMessage.ViewTime(session.portalId, skyDarken);
                for (String peerName : session.peers) {
                    network.send(peerName, time);
                    timeSendCount.incrementAndGet();
                }
            }
            long entityTick = tickCounter;
            NetworkConfig.ViewConfig viewConfig = activeViewConfig();
            EntityRateScheduler scheduler = ensureScheduler(viewConfig);
            int missesBeforeResync = Math.max(1, viewConfig.entityDeltaMissesBeforeResync);
            double velocityThreshold = Math.max(0.0D, viewConfig.entityDeltaVelocityThreshold);
            boolean deltaEnabled = viewConfig.entityDeltaEnabled;

            BoundingBox bounds = new BoundingBox(session.box.minX(), session.box.minY(), session.box.minZ(),
                session.box.maxX() + 1, session.box.maxY() + 1, session.box.maxZ() + 1);
            List<EntityVisual> visuals = new ArrayList<>(16);
            Set<UUID> presentIds = ConcurrentHashMap.newKeySet();
            for (Entity entity : session.world.getNearbyEntities(bounds)) {
                if (visuals.size() >= 64) {
                    break;
                }
                if (entity.isDead() || !entity.isValid()) {
                    continue;
                }
                EntityVisual currentFull = captureEntityVisualFull(session, entity);
                visuals.add(currentFull);
                presentIds.add(currentFull.id());
                session.lastCapturedSnapshots.put(currentFull.id(), currentFull);
            }
            session.sentProfiles.retainAll(presentIds);
            session.lastCapturedSnapshots.keySet().retainAll(presentIds);

            Set<UUID> sidebandAllowed = null;
            for (String peerName : session.peers) {
                boolean sideband = network.isSidebandOnlyPeer(peerName);
                if (sideband) {
                    Long nextTick = session.sidebandEntityNextTick.get(peerName);
                    if (nextTick != null && entityTick < nextTick.longValue()) {
                        continue;
                    }
                    session.sidebandEntityNextTick.put(peerName, entityTick + SIDEBAND_ENTITY_INTERVAL_TICKS);
                    if (sidebandAllowed == null) {
                        sidebandAllowed = nearestEntityIds(session, visuals, SIDEBAND_MAX_ENTITIES);
                    }
                } else {
                    session.sidebandEntityNextTick.remove(peerName);
                }
                Map<UUID, EntitySendState> peerStates = session.sendStatesFor(peerName);
                peerStates.keySet().retainAll(presentIds);
                List<EntityVisual> outbound = new ArrayList<>(visuals.size());
                for (EntityVisual currentFull : visuals) {
                    if (sideband && !sidebandAllowed.contains(currentFull.id())) {
                        continue;
                    }
                    boolean rateAllowsSend = scheduler.shouldSend(
                        peerName, currentFull.id(),
                        session.portalCenterX, session.portalCenterY, session.portalCenterZ,
                        currentFull.x(), currentFull.y(), currentFull.z(),
                        entityTick
                    );
                    EntitySendState state = peerStates.computeIfAbsent(currentFull.id(), EntitySendState::new);
                    boolean forceFull = !deltaEnabled || state.isForceFullNext() || state.getLastSentSnapshot() == null;
                    if (!rateAllowsSend && !forceFull) {
                        continue;
                    }
                    int sequence = state.allocateSequence();
                    EntityVisual outboundVisual;
                    if (forceFull) {
                        outboundVisual = withSequenceAndMode(currentFull, sequence, EntityVisual.MODE_FULL);
                        state.recordSent(currentFull, true);
                    } else {
                        EntityVisual delta = EntityDeltaCodec.buildDelta(currentFull, state.getLastSentSnapshot(), sequence, velocityThreshold);
                        outboundVisual = delta;
                        state.recordSent(currentFull, false);
                        state.recordMiss(missesBeforeResync);
                    }
                    outbound.add(outboundVisual);
                }
                Set<UUID> previousPresent = session.lastSentPresentIds.get(peerName);
                boolean presentChanged = previousPresent == null || !previousPresent.equals(presentIds);
                if (Settings.DEBUG && presentChanged && previousPresent != null) {
                    Set<UUID> left = new HashSet<>(previousPresent);
                    left.removeAll(presentIds);
                    Set<UUID> joined = new HashSet<>(presentIds);
                    joined.removeAll(previousPresent);
                    if (!left.isEmpty() || !joined.isEmpty()) {
                        Wormholes.v("[stream] portal=" + session.portalId + " peer=" + peerName + " present=" + presentIds.size() + (left.isEmpty() ? "" : " LEFT=" + left) + (joined.isEmpty() ? "" : " JOINED=" + joined));
                    }
                }
                if (outbound.isEmpty() && !presentChanged) {
                    continue;
                }
                session.lastSentPresentIds.put(peerName, new HashSet<>(presentIds));
                WireMessage.ViewEntities message = new WireMessage.ViewEntities(session.portalId, outbound, new ArrayList<>(presentIds));
                network.send(peerName, message);
                entitySendCount.addAndGet(outbound.size());
            }
        } catch (Throwable e) {
            Wormholes.v("net: entity view capture failed for portal " + session.portalId + ": " + e.getMessage());
        } finally {
            session.entityCaptureRunning.set(false);
        }
    }

    private EntityVisual captureEntityVisualFull(Session session, Entity entity) {
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
        UUID passengerOf = entity.getVehicle() == null ? null : entity.getVehicle().getUniqueId();
        UUID leashHolder = null;
        if (entity instanceof LivingEntity living && living.isLeashed()) {
            try {
                Entity holder = living.getLeashHolder();
                if (holder != null) {
                    leashHolder = holder.getUniqueId();
                }
            } catch (IllegalStateException ignored) {
            }
        }
        byte[] metadata = PacketBlobs.captureMetadata(entity);
        byte[] equipment = PacketBlobs.captureEquipment(entity);
        return EntityVisual.full(
            entity.getUniqueId(),
            entity.getType().getKey().toString(),
            location.getX(), location.getY(), location.getZ(),
            entity.getHeight(),
            look.getX(), look.getY(), look.getZ(),
            location.getYaw(), location.getPitch(),
            velocity.getX(), velocity.getY(), velocity.getZ(),
            entity.isOnGround(),
            playerName,
            textureValue,
            textureSignature,
            passengerOf,
            leashHolder,
            metadata,
            equipment,
            0
        );
    }

    private static EntityVisual withSequenceAndMode(EntityVisual source, int sequence, byte mode) {
        return new EntityVisual(
            mode,
            sequence,
            source.presentMask(),
            source.id(),
            source.typeKey(),
            source.x(), source.y(), source.z(),
            source.height(),
            source.lookX(), source.lookY(), source.lookZ(),
            source.yaw(), source.pitch(),
            source.velocityX(), source.velocityY(), source.velocityZ(),
            source.onGround(),
            source.playerName(),
            source.textureValue(),
            source.textureSignature(),
            source.passengerOf(),
            source.leashHolder(),
            source.metadata(),
            source.equipment()
        );
    }

    private static Set<UUID> nearestEntityIds(Session session, List<EntityVisual> visuals, int max) {
        Set<UUID> nearest = new HashSet<>(Math.min(visuals.size(), max));
        if (visuals.size() <= max) {
            for (EntityVisual visual : visuals) {
                nearest.add(visual.id());
            }
            return nearest;
        }
        List<EntityVisual> sorted = new ArrayList<>(visuals);
        sorted.sort(java.util.Comparator.comparingDouble(visual -> {
            double dx = visual.x() - session.portalCenterX;
            double dy = visual.y() - session.portalCenterY;
            double dz = visual.z() - session.portalCenterZ;
            return (dx * dx) + (dy * dy) + (dz * dz);
        }));
        for (int i = 0; i < max; i++) {
            nearest.add(sorted.get(i).id());
        }
        return nearest;
    }

    private NetworkConfig.ViewConfig activeViewConfig() {
        if (Wormholes.settings == null) {
            return new NetworkConfig.ViewConfig();
        }
        NetworkConfig network = Wormholes.settings.getNetwork();
        if (network == null || network.view == null) {
            return new NetworkConfig.ViewConfig();
        }
        return network.view;
    }

    private EntityRateScheduler ensureScheduler(NetworkConfig.ViewConfig view) {
        EntityRateScheduler.Bands desiredBands = new EntityRateScheduler.Bands(
            view.entityRateNearRange, view.entityRateMidRange, view.entityRateFarRange,
            view.entityRateNearHz, view.entityRateMidHz, view.entityRateFarHz, view.entityRateVeryFarHz
        );
        EntityRateScheduler current = entityRateScheduler;
        if (current != null && desiredBands.equals(lastBands)) {
            return current;
        }
        EntityRateScheduler fresh = new EntityRateScheduler(desiredBands);
        entityRateScheduler = fresh;
        lastBands = desiredBands;
        return fresh;
    }

    public EntityRateScheduler getEntityRateScheduler() {
        return entityRateScheduler;
    }

    public PreShipPredictor getPreShipPredictor() {
        return preShipPredictor;
    }

    public void onPortalTraversed(String peerName, UUID destinationPortalId) {
        if (peerName == null || destinationPortalId == null) {
            return;
        }
        preShipPredictor.promote(peerName, destinationPortalId);
        ChunkReplicationManager replication = network.getReplicationManager();
        replication.promotePreShip(peerName, destinationPortalId);
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
            if (session.lastCapturedSnapshots.containsKey(entityId)) {
                WireMessage.ViewEntityAnimation message = new WireMessage.ViewEntityAnimation(session.portalId, entityId, hurt, animationOrdinal, yaw);
                for (String peerName : session.peers) {
                    network.send(peerName, message);
                }
            }
        }
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

    private CompletableFuture<Void> sendInitialBulk(Session session, String peerName, int chunkX, int chunkZ) {
        ChunkReplicationManager replication = network.getReplicationManager();
        long chunkKey = ViewSlice.columnKey(chunkX, chunkZ);
        CompletableFuture<Void> done = new CompletableFuture<>();
        session.world.getChunkAtAsync(chunkX, chunkZ).whenComplete((chunk, error) -> {
            if (error != null || chunk == null) {
                done.complete(null);
                return;
            }
            ChunkSnapshot snapshot = chunk.getChunkSnapshot(false, true, false, true);
            FoliaScheduler.runAsync(Wormholes.instance, () -> {
                try {
                    ViewSlice slice = chunkBulkBuilder.buildSlice(session.box, chunkX, chunkZ, snapshot);
                    if (slice == null) {
                        return;
                    }
                    byte[] payload;
                    try {
                        payload = ChunkBulkBuilder.encodeSliceBytes(slice);
                    } catch (java.io.IOException e) {
                        Wormholes.v("net: failed to encode chunk bulk for " + peerName + " (" + chunkX + "," + chunkZ + "): " + e.getMessage());
                        return;
                    }
                    replication.sendBulk(peerName, chunkKey, payload);
                    if (session.lastSkyDarken >= 0) {
                        network.send(peerName, new WireMessage.ViewTime(session.portalId, session.lastSkyDarken));
                        timeSendCount.incrementAndGet();
                    }
                } finally {
                    done.complete(null);
                }
            });
        });
        return done;
    }

    private void retainGatewayTickets(ILocalPortal portal) {
        World world = portal.getStructure().getWorld();
        ViewBox box = computeBox(portal, portal.getNetworkViewDepth());
        TicketLease previous;
        TicketLease next;
        synchronized (gatewayTickets) {
            previous = gatewayTickets.get(portal.getId());
            if (previous != null && previous.matches(world, box)) {
                ensureTicketLease(previous);
                return;
            }
            next = retainTicketLease(portal.getId(), world, box);
            gatewayTickets.put(portal.getId(), next);
        }
        if (previous != null) {
            releaseTicketLease(previous);
        }
    }

    private void releaseGatewayTicket(UUID portalId) {
        TicketLease removed;
        synchronized (gatewayTickets) {
            removed = gatewayTickets.remove(portalId);
        }
        if (removed != null) {
            releaseTicketLease(removed);
        }
    }

    private boolean isIntervalDue(int intervalTicks) {
        long interval = Math.max(DIRTY_DRAIN_INTERVAL_TICKS, intervalTicks);
        long steps = Math.max(1L, interval / DIRTY_DRAIN_INTERVAL_TICKS);
        long normalized = steps * DIRTY_DRAIN_INTERVAL_TICKS;
        return tickCounter % normalized < DIRTY_DRAIN_INTERVAL_TICKS;
    }

    private void releaseMissingGatewayTickets(Set<UUID> active) {
        List<TicketLease> removed = new ArrayList<>();
        synchronized (gatewayTickets) {
            for (Map.Entry<UUID, TicketLease> entry : gatewayTickets.entrySet()) {
                if (active.contains(entry.getKey())) {
                    continue;
                }
                removed.add(entry.getValue());
            }
            for (TicketLease lease : removed) {
                gatewayTickets.remove(lease.portalId, lease);
            }
        }
        for (TicketLease lease : removed) {
            releaseTicketLease(lease);
        }
    }

    private void retainSessionTickets(Session session) {
        synchronized (session) {
            if (session.ticketLease != null) {
                ensureTicketLease(session.ticketLease);
                return;
            }
            session.ticketLease = retainTicketLease(session.portalId, session.world, session.box);
        }
    }

    private void releaseSessionTickets(Session session) {
        TicketLease lease;
        synchronized (session) {
            lease = session.ticketLease;
            session.ticketLease = null;
        }
        if (lease != null) {
            releaseTicketLease(lease);
        }
    }

    private TicketLease retainTicketLease(UUID portalId, World world, ViewBox box) {
        TicketLease lease = new TicketLease(portalId, world, box);
        for (long[] column : lease.columns) {
            retainChunkTicket(world, (int) column[0], (int) column[1]);
        }
        return lease;
    }

    private void releaseTicketLease(TicketLease lease) {
        if (!lease.released.compareAndSet(false, true)) {
            return;
        }
        for (long[] column : lease.columns) {
            int chunkX = (int) column[0];
            int chunkZ = (int) column[1];
            releaseChunkTicket(lease.world, chunkX, chunkZ);
        }
    }

    private void retainChunkTicket(World world, int chunkX, int chunkZ) {
        ChunkTicketKey key = new ChunkTicketKey(world.getUID(), chunkX, chunkZ);
        synchronized (chunkTickets) {
            TicketHold existing = chunkTickets.get(key);
            if (existing != null) {
                existing.references++;
                return;
            }
            TicketHold hold = new TicketHold(world, chunkX, chunkZ);
            chunkTickets.put(key, hold);
            hold.applying = true;
            applyChunkTicket(key, hold);
        }
    }

    private void ensureTicketLease(TicketLease lease) {
        for (long[] column : lease.columns) {
            ensureChunkTicket(lease.world, (int) column[0], (int) column[1]);
        }
    }

    private void ensureChunkTicket(World world, int chunkX, int chunkZ) {
        ChunkTicketKey key = new ChunkTicketKey(world.getUID(), chunkX, chunkZ);
        TicketHold hold;
        synchronized (chunkTickets) {
            hold = chunkTickets.get(key);
            if (hold == null || hold.applied || hold.applying || hold.references <= 0) {
                return;
            }
            hold.applying = true;
        }
        applyChunkTicket(key, hold);
    }

    private void applyChunkTicket(ChunkTicketKey key, TicketHold hold) {
        hold.world.getChunkAtAsync(hold.chunkX, hold.chunkZ).whenComplete((chunk, error) -> {
            if (error != null || chunk == null) {
                synchronized (chunkTickets) {
                    hold.applying = false;
                    if (chunkTickets.get(key) == hold && hold.references <= 0) {
                        chunkTickets.remove(key);
                    }
                }
                return;
            }
            FoliaScheduler.runRegion(Wormholes.instance, hold.world, hold.chunkX, hold.chunkZ, () -> {
                synchronized (chunkTickets) {
                    if (chunkTickets.get(key) != hold || hold.references <= 0) {
                        return;
                    }
                }
                chunk.addPluginChunkTicket(Wormholes.instance);
                boolean keepTicket;
                synchronized (chunkTickets) {
                    keepTicket = chunkTickets.get(key) == hold && hold.references > 0;
                    if (keepTicket) {
                        hold.applied = true;
                    }
                    hold.applying = false;
                }
                if (!keepTicket) {
                    chunk.removePluginChunkTicket(Wormholes.instance);
                }
            });
        });
    }

    private void releaseChunkTicket(World world, int chunkX, int chunkZ) {
        ChunkTicketKey key = new ChunkTicketKey(world.getUID(), chunkX, chunkZ);
        boolean removeTicket = false;
        synchronized (chunkTickets) {
            TicketHold hold = chunkTickets.get(key);
            if (hold == null) {
                return;
            }
            hold.references--;
            if (hold.references > 0) {
                return;
            }
            chunkTickets.remove(key);
            removeTicket = hold.applied;
        }
        if (removeTicket) {
            FoliaScheduler.runRegion(Wormholes.instance, world, chunkX, chunkZ, () -> {
                if (world.isChunkLoaded(chunkX, chunkZ)) {
                    world.getChunkAt(chunkX, chunkZ).removePluginChunkTicket(Wormholes.instance);
                }
            });
        }
    }

    private void releaseAllGatewayTickets() {
        List<TicketLease> leases;
        synchronized (gatewayTickets) {
            leases = new ArrayList<>(gatewayTickets.values());
            gatewayTickets.clear();
        }
        for (TicketLease lease : leases) {
            releaseTicketLease(lease);
        }
    }

    private void releaseAllChunkTickets() {
        List<TicketHold> holds;
        synchronized (chunkTickets) {
            holds = new ArrayList<>(chunkTickets.values());
            chunkTickets.clear();
        }
        for (TicketHold hold : holds) {
            if (!hold.applied) {
                continue;
            }
            FoliaScheduler.runRegion(Wormholes.instance, hold.world, hold.chunkX, hold.chunkZ, () -> {
                if (hold.world.isChunkLoaded(hold.chunkX, hold.chunkZ)) {
                    hold.world.getChunkAt(hold.chunkX, hold.chunkZ).removePluginChunkTicket(Wormholes.instance);
                }
            });
        }
    }

    private static boolean isTicketedGateway(ILocalPortal portal) {
        return portal != null
            && portal.isGateway()
            && portal.getStructure() != null
            && portal.getStructure().getWorld() != null
            && portal.getStructure().getArea() != null;
    }

    private static List<long[]> columnsFor(ViewBox box) {
        List<long[]> columns = new ArrayList<>();
        for (int cx = box.minX() >> 4; cx <= box.maxX() >> 4; cx++) {
            for (int cz = box.minZ() >> 4; cz <= box.maxZ() >> 4; cz++) {
                columns.add(new long[]{cx, cz});
            }
        }
        return columns;
    }

    public Stats statsSnapshot() {
        int totalSubscriptions = 0;
        for (Session session : sessions.values()) {
            totalSubscriptions += session.peers.size();
        }
        EntityRateScheduler scheduler = entityRateScheduler;
        int tracked = scheduler == null ? 0 : scheduler.trackedEntityCount();
        ChunkReplicationManager.Stats replicationStats = network.getReplicationManager().statsSnapshot();
        return new Stats(
            totalSubscriptions,
            tracked,
            replicationStats.bulkSent(),
            replicationStats.diffsSent(),
            entitySendCount.get(),
            timeSendCount.get()
        );
    }

    public int sessionCount() {
        return sessions.size();
    }
}
