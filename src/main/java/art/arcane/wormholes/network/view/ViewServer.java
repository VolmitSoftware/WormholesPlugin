package art.arcane.wormholes.network.view;

import art.arcane.volmlib.util.scheduling.FoliaScheduler;

import com.github.retrooper.packetevents.PacketEvents;
import com.github.retrooper.packetevents.protocol.player.TextureProperty;
import com.github.retrooper.packetevents.protocol.player.UserProfile;
import art.arcane.wormholes.EffectManager;
import art.arcane.wormholes.Settings;
import art.arcane.wormholes.Wormholes;
import art.arcane.wormholes.chunk.BukkitChunkLeaseProvider;
import art.arcane.wormholes.chunk.ChunkLease;
import art.arcane.wormholes.chunk.ChunkLeaseRegistry;
import art.arcane.wormholes.config.toml.NetworkConfig;
import art.arcane.wormholes.network.NetworkManager;
import art.arcane.wormholes.network.WireMessage;
import art.arcane.wormholes.network.replication.ChunkBulkBuilder;
import art.arcane.wormholes.network.replication.ChunkReplicationManager;
import art.arcane.wormholes.network.replication.ChunkResyncRequest;
import art.arcane.wormholes.platform.WormholesPlatform;
import art.arcane.wormholes.portal.ILocalPortal;
import art.arcane.wormholes.util.AxisAlignedBB;

import org.bukkit.Bukkit;
import org.bukkit.ChunkSnapshot;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.block.data.BlockData;
import org.bukkit.entity.Entity;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.entity.Pose;
import org.bukkit.event.Listener;
import org.bukkit.inventory.EntityEquipment;
import org.bukkit.inventory.ItemStack;
import org.bukkit.util.BoundingBox;
import org.bukkit.util.Vector;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.logging.Level;

public final class ViewServer implements Listener {
    public record Stats(int subscriptions, int trackedEntities, long chunkBulkSentCount, long chunkDiffSentCount, long entitySendCount, long timeSendCount) {
    }

    private static final long DIRTY_DRAIN_INTERVAL_TICKS = 2L;
    private static final int MAX_BULK_SNAPSHOTS_PER_TICK = 8;
    private static final int MAX_CAPTURED_ENTITIES = 64;
    private static final long ENTITY_CAPTURE_DEADLINE_MILLIS = 10_000L;
    private static final int SIDEBAND_MAX_ENTITIES = 24;
    private static final long SIDEBAND_ENTITY_INTERVAL_TICKS = 2L;
    private static final long SIDEBAND_FULL_RESYNC_TICKS = 80L;
    private static final long SIDEBAND_FULL_RESYNC_JITTER_TICKS = 40L;
    private static final long BLOB_RECAPTURE_INTERVAL_TICKS = 40L;
    private static final long MAX_BULK_RETRY_DELAY_TICKS = 40L;
    private static final long BULK_COMPLETE_RETRY_DELAY_TICKS = 5L;
    private static final long VIEW_TIME_RETRY_DELAY_TICKS = 5L;

    private final NetworkManager network;
    private final Map<UUID, Session> sessions = new ConcurrentHashMap<>();
    private final Map<UUID, TicketLease> gatewayTickets = new ConcurrentHashMap<>();
    private final Map<BlockData, String> blockDataStrings = new ConcurrentHashMap<>();
    private final ChunkBulkBuilder chunkBulkBuilder;
    private final AtomicBoolean active = new AtomicBoolean(true);
    private final AtomicBoolean taskRunning = new AtomicBoolean(false);
    private final AtomicLong entitySendCount = new AtomicLong();
    private final AtomicLong timeSendCount = new AtomicLong();
    private final PreShipPredictor preShipPredictor = new PreShipPredictor();
    private final BulkRetryCoordinator<BulkRetryKey> bulkRetryCoordinator = new BulkRetryCoordinator<>(MAX_BULK_RETRY_DELAY_TICKS);
    private final Set<BulkCompleteKey> bulkCompleteRetries = ConcurrentHashMap.newKeySet();
    private volatile EntityRateScheduler entityRateScheduler;
    private volatile EntityRateScheduler.Bands lastBands;
    private volatile NetworkConfig.ViewConfig cachedPreShipView;
    private volatile PreShipPredictor.Settings cachedPreShipSettings;
    private volatile long tickCounter;

    private static final class Session {
        private final UUID portalId;
        private final UUID subscriptionId;
        private final World world;
        private final ViewBox box;
        private final int centerChunkX;
        private final int centerChunkZ;
        private final double portalCenterX;
        private final double portalCenterY;
        private final double portalCenterZ;
        private final List<long[]> columns;
        private final List<Long> chunkKeys;
        private final BoundingBox bounds;
        private final Set<String> peers = ConcurrentHashMap.newKeySet();
        private final Set<UUID> sentProfiles = ConcurrentHashMap.newKeySet();
        private final Map<String, Map<UUID, EntitySendState>> sendStates = new ConcurrentHashMap<>();
        private final Map<String, Set<UUID>> lastSentPresentIds = new ConcurrentHashMap<>();
        private final Map<String, Long> sidebandEntityNextTick = new ConcurrentHashMap<>();
        private final Map<String, Boolean> lastPeerSideband = new ConcurrentHashMap<>();
        private final Map<String, TimeDeliveryState> timeDeliveryStates = new ConcurrentHashMap<>();
        private final Map<UUID, EntityVisual> lastCapturedSnapshots = new ConcurrentHashMap<>();
        private final Map<UUID, BlobCaptureState> blobCaptureStates = new ConcurrentHashMap<>();
        private final AtomicBoolean entityCaptureRunning = new AtomicBoolean(false);
        private final AtomicLong entityCaptureGeneration = new AtomicLong();
        private final AtomicBoolean captureFailureLogged = new AtomicBoolean(false);
        private volatile TicketLease ticketLease;
        private volatile EntityCaptureToken activeEntityCapture;
        private volatile int lastSkyDarken = -1;

        private Session(UUID portalId, World world, ViewBox box, int centerChunkX, int centerChunkZ,
                        double portalCenterX, double portalCenterY, double portalCenterZ) {
            this.portalId = portalId;
            this.subscriptionId = UUID.randomUUID();
            this.world = world;
            this.box = box;
            this.centerChunkX = centerChunkX;
            this.centerChunkZ = centerChunkZ;
            this.portalCenterX = portalCenterX;
            this.portalCenterY = portalCenterY;
            this.portalCenterZ = portalCenterZ;
            this.columns = columnsFor(box);
            this.chunkKeys = chunkKeysFor(columns);
            this.bounds = new BoundingBox(box.minX(), box.minY(), box.minZ(),
                box.maxX() + 1, box.maxY() + 1, box.maxZ() + 1);
        }

        private Map<UUID, EntitySendState> sendStatesFor(String peerName) {
            return sendStates.computeIfAbsent(peerName, name -> new ConcurrentHashMap<>());
        }
    }

    record BlobCaptureState(long lastCaptureTick, Pose pose, boolean onFire, int equipmentSignature) {
    }

    static final class TimeDeliveryState {
        private final AtomicBoolean deliveryRunning = new AtomicBoolean(false);
        private final AtomicBoolean initialAccepted = new AtomicBoolean(false);
        private volatile int desiredSkyDarken;
        private volatile int acceptedSkyDarken = -1;

        TimeDeliveryState(int desiredSkyDarken) {
            this.desiredSkyDarken = desiredSkyDarken;
        }

        void updateDesired(int skyDarken) {
            desiredSkyDarken = skyDarken;
        }

        int desiredSkyDarken() {
            return desiredSkyDarken;
        }

        boolean needsDelivery() {
            return acceptedSkyDarken != desiredSkyDarken;
        }

        boolean hasAcceptedInitial() {
            return initialAccepted.get();
        }

        void markAccepted(int skyDarken) {
            acceptedSkyDarken = skyDarken;
            initialAccepted.set(true);
        }

        boolean tryStartDelivery() {
            return deliveryRunning.compareAndSet(false, true);
        }

        void finishDelivery() {
            deliveryRunning.set(false);
        }
    }

    static final class EntityCaptureToken {
        private final long generation;
        private final long deadlineNanos;
        private final AtomicBoolean active = new AtomicBoolean(true);

        EntityCaptureToken(long generation, long deadlineNanos) {
            this.generation = generation;
            this.deadlineNanos = deadlineNanos;
        }

        long generation() {
            return generation;
        }

        boolean isActive() {
            return active.get();
        }

        boolean isExpired() {
            return deadlineNanos - System.nanoTime() < 0L;
        }

        boolean tryCompleteBeforeDeadline() {
            return !isExpired() && active.compareAndSet(true, false);
        }

        boolean tryComplete() {
            return active.compareAndSet(true, false);
        }
    }

    private static final class EntityCaptureContext {
        private final EntityCaptureToken token;
        private final Set<UUID> profileUpdates = ConcurrentHashMap.newKeySet();
        private final Map<UUID, BlobCaptureState> blobStateUpdates = new ConcurrentHashMap<>();

        private EntityCaptureContext(EntityCaptureToken token) {
            this.token = token;
        }
    }

    record BulkRetryKey(UUID subscriptionId, String peerName, long chunkKey, long bulkGeneration) {
    }

    private record BulkCompleteKey(UUID subscriptionId, String peerName) {
    }

    static final class TicketLease implements AutoCloseable {
        private final UUID portalId;
        private final World world;
        private final ViewBox box;
        private final List<long[]> columns;
        private final List<ChunkLease> leases;
        private final AtomicBoolean released = new AtomicBoolean(false);

        TicketLease(UUID portalId, World world, ViewBox box) {
            this.portalId = portalId;
            this.world = world;
            this.box = box;
            this.columns = columnsFor(box);
            this.leases = new ArrayList<>(columns.size());
            ChunkLeaseRegistry<World> registry = BukkitChunkLeaseProvider.registry();
            for (long[] column : columns) {
                leases.add(registry.retain(world, world.getUID(), (int) column[0], (int) column[1]));
            }
        }

        private boolean matches(World candidateWorld, ViewBox candidateBox) {
            return world.equals(candidateWorld) && box.equals(candidateBox);
        }

        private synchronized void ensure() {
            if (released.get()) {
                return;
            }
            ChunkLeaseRegistry<World> registry = BukkitChunkLeaseProvider.registry();
            for (int index = 0; index < leases.size(); index++) {
                ChunkLease lease = leases.get(index);
                if (!lease.ready().isDone() || lease.ready().getNow(Boolean.FALSE).booleanValue()) {
                    continue;
                }
                long[] column = columns.get(index);
                lease.close();
                leases.set(index, registry.retain(world, world.getUID(), (int) column[0], (int) column[1]));
            }
        }

        @Override
        public void close() {
            if (!released.compareAndSet(false, true)) {
                return;
            }
            synchronized (this) {
                for (ChunkLease lease : leases) {
                    lease.close();
                }
            }
        }
    }

    public ViewServer(NetworkManager network) {
        this.network = network;
        this.chunkBulkBuilder = new ChunkBulkBuilder(blockDataStrings);
        network.getReplicationManager().setBulkRetryListener(this::retryCanonicalBulk);
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
        if (!active.get()) {
            return;
        }
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
        int initialSkyDarken = art.arcane.wormholes.render.view.ProjectionWorldView.computeSkyDarken(session.world.getTime());
        session.timeDeliveryStates.put(peerName, new TimeDeliveryState(initialSkyDarken));
        queueTimeDelivery(session, peerName, initialSkyDarken);
        ChunkReplicationManager replication = network.getReplicationManager();
        for (long[] column : session.columns) {
            replication.subscribe(peerName, session.subscriptionId, session.world, ViewSlice.columnKey((int) column[0], (int) column[1]));
        }
        int totalColumns = session.columns.size();
        if (totalColumns == 0) {
            sendBulkCompleteWithRetry(session, peerName);
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
                sendInitialBulkWithRetry(session, peerName, chunkX, chunkZ).whenComplete((accepted, error) -> {
                    if (Boolean.TRUE.equals(accepted) && remainingBulks.decrementAndGet() == 0 && session.peers.contains(peerName)) {
                        sendBulkCompleteWithRetry(session, peerName);
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
        ChunkReplicationManager replication = network.getReplicationManager();
        replication.unsubscribeAll(peerName, session.subscriptionId, session.chunkKeys);
        session.peers.remove(peerName);
        session.sendStates.remove(peerName);
        session.lastSentPresentIds.remove(peerName);
        session.lastPeerSideband.remove(peerName);
        session.timeDeliveryStates.remove(peerName);
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
            sendInitialBulkWithRetry(session, peerName, chunkX, chunkZ);
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
        Session removed = sessions.get(portal.getId());
        List<String> peers = new ArrayList<>();
        if (removed != null) {
            peers.addAll(removed.peers);
            unsubscribeSessionReplication(removed);
            sessions.remove(portal.getId(), removed);
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
        active.set(false);
        network.getReplicationManager().setBulkRetryListener(null);
        for (Session session : sessions.values()) {
            unsubscribeSessionReplication(session);
            releaseSessionTickets(session);
        }
        sessions.clear();
        bulkRetryCoordinator.clear();
        bulkCompleteRetries.clear();
        releaseAllGatewayTickets();
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
                unsubscribeSessionReplication(session);
                sessions.remove(session.portalId, session);
                releaseSessionTickets(session);
                continue;
            }
            retryPendingTimeDeliveries(session);
            expireEntityCaptureIfNeeded(session);
            boolean entitiesDue = isIntervalDue(portal.getNetworkViewEntityIntervalTicks());
            if (entitiesDue && session.entityCaptureRunning.compareAndSet(false, true)) {
                EntityCaptureToken token = new EntityCaptureToken(
                    session.entityCaptureGeneration.incrementAndGet(),
                    System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(ENTITY_CAPTURE_DEADLINE_MILLIS));
                session.activeEntityCapture = token;
                boolean scheduled = FoliaScheduler.runRegion(Wormholes.instance, session.world, session.centerChunkX, session.centerChunkZ,
                    () -> captureEntities(session, token));
                if (!scheduled) {
                    completeEntityCaptureFailure(session, token,
                        new IllegalStateException("Entity capture center region rejected scheduling"));
                }
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
            if (session.peers.isEmpty()) {
                continue;
            }
            List<PreShipPredictor.PlayerPose> poses = null;
            for (String peerName : session.peers) {
                ViewSubscriptionManager.SubscriberPose subscriberPose = subscriptions.getSubscriberPose(peerName);
                if (subscriberPose == null) {
                    continue;
                }
                if (poses == null) {
                    poses = new ArrayList<>(session.peers.size());
                }
                poses.add(new PreShipPredictor.PlayerPose(
                    session.portalId, peerName,
                    subscriberPose.x(), subscriberPose.y(), subscriberPose.z(),
                    subscriberPose.forwardX(), subscriberPose.forwardY(), subscriberPose.forwardZ()));
            }
            if (poses == null) {
                continue;
            }
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
            for (PreShipPredictor.PlayerPose pose : poses) {
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
                    replication.subscribePreShip(pose.subscriberId(), ticket.getPortalId(), session.world, chunkKeys);
                }
            }
        }
        List<PreShipPredictor.PreShipTicket> cancelled = preShipPredictor.sweepCanceled(settings, nowMillis);
        for (PreShipPredictor.PreShipTicket ticket : cancelled) {
            replication.cancelPreShip(ticket.getSubscriberId(), ticket.getPortalId());
        }
    }

    private List<long[]> sessionColumnsForPortal(UUID portalId) {
        Session session = sessions.get(portalId);
        if (session == null) {
            return List.of();
        }
        return session.columns;
    }

    private PreShipPredictor.Settings preShipSettings(NetworkConfig.ViewConfig view) {
        PreShipPredictor.Settings cached = cachedPreShipSettings;
        if (cached != null && view == cachedPreShipView) {
            return cached;
        }
        PreShipPredictor.Settings fresh = new PreShipPredictor.Settings(view.preshipEnabled, view.preshipDistance, view.preshipMinSpeed, view.preshipRateFraction, view.preshipCancelGraceSeconds);
        cachedPreShipView = view;
        cachedPreShipSettings = fresh;
        return fresh;
    }

    private void captureEntities(Session session, EntityCaptureToken token) {
        if (!isEntityCaptureActive(session, token)) {
            return;
        }
        EntityCaptureContext context = new EntityCaptureContext(token);
        try {
            int skyDarken = art.arcane.wormholes.render.view.ProjectionWorldView.computeSkyDarken(session.world.getTime());
            if (skyDarken != session.lastSkyDarken) {
                session.lastSkyDarken = skyDarken;
                for (String peerName : session.peers) {
                    queueTimeDelivery(session, peerName, skyDarken);
                }
            }
            long entityTick = tickCounter;
            NetworkConfig.ViewConfig viewConfig = activeViewConfig();
            EntityRateScheduler scheduler = ensureScheduler(viewConfig);
            boolean deltaEnabled = viewConfig.entityDeltaEnabled;
            if (!FoliaScheduler.isFoliaThreading(Bukkit.getServer())) {
                EntityAdmission<Entity> admission = new EntityAdmission<>(MAX_CAPTURED_ENTITIES);
                for (Entity entity : session.world.getNearbyEntities(session.bounds)) {
                    if (entity.isDead() || !entity.isValid() || EffectManager.isPortalEffectEntity(entity)) {
                        continue;
                    }
                    admission.admit(entityRank(session, entity), entity);
                }
                Map<UUID, EntityVisual> captured = new HashMap<>();
                for (Entity entity : admission.selectedEntities()) {
                    if (!isEntityCaptureActive(session, token)) {
                        return;
                    }
                    if (entity.isDead() || !entity.isValid() || EffectManager.isPortalEffectEntity(entity)) {
                        continue;
                    }
                    EntityVisual currentFull = captureEntityVisualFull(session, context, entity, entityTick);
                    captured.put(currentFull.id(), currentFull);
                }
                completeEntityCaptureSuccess(session, context, entityTick, scheduler, deltaEnabled, captured);
                return;
            }
            EntityAdmission<Entity> admission = new EntityAdmission<>(MAX_CAPTURED_ENTITIES);
            List<CompletableFuture<Void>> partitions = new ArrayList<>(session.columns.size());
            for (long[] column : session.columns) {
                int chunkX = (int) column[0];
                int chunkZ = (int) column[1];
                BoundingBox partitionBounds = captureBoundsForChunk(session.bounds, chunkX, chunkZ);
                if (partitionBounds == null) {
                    continue;
                }
                CompletableFuture<Void> partition = new CompletableFuture<>();
                partitions.add(partition);
                boolean scheduled = FoliaScheduler.runRegion(Wormholes.instance, session.world, chunkX, chunkZ, () -> {
                    try {
                        for (Entity entity : session.world.getNearbyEntities(partitionBounds)) {
                            if (!FoliaScheduler.isOwnedByCurrentRegion(entity)
                                || entity.isDead()
                                || !entity.isValid()
                                || EffectManager.isPortalEffectEntity(entity)) {
                                continue;
                            }
                            EntityRank rank = entityRank(session, entity);
                            admission.admit(rank, entity);
                        }
                        partition.complete(null);
                    } catch (Throwable error) {
                        partition.completeExceptionally(error);
                    }
                });
                if (!scheduled) {
                    partition.completeExceptionally(new IllegalStateException("Entity capture partition rejected scheduling for " + chunkX + "," + chunkZ));
                }
            }
            CompletableFuture.allOf(partitions.toArray(CompletableFuture[]::new)).whenComplete((ignored, error) -> {
                if (error != null) {
                    completeEntityCaptureFailure(session, token, error);
                } else {
                    captureAdmittedEntities(session, context, admission.selectedEntities(), entityTick, scheduler, deltaEnabled);
                }
            });
        } catch (Throwable e) {
            completeEntityCaptureFailure(session, token, e);
        }
    }

    private void captureAdmittedEntities(Session session, EntityCaptureContext context, List<Entity> entities, long entityTick,
                                          EntityRateScheduler scheduler, boolean deltaEnabled) {
        if (!isEntityCaptureActive(session, context.token)) {
            return;
        }
        Map<UUID, EntityVisual> captured = new ConcurrentHashMap<>();
        List<CompletableFuture<Void>> captures = new ArrayList<>(entities.size());
        for (Entity entity : entities) {
            CompletableFuture<Void> capture = new CompletableFuture<>();
            captures.add(capture);
            boolean scheduled = WormholesPlatform.scheduleEntity(Wormholes.instance, entity, () -> {
                try {
                    if (!isEntityCaptureActive(session, context.token)) {
                        capture.complete(null);
                        return;
                    }
                    Location location = entity.getLocation();
                    if (entity.isDead()
                        || !entity.isValid()
                        || EffectManager.isPortalEffectEntity(entity)
                        || !session.bounds.contains(location.getX(), location.getY(), location.getZ())) {
                        capture.complete(null);
                        return;
                    }
                    EntityVisual visual = captureEntityVisualFull(session, context, entity, entityTick);
                    if (isEntityCaptureActive(session, context.token)) {
                        captured.put(visual.id(), visual);
                    }
                    capture.complete(null);
                } catch (Throwable error) {
                    capture.completeExceptionally(error);
                }
            }, () -> capture.complete(null), 0L);
            if (!scheduled) {
                capture.complete(null);
            }
        }
        CompletableFuture.allOf(captures.toArray(CompletableFuture[]::new)).whenComplete((ignored, error) -> {
            if (error != null) {
                completeEntityCaptureFailure(session, context.token, error);
                return;
            }
            completeEntityCaptureSuccess(session, context, entityTick, scheduler, deltaEnabled, captured);
        });
    }

    private void queueTimeDelivery(Session session, String peerName, int skyDarken) {
        TimeDeliveryState state = session.timeDeliveryStates.get(peerName);
        if (state == null) {
            return;
        }
        state.updateDesired(skyDarken);
        startTimeDelivery(session, peerName, state);
    }

    private void retryPendingTimeDeliveries(Session session) {
        for (Map.Entry<String, TimeDeliveryState> entry : session.timeDeliveryStates.entrySet()) {
            TimeDeliveryState state = entry.getValue();
            if (state.needsDelivery()) {
                startTimeDelivery(session, entry.getKey(), state);
            }
        }
    }

    private void startTimeDelivery(Session session, String peerName, TimeDeliveryState state) {
        if (state.tryStartDelivery()) {
            attemptTimeDelivery(session, peerName, state);
        }
    }

    private void attemptTimeDelivery(Session session, String peerName, TimeDeliveryState state) {
        if (!isTimeDeliveryActive(session, peerName, state)) {
            state.finishDelivery();
            return;
        }
        int skyDarken = state.desiredSkyDarken();
        if (!state.needsDelivery()) {
            finishTimeDelivery(session, peerName, state);
            return;
        }
        if (network.send(peerName, new WireMessage.ViewTime(session.portalId, skyDarken))) {
            state.markAccepted(skyDarken);
            timeSendCount.incrementAndGet();
        }
        if (!state.needsDelivery()) {
            finishTimeDelivery(session, peerName, state);
            return;
        }
        boolean scheduled = FoliaScheduler.runAsync(Wormholes.instance,
            () -> attemptTimeDelivery(session, peerName, state), VIEW_TIME_RETRY_DELAY_TICKS);
        if (!scheduled) {
            state.finishDelivery();
        }
    }

    private void finishTimeDelivery(Session session, String peerName, TimeDeliveryState state) {
        state.finishDelivery();
        if (isTimeDeliveryActive(session, peerName, state) && state.needsDelivery()) {
            startTimeDelivery(session, peerName, state);
        }
    }

    private boolean isTimeDeliveryActive(Session session, String peerName, TimeDeliveryState state) {
        return isSessionPeerActive(session, peerName) && session.timeDeliveryStates.get(peerName) == state;
    }

    private void expireEntityCaptureIfNeeded(Session session) {
        EntityCaptureToken token = session.activeEntityCapture;
        if (token != null && token.isExpired()) {
            completeEntityCaptureFailure(session, token, entityCaptureTimeout(token));
        }
    }

    private boolean isEntityCaptureActive(Session session, EntityCaptureToken token) {
        return active.get()
            && sessions.get(session.portalId) == session
            && session.activeEntityCapture == token
            && session.entityCaptureGeneration.get() == token.generation()
            && token.isActive();
    }

    private void completeEntityCaptureSuccess(Session session, EntityCaptureContext context, long entityTick,
                                              EntityRateScheduler scheduler, boolean deltaEnabled,
                                              Map<UUID, EntityVisual> captured) {
        EntityCaptureToken token = context.token;
        if (!isEntityCaptureActive(session, token)) {
            return;
        }
        if (!token.tryCompleteBeforeDeadline()) {
            completeEntityCaptureFailure(session, token, entityCaptureTimeout(token));
            return;
        }
        try {
            session.sentProfiles.addAll(context.profileUpdates);
            session.blobCaptureStates.putAll(context.blobStateUpdates);
            for (EntityVisual visual : captured.values()) {
                session.lastCapturedSnapshots.put(visual.id(), visual);
            }
            publishEntityCapture(session, entityTick, scheduler, deltaEnabled, captured);
        } finally {
            finishEntityCapture(session, token);
        }
    }

    private void completeEntityCaptureFailure(Session session, EntityCaptureToken token, Throwable error) {
        if (!isEntityCaptureActive(session, token) || !token.tryComplete()) {
            return;
        }
        try {
            publishEmptyEntityPresence(session, error);
        } finally {
            finishEntityCapture(session, token);
        }
    }

    private TimeoutException entityCaptureTimeout(EntityCaptureToken token) {
        return new TimeoutException(
            "Entity capture generation " + token.generation() + " exceeded " + ENTITY_CAPTURE_DEADLINE_MILLIS + "ms");
    }

    private void finishEntityCapture(Session session, EntityCaptureToken token) {
        if (session.activeEntityCapture == token) {
            session.activeEntityCapture = null;
            session.entityCaptureRunning.set(false);
        }
    }

    static BoundingBox captureBoundsForChunk(BoundingBox bounds, int chunkX, int chunkZ) {
        double chunkMinX = (double) chunkX * 16.0D;
        double chunkMinZ = (double) chunkZ * 16.0D;
        double chunkMaxX = Math.nextDown(chunkMinX + 16.0D);
        double chunkMaxZ = Math.nextDown(chunkMinZ + 16.0D);
        double minX = Math.max(bounds.getMinX(), chunkMinX);
        double minZ = Math.max(bounds.getMinZ(), chunkMinZ);
        double maxX = Math.min(bounds.getMaxX(), chunkMaxX);
        double maxZ = Math.min(bounds.getMaxZ(), chunkMaxZ);
        if (maxX <= minX || maxZ <= minZ) {
            return null;
        }
        return new BoundingBox(minX, bounds.getMinY(), minZ, maxX, bounds.getMaxY(), maxZ);
    }

    private static EntityRank entityRank(Session session, Entity entity) {
        Location location = entity.getLocation();
        double dx = location.getX() - session.portalCenterX;
        double dy = location.getY() - session.portalCenterY;
        double dz = location.getZ() - session.portalCenterZ;
        return new EntityRank(entity.getUniqueId(), entity instanceof Player, (dx * dx) + (dy * dy) + (dz * dz));
    }

    private void publishEntityCapture(Session session, long entityTick, EntityRateScheduler scheduler,
                                      boolean deltaEnabled, Map<UUID, EntityVisual> captured) {
        if (!active.get() || sessions.get(session.portalId) != session) {
            return;
        }
        session.captureFailureLogged.set(false);
        List<EntityVisual> visuals = new ArrayList<>(captured.values());
        Set<UUID> presentIds = new HashSet<>(captured.keySet());
        List<UUID> presentIdList = new ArrayList<>(presentIds);
        session.sentProfiles.retainAll(presentIds);
        session.lastCapturedSnapshots.keySet().retainAll(presentIds);
        session.blobCaptureStates.keySet().retainAll(presentIds);

        Set<UUID> sidebandAllowed = null;
        List<UUID> sidebandPresentIdList = null;
        for (String peerName : session.peers) {
            boolean sideband = network.isSidebandOnlyPeer(peerName);
            List<UUID> peerPresentIdList;
            if (sideband) {
                Long nextTick = session.sidebandEntityNextTick.get(peerName);
                if (nextTick != null && entityTick < nextTick.longValue()) {
                    continue;
                }
                session.sidebandEntityNextTick.put(peerName, entityTick + SIDEBAND_ENTITY_INTERVAL_TICKS);
                if (sidebandAllowed == null) {
                    sidebandAllowed = nearestEntityIds(session, visuals, SIDEBAND_MAX_ENTITIES);
                    sidebandPresentIdList = new ArrayList<>(sidebandAllowed);
                }
                peerPresentIdList = sidebandPresentIdList;
            } else {
                session.sidebandEntityNextTick.remove(peerName);
                peerPresentIdList = presentIdList;
            }
            Map<UUID, EntitySendState> peerStates = session.sendStatesFor(peerName);
            Set<UUID> peerPresentIds = presentIdsForPeer(sideband, presentIds, sidebandAllowed);
            peerStates.keySet().retainAll(peerPresentIds);
            Boolean previousSideband = session.lastPeerSideband.put(peerName, Boolean.valueOf(sideband));
            if (previousSideband != null && previousSideband.booleanValue() != sideband) {
                for (EntitySendState transitioned : peerStates.values()) {
                    transitioned.requestFull();
                }
            }
            int outboundCapacity = sideband ? Math.min(visuals.size(), SIDEBAND_MAX_ENTITIES) : visuals.size();
            List<EntityVisual> outbound = new ArrayList<>(outboundCapacity);
            for (EntityVisual currentFull : visuals) {
                if (sideband && !sidebandAllowed.contains(currentFull.id())) {
                    continue;
                }
                EntitySendState state = peerStates.computeIfAbsent(currentFull.id(), EntitySendState::new);
                boolean rateAllowsSend = entityTick >= state.getNextEligibleTick();
                if (rateAllowsSend) {
                    double dx = currentFull.x() - session.portalCenterX;
                    double dy = currentFull.y() - session.portalCenterY;
                    double dz = currentFull.z() - session.portalCenterZ;
                    state.setNextEligibleTick(entityTick + scheduler.claimSendInterval((dx * dx) + (dy * dy) + (dz * dz)));
                }
                EntityVisual lastSent = state.getLastSentSnapshot();
                boolean forceFull = !deltaEnabled
                    || state.isForceFullNext()
                    || lastSent == null
                    || (sideband && state.isSidebandFullDue(entityTick, SIDEBAND_FULL_RESYNC_TICKS, SIDEBAND_FULL_RESYNC_JITTER_TICKS));
                if (!rateAllowsSend && !forceFull) {
                    continue;
                }
                if (forceFull) {
                    int sequence = state.allocateSequence();
                    outbound.add(withSequenceAndMode(currentFull, sequence, EntityVisual.MODE_FULL));
                    state.recordSent(currentFull, true, entityTick);
                } else {
                    int mask = EntityDeltaCodec.computeMask(currentFull, lastSent);
                    if (mask == 0) {
                        continue;
                    }
                    int sequence = state.allocateSequence();
                    outbound.add(EntityDeltaCodec.buildDelta(currentFull, lastSent, sequence, mask));
                    state.recordSent(currentFull, false, entityTick);
                }
            }
            Set<UUID> previousPresent = session.lastSentPresentIds.get(peerName);
            boolean presentChanged = previousPresent == null || !previousPresent.equals(peerPresentIds);
            if (Settings.DEBUG && presentChanged && previousPresent != null) {
                Set<UUID> left = new HashSet<>(previousPresent);
                left.removeAll(peerPresentIds);
                Set<UUID> joined = new HashSet<>(peerPresentIds);
                joined.removeAll(previousPresent);
                if (!left.isEmpty() || !joined.isEmpty()) {
                    Wormholes.v("[stream] portal=" + session.portalId + " peer=" + peerName + " present=" + peerPresentIds.size()
                        + (left.isEmpty() ? "" : " LEFT=" + left) + (joined.isEmpty() ? "" : " JOINED=" + joined));
                }
            }
            if (outbound.isEmpty() && !presentChanged) {
                continue;
            }
            WireMessage.ViewEntities message = new WireMessage.ViewEntities(session.portalId, outbound, peerPresentIdList);
            boolean sent = network.send(peerName, message);
            if (sent) {
                session.lastSentPresentIds.put(peerName, peerPresentIds);
                entitySendCount.addAndGet(outbound.size());
            } else {
                for (EntityVisual failedVisual : outbound) {
                    EntitySendState failedState = peerStates.get(failedVisual.id());
                    if (failedState != null) {
                        failedState.requestFull();
                    }
                }
            }
        }
    }

    private void publishEmptyEntityPresence(Session session, Throwable error) {
        if (!active.get() || sessions.get(session.portalId) != session) {
            return;
        }
        if (session.captureFailureLogged.compareAndSet(false, true)) {
            Wormholes.instance.getLogger().log(Level.WARNING, "Entity view capture failed for portal " + session.portalId, error);
        }
        session.sentProfiles.clear();
        session.lastCapturedSnapshots.clear();
        session.blobCaptureStates.clear();
        session.sidebandEntityNextTick.clear();
        for (String peerName : session.peers) {
            session.sendStatesFor(peerName).clear();
            boolean sent = network.send(peerName, new WireMessage.ViewEntities(session.portalId, List.of(), List.of()));
            if (sent) {
                session.lastSentPresentIds.put(peerName, Set.of());
            } else {
                session.lastSentPresentIds.remove(peerName);
            }
        }
    }

    static Set<UUID> presentIdsForPeer(boolean sideband, Set<UUID> presentIds, Set<UUID> sidebandAllowed) {
        if (!sideband) {
            return presentIds;
        }
        return sidebandAllowed == null ? Set.of() : sidebandAllowed;
    }

    private EntityVisual captureEntityVisualFull(Session session, EntityCaptureContext context, Entity entity, long entityTick) {
        Location location = entity.getLocation();
        Vector look = entity instanceof LivingEntity living ? living.getEyeLocation().getDirection() : location.getDirection();
        Vector velocity = entity.getVelocity();
        String playerName = "";
        String textureValue = "";
        String textureSignature = "";
        if (entity instanceof Player player) {
            playerName = player.getName();
            UUID playerId = player.getUniqueId();
            if (!session.sentProfiles.contains(playerId) && context.profileUpdates.add(playerId)) {
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
        EntityVisual previousVisual = session.lastCapturedSnapshots.get(entity.getUniqueId());
        BlobCaptureState previousBlobState = session.blobCaptureStates.get(entity.getUniqueId());
        Pose pose = entity.getPose();
        boolean onFire = entity.getFireTicks() > 0;
        int equipmentSignature = equipmentSignature(entity);
        byte[] metadata;
        byte[] equipment;
        if (shouldRecaptureBlobs(previousVisual, previousBlobState, entityTick, BLOB_RECAPTURE_INTERVAL_TICKS, pose, onFire, equipmentSignature)) {
            metadata = PacketBlobs.captureMetadata(entity);
            equipment = PacketBlobs.captureEquipment(entity);
            context.blobStateUpdates.put(entity.getUniqueId(), new BlobCaptureState(entityTick, pose, onFire, equipmentSignature));
        } else {
            metadata = previousVisual.metadata();
            equipment = previousVisual.equipment();
        }
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

    static boolean shouldRecaptureBlobs(EntityVisual previousVisual, BlobCaptureState previousBlobState, long entityTick, long intervalTicks,
                                        Pose pose, boolean onFire, int equipmentSignature) {
        return previousVisual == null
            || previousBlobState == null
            || entityTick - previousBlobState.lastCaptureTick() >= intervalTicks
            || previousBlobState.pose() != pose
            || previousBlobState.onFire() != onFire
            || previousBlobState.equipmentSignature() != equipmentSignature;
    }

    private static int equipmentSignature(Entity entity) {
        if (!(entity instanceof LivingEntity living)) {
            return 0;
        }
        EntityEquipment equipment = living.getEquipment();
        if (equipment == null) {
            return 0;
        }
        int signature = 1;
        signature = 31 * signature + itemSignature(equipment.getHelmet());
        signature = 31 * signature + itemSignature(equipment.getChestplate());
        signature = 31 * signature + itemSignature(equipment.getLeggings());
        signature = 31 * signature + itemSignature(equipment.getBoots());
        signature = 31 * signature + itemSignature(equipment.getItemInMainHand());
        signature = 31 * signature + itemSignature(equipment.getItemInOffHand());
        return signature;
    }

    private static int itemSignature(ItemStack stack) {
        if (stack == null) {
            return 0;
        }
        return (stack.getType().ordinal() * 31) + stack.getAmount();
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
        sorted.sort(Comparator.comparingDouble(visual -> {
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
            if (session.lastCapturedSnapshots.containsKey(entityId) && !session.peers.isEmpty()) {
                WireMessage.ViewEntityAnimation message = new WireMessage.ViewEntityAnimation(session.portalId, entityId, hurt, animationOrdinal, yaw);
                network.sendToPeers(session.peers, message);
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

    private CompletableFuture<Boolean> sendInitialBulkWithRetry(Session session, String peerName, int chunkX, int chunkZ) {
        CompletableFuture<Boolean> result = new CompletableFuture<>();
        continueInitialBulkRetry(session, peerName, chunkX, chunkZ, result);
        return result;
    }

    private void continueInitialBulkRetry(Session session, String peerName, int chunkX, int chunkZ, CompletableFuture<Boolean> result) {
        if (result.isDone()) {
            return;
        }
        ChunkReplicationManager replication = network.getReplicationManager();
        long chunkKey = ViewSlice.columnKey(chunkX, chunkZ);
        if (!isSessionChunkActive(session, peerName, chunkKey)) {
            result.complete(false);
            return;
        }
        if (replication.isBulked(peerName, chunkKey)) {
            result.complete(true);
            return;
        }
        long bulkGeneration = replication.bulkGeneration(peerName, chunkKey);
        if (bulkGeneration < 0L) {
            result.complete(false);
            return;
        }
        BulkRetryKey key = new BulkRetryKey(session.subscriptionId, peerName, chunkKey, bulkGeneration);
        CompletableFuture<Boolean> generationResult = bulkRetryCoordinator.run(
            key,
            () -> isSessionChunkActive(session, peerName, chunkKey)
                && replication.bulkGeneration(peerName, chunkKey) == bulkGeneration
                && !replication.isBulked(peerName, chunkKey),
            () -> sendInitialBulk(session, peerName, chunkX, chunkZ, bulkGeneration),
            (retry, delayTicks) -> FoliaScheduler.runAsync(Wormholes.instance, retry, delayTicks)
        );
        generationResult.whenComplete((accepted, error) -> {
            if (result.isDone()) {
                return;
            }
            if (!isSessionChunkActive(session, peerName, chunkKey)) {
                result.complete(false);
                return;
            }
            if (replication.isBulked(peerName, chunkKey)) {
                result.complete(true);
                return;
            }
            long currentGeneration = replication.bulkGeneration(peerName, chunkKey);
            if (currentGeneration != bulkGeneration && currentGeneration >= 0L) {
                continueInitialBulkRetry(session, peerName, chunkX, chunkZ, result);
                return;
            }
            result.complete(false);
        });
    }

    private CompletableFuture<Boolean> sendInitialBulk(Session session, String peerName, int chunkX, int chunkZ, long bulkGeneration) {
        ChunkReplicationManager replication = network.getReplicationManager();
        long chunkKey = ViewSlice.columnKey(chunkX, chunkZ);
        CompletableFuture<Boolean> done = new CompletableFuture<>();
        if (!isSessionChunkActive(session, peerName, chunkKey)
            || replication.bulkGeneration(peerName, chunkKey) != bulkGeneration) {
            done.complete(false);
            return done;
        }
        WormholesPlatform.loadChunk(Wormholes.instance, session.world, chunkX, chunkZ).whenComplete((chunk, error) -> {
            if (error != null || chunk == null || !isSessionChunkActive(session, peerName, chunkKey)) {
                done.complete(false);
                return;
            }
            boolean snapshotScheduled = FoliaScheduler.runRegion(Wormholes.instance, session.world, chunkX, chunkZ, () -> {
                if (!isSessionChunkActive(session, peerName, chunkKey)) {
                    done.complete(false);
                    return;
                }
                ChunkSnapshot snapshot = WormholesPlatform.chunkSnapshot(chunk, false, true, false, true);
                boolean encodeScheduled = FoliaScheduler.runAsync(Wormholes.instance, () -> {
                    try {
                        if (!isSessionChunkActive(session, peerName, chunkKey)) {
                            done.complete(false);
                            return;
                        }
                        ViewSlice slice = chunkBulkBuilder.buildSlice(session.box, chunkX, chunkZ, snapshot);
                        if (slice == null) {
                            done.complete(isSessionChunkActive(session, peerName, chunkKey));
                            return;
                        }
                        byte[] payload;
                        try {
                            payload = ChunkBulkBuilder.encodeSliceBytes(slice);
                        } catch (IOException e) {
                            Wormholes.v("net: failed to encode chunk bulk for " + peerName + " (" + chunkX + "," + chunkZ + "): " + e.getMessage());
                            done.complete(false);
                            return;
                        }
                        if (!isSessionChunkActive(session, peerName, chunkKey)) {
                            done.complete(false);
                            return;
                        }
                        boolean accepted = replication.sendBulk(peerName, session.subscriptionId, chunkKey, payload, slice.contentHash(), bulkGeneration);
                        done.complete(accepted);
                    } catch (Throwable errorDuringBulk) {
                        done.complete(false);
                    }
                });
                if (!encodeScheduled) {
                    done.complete(false);
                }
            });
            if (!snapshotScheduled) {
                done.complete(false);
            }
        });
        return done;
    }

    private void retryCanonicalBulk(String peerName, long chunkKey) {
        if (!active.get()) {
            return;
        }
        int chunkX = (int) (chunkKey >> 32);
        int chunkZ = (int) chunkKey;
        for (Session session : sessions.values()) {
            if (!session.peers.contains(peerName) || !sessionContainsChunk(session, chunkX, chunkZ)) {
                continue;
            }
            sendInitialBulkWithRetry(session, peerName, chunkX, chunkZ);
            return;
        }
    }

    private void sendBulkCompleteWithRetry(Session session, String peerName) {
        BulkCompleteKey key = new BulkCompleteKey(session.subscriptionId, peerName);
        if (!bulkCompleteRetries.add(key)) {
            return;
        }
        attemptBulkComplete(session, peerName, key);
    }

    private void attemptBulkComplete(Session session, String peerName, BulkCompleteKey key) {
        if (!isSessionPeerActive(session, peerName)) {
            bulkCompleteRetries.remove(key);
            return;
        }
        TimeDeliveryState timeState = session.timeDeliveryStates.get(peerName);
        if (timeState == null || !timeState.hasAcceptedInitial()) {
            if (timeState != null) {
                startTimeDelivery(session, peerName, timeState);
            }
            scheduleBulkCompleteRetry(session, peerName, key);
            return;
        }
        ChunkReplicationManager replication = network.getReplicationManager();
        if (replication.sendWhenAllBulked(peerName, session.subscriptionId, session.chunkKeys,
            () -> network.send(peerName, new WireMessage.ViewBulkComplete(session.portalId)))) {
            bulkCompleteRetries.remove(key);
            return;
        }
        scheduleBulkCompleteRetry(session, peerName, key);
    }

    private void scheduleBulkCompleteRetry(Session session, String peerName, BulkCompleteKey key) {
        boolean scheduled = FoliaScheduler.runAsync(Wormholes.instance,
            () -> attemptBulkComplete(session, peerName, key), BULK_COMPLETE_RETRY_DELAY_TICKS);
        if (!scheduled) {
            bulkCompleteRetries.remove(key);
        }
    }

    private boolean isSessionPeerActive(Session session, String peerName) {
        return active.get() && sessions.get(session.portalId) == session && session.peers.contains(peerName);
    }

    private boolean isSessionChunkActive(Session session, String peerName, long chunkKey) {
        return isSessionPeerActive(session, peerName)
            && network.getReplicationManager().isSubscribed(peerName, session.subscriptionId, chunkKey);
    }

    private void unsubscribeSessionReplication(Session session) {
        ChunkReplicationManager replication = network.getReplicationManager();
        for (String peerName : session.peers) {
            replication.unsubscribeAll(peerName, session.subscriptionId, session.chunkKeys);
        }
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
        return new TicketLease(portalId, world, box);
    }

    private void releaseTicketLease(TicketLease lease) {
        lease.close();
    }

    private void ensureTicketLease(TicketLease lease) {
        lease.ensure();
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

    private static List<Long> chunkKeysFor(List<long[]> columns) {
        List<Long> chunkKeys = new ArrayList<>(columns.size());
        for (long[] column : columns) {
            chunkKeys.add(ViewSlice.columnKey((int) column[0], (int) column[1]));
        }
        return List.copyOf(chunkKeys);
    }

    public Stats statsSnapshot() {
        int totalSubscriptions = 0;
        int tracked = 0;
        for (Session session : sessions.values()) {
            totalSubscriptions += session.peers.size();
            for (Map<UUID, EntitySendState> peerStates : session.sendStates.values()) {
                tracked += peerStates.size();
            }
        }
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

    private static int compareRanks(EntityRank left, EntityRank right) {
        if (left.player() != right.player()) {
            return left.player() ? -1 : 1;
        }
        int distanceOrder = Double.compare(left.distanceSquared(), right.distanceSquared());
        if (distanceOrder != 0) {
            return distanceOrder;
        }
        return left.id().compareTo(right.id());
    }

    record EntityRank(UUID id, boolean player, double distanceSquared) {
    }

    static final class EntityAdmission<T> {
        private static final Comparator<EntityRank> RANK_ORDER = ViewServer::compareRanks;

        private final int limit;
        private final TreeSet<EntityRank> ranks = new TreeSet<>(RANK_ORDER);
        private final Map<UUID, EntityRank> ranksById = new HashMap<>();
        private final Map<UUID, T> valuesById = new HashMap<>();

        EntityAdmission(int limit) {
            if (limit <= 0) {
                throw new IllegalArgumentException("limit must be positive");
            }
            this.limit = limit;
        }

        synchronized boolean admit(EntityRank rank, T value) {
            if (ranksById.containsKey(rank.id())) {
                return false;
            }
            if (ranks.size() >= limit) {
                EntityRank worst = ranks.last();
                if (RANK_ORDER.compare(rank, worst) >= 0) {
                    return false;
                }
                ranks.remove(worst);
                ranksById.remove(worst.id());
                valuesById.remove(worst.id());
            }
            ranks.add(rank);
            ranksById.put(rank.id(), rank);
            valuesById.put(rank.id(), value);
            return true;
        }

        synchronized Set<UUID> admittedIds() {
            return Set.copyOf(ranksById.keySet());
        }

        synchronized List<T> selectedEntities() {
            return List.copyOf(valuesById.values());
        }
    }
}
