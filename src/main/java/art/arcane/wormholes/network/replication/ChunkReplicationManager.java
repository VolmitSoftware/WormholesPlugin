package art.arcane.wormholes.network.replication;

import art.arcane.wormholes.network.NetworkManager;
import art.arcane.wormholes.network.WireMessage;

import org.bukkit.World;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.BooleanSupplier;

public final class ChunkReplicationManager implements BlockChangeFeed {
    public record ReplicationConfig(long maxQueuedDiffsPerPeer) {
        public static ReplicationConfig defaults() {
            return new ReplicationConfig(4096L);
        }
    }

    public record Stats(long bulkSent, long diffsSent, long blocksSent, long resyncRequests, long preShipBulksDeferred) {
    }

    @FunctionalInterface
    public interface ChunkEvictionListener {
        void onChunkEvicted(UUID worldId, long chunkKey);
    }

    @FunctionalInterface
    public interface BulkRetryListener {
        void onBulkRetryRequired(String peerName, long chunkKey);
    }

    private static final class CanonicalHashCache {
        private volatile long hash;

        private CanonicalHashCache() {
            this.hash = 0L;
        }
    }

    private static final class PreShipState {
        private final UUID portalId;
        private boolean promoted;

        private PreShipState(UUID portalId) {
            this.portalId = portalId;
            this.promoted = false;
        }
    }

    private record SubscriptionRef(UUID portalId, boolean preShip) {
    }

    private final NetworkManager network;
    private volatile ReplicationConfig config;
    private volatile ChunkEvictionListener evictionListener;
    private volatile BulkRetryListener bulkRetryListener;
    private final Map<String, Map<Long, ChunkReplicationState>> peerStates = new ConcurrentHashMap<>();
    private final Map<String, Map<Long, Set<SubscriptionRef>>> peerSubscriptions = new ConcurrentHashMap<>();
    private final Map<UUID, Map<Long, ConcurrentHashMap<String, ChunkReplicationState>>> worldSubscribers = new ConcurrentHashMap<>();
    private final Map<String, Map<Long, CanonicalHashCache>> peerHashes = new ConcurrentHashMap<>();
    private final Map<String, Map<UUID, Map<Long, PreShipState>>> peerPreShip = new ConcurrentHashMap<>();
    private final Map<String, Object> peerGates = new ConcurrentHashMap<>();
    private final AtomicLong bulkSent = new AtomicLong();
    private final AtomicLong diffsSent = new AtomicLong();
    private final AtomicLong blocksSent = new AtomicLong();
    private final AtomicLong resyncRequests = new AtomicLong();
    private final AtomicLong preShipBulksDeferred = new AtomicLong();

    public ChunkReplicationManager(NetworkManager network, ReplicationConfig config) {
        this.network = network;
        this.config = config == null ? ReplicationConfig.defaults() : config;
    }

    public void applyConfig(ReplicationConfig next) {
        this.config = next == null ? ReplicationConfig.defaults() : next;
    }

    public void setEvictionListener(ChunkEvictionListener listener) {
        this.evictionListener = listener;
    }

    public void setBulkRetryListener(BulkRetryListener listener) {
        this.bulkRetryListener = listener;
    }

    public ReplicationConfig config() {
        return config;
    }

    public boolean isBulked(String peerName, long chunkKey) {
        ChunkReplicationState state = stateFor(peerName, chunkKey, false);
        return state != null && state.isBulkSent();
    }

    public boolean isSubscribed(String peerName, long chunkKey) {
        return stateFor(peerName, chunkKey, false) != null;
    }

    public boolean isSubscribed(String peerName, UUID portalId, long chunkKey) {
        synchronized (peerGate(peerName)) {
            return hasSubscriptionLocked(peerName, new SubscriptionRef(portalId, false), chunkKey);
        }
    }

    public void subscribe(String peerName, UUID portalId, World world, long chunkKey) {
        if (portalId == null) {
            return;
        }
        synchronized (peerGate(peerName)) {
            subscribeLocked(peerName, new SubscriptionRef(portalId, false), world, chunkKey);
        }
    }

    public void subscribePreShip(String peerName, UUID portalId, World world, List<Long> chunkKeys) {
        if (peerName == null || portalId == null || chunkKeys == null || chunkKeys.isEmpty()) {
            return;
        }
        synchronized (peerGate(peerName)) {
            Map<UUID, Map<Long, PreShipState>> portalMap = peerPreShip.computeIfAbsent(peerName, ignored -> new ConcurrentHashMap<>());
            Map<Long, PreShipState> chunkMap = portalMap.computeIfAbsent(portalId, ignored -> new ConcurrentHashMap<>());
            SubscriptionRef subscription = new SubscriptionRef(portalId, true);
            for (Long chunkKey : chunkKeys) {
                long key = chunkKey.longValue();
                subscribeLocked(peerName, subscription, world, key);
                chunkMap.putIfAbsent(key, new PreShipState(portalId));
                preShipBulksDeferred.incrementAndGet();
            }
        }
    }

    public void promotePreShip(String peerName, UUID portalId) {
        Map<UUID, Map<Long, PreShipState>> portalMap = peerPreShip.get(peerName);
        if (portalMap == null) {
            return;
        }
        Map<Long, PreShipState> chunkMap = portalMap.get(portalId);
        if (chunkMap == null) {
            return;
        }
        for (PreShipState state : chunkMap.values()) {
            state.promoted = true;
        }
    }

    public void cancelPreShip(String peerName, UUID portalId) {
        synchronized (peerGate(peerName)) {
            Map<UUID, Map<Long, PreShipState>> portalMap = peerPreShip.get(peerName);
            if (portalMap == null) {
                return;
            }
            Map<Long, PreShipState> removed = portalMap.remove(portalId);
            if (removed == null) {
                return;
            }
            SubscriptionRef subscription = new SubscriptionRef(portalId, true);
            for (Long chunkKey : removed.keySet()) {
                unsubscribeLocked(peerName, subscription, chunkKey.longValue());
            }
            if (portalMap.isEmpty()) {
                peerPreShip.remove(peerName, portalMap);
            }
        }
    }

    public boolean isPreShipPromoted(String peerName, UUID portalId, long chunkKey) {
        Map<UUID, Map<Long, PreShipState>> portalMap = peerPreShip.get(peerName);
        if (portalMap == null) {
            return false;
        }
        Map<Long, PreShipState> chunkMap = portalMap.get(portalId);
        if (chunkMap == null) {
            return false;
        }
        PreShipState state = chunkMap.get(chunkKey);
        return state != null && state.promoted;
    }

    public void unsubscribe(String peerName, UUID portalId, long chunkKey) {
        if (portalId == null) {
            return;
        }
        synchronized (peerGate(peerName)) {
            unsubscribeLocked(peerName, new SubscriptionRef(portalId, false), chunkKey);
        }
    }

    public void unsubscribeAll(String peerName, UUID portalId, List<Long> chunkKeys) {
        if (portalId == null) {
            return;
        }
        synchronized (peerGate(peerName)) {
            SubscriptionRef subscription = new SubscriptionRef(portalId, false);
            for (Long chunkKey : chunkKeys) {
                unsubscribeLocked(peerName, subscription, chunkKey.longValue());
            }
        }
    }

    public void clearPeer(String peerName) {
        synchronized (peerGate(peerName)) {
            Map<Long, ChunkReplicationState> chunks = peerStates.remove(peerName);
            if (chunks != null) {
                for (Map.Entry<UUID, Map<Long, ConcurrentHashMap<String, ChunkReplicationState>>> worldEntry : worldSubscribers.entrySet()) {
                    Map<Long, ConcurrentHashMap<String, ChunkReplicationState>> worldMap = worldEntry.getValue();
                    for (Map.Entry<Long, ConcurrentHashMap<String, ChunkReplicationState>> entry : worldMap.entrySet()) {
                        ConcurrentHashMap<String, ChunkReplicationState> chunkPeers = entry.getValue();
                        if (chunkPeers.remove(peerName) == null) {
                            continue;
                        }
                        if (chunkPeers.isEmpty()) {
                            worldMap.remove(entry.getKey(), chunkPeers);
                            notifyEviction(worldEntry.getKey(), entry.getKey().longValue());
                        }
                    }
                }
            }
            peerHashes.remove(peerName);
            peerPreShip.remove(peerName);
            peerSubscriptions.remove(peerName);
        }
    }

    public boolean sendBulk(String peerName, UUID portalId, long chunkKey, byte[] payload, long contentHash) {
        return sendBulk(peerName, portalId, chunkKey, payload, contentHash, -1L);
    }

    public boolean sendBulk(String peerName, UUID portalId, long chunkKey, byte[] payload, long contentHash, long expectedGeneration) {
        synchronized (peerGate(peerName)) {
            SubscriptionRef subscription = new SubscriptionRef(portalId, false);
            if (!hasSubscriptionLocked(peerName, subscription, chunkKey)) {
                return false;
            }
            ChunkReplicationState state = stateFor(peerName, chunkKey, false);
            if (state == null) {
                return false;
            }
            if (expectedGeneration >= 0L && state.bulkGeneration() != expectedGeneration) {
                return false;
            }
            if (state.isBulkSent()) {
                return false;
            }
            long sequence = state.nextBroadcastSeq();
            WireMessage.ChunkBulkBatch batch = new WireMessage.ChunkBulkBatch(List.of(new ChunkBulk(chunkKey, sequence, payload)));
            if (!network.send(peerName, batch)) {
                return false;
            }
            state.markBulkSent();
            bulkSent.incrementAndGet();
            if (!state.hasPendingBlocks()) {
                cacheCanonicalHash(peerName, chunkKey, contentHash);
            }
            return true;
        }
    }

    public boolean sendWhenAllBulked(String peerName, UUID portalId, List<Long> chunkKeys, BooleanSupplier sender) {
        synchronized (peerGate(peerName)) {
            SubscriptionRef subscription = new SubscriptionRef(portalId, false);
            for (Long chunkKey : chunkKeys) {
                if (!hasSubscriptionLocked(peerName, subscription, chunkKey.longValue())) {
                    return false;
                }
                ChunkReplicationState state = stateFor(peerName, chunkKey.longValue(), false);
                if (state == null || !state.isBulkSent()) {
                    return false;
                }
            }
            return sender.getAsBoolean();
        }
    }

    public long canonicalHash(String peerName, long chunkKey) {
        Map<Long, CanonicalHashCache> hashMap = peerHashes.get(peerName);
        if (hashMap == null) {
            return 0L;
        }
        CanonicalHashCache cache = hashMap.get(chunkKey);
        if (cache == null) {
            return 0L;
        }
        return cache.hash;
    }

    public long bulkGeneration(String peerName, long chunkKey) {
        ChunkReplicationState state = stateFor(peerName, chunkKey, false);
        return state == null ? -1L : state.bulkGeneration();
    }

    public void requestResync(String peerName, long chunkKey) {
        synchronized (peerGate(peerName)) {
            ChunkReplicationState state = stateFor(peerName, chunkKey, false);
            if (state == null) {
                return;
            }
            state.resetBulk();
            resyncRequests.incrementAndGet();
            Map<Long, CanonicalHashCache> hashMap = peerHashes.get(peerName);
            if (hashMap != null) {
                hashMap.remove(chunkKey);
            }
        }
    }

    public void forceResync(World world, long chunkKey) {
        ConcurrentHashMap<String, ChunkReplicationState> subscribers = subscribersFor(world, chunkKey);
        if (subscribers == null || subscribers.isEmpty()) {
            return;
        }
        for (ChunkReplicationState candidate : subscribers.values()) {
            String peerName = candidate.peerName();
            synchronized (peerGate(peerName)) {
                ChunkReplicationState state = stateFor(peerName, chunkKey, false);
                if (state != candidate) {
                    continue;
                }
                invalidateForBulkResend(peerName, state);
                resyncRequests.incrementAndGet();
                notifyBulkRetry(peerName, chunkKey);
            }
        }
    }

    @Override
    public void onChunkDrain(World world, long chunkKey, List<BlockChange> blocks, List<LightDiff> lights, List<BlockEntityDiff> entities) {
        ConcurrentHashMap<String, ChunkReplicationState> subscribers = subscribersFor(world, chunkKey);
        if (subscribers == null || subscribers.isEmpty()) {
            return;
        }
        long capacity = config.maxQueuedDiffsPerPeer();
        boolean hasBlocks = blocks != null && !blocks.isEmpty();
        boolean hasLights = lights != null && !lights.isEmpty();
        boolean hasEntities = entities != null && !entities.isEmpty();
        if (!hasBlocks && !hasLights && !hasEntities) {
            return;
        }
        for (ChunkReplicationState candidate : subscribers.values()) {
            String peerName = candidate.peerName();
            synchronized (peerGate(peerName)) {
                ChunkReplicationState state = stateFor(peerName, chunkKey, false);
                if (state != candidate) {
                    continue;
                }
                boolean overflowed = false;
                if (hasBlocks) {
                    for (int i = 0; i < blocks.size(); i++) {
                        if (!state.appendBlock(blocks.get(i), capacity)) {
                            overflowed = true;
                            break;
                        }
                    }
                    markHashDirty(peerName, chunkKey);
                }
                if (!overflowed && hasLights) {
                    for (int i = 0; i < lights.size(); i++) {
                        if (!state.appendLight(lights.get(i), capacity)) {
                            overflowed = true;
                            break;
                        }
                    }
                }
                if (!overflowed && hasEntities) {
                    for (int i = 0; i < entities.size(); i++) {
                        if (!state.appendBlockEntity(entities.get(i), capacity)) {
                            overflowed = true;
                            break;
                        }
                    }
                }
                if (overflowed) {
                    invalidateForBulkResend(peerName, state);
                    resyncRequests.incrementAndGet();
                    notifyBulkRetry(peerName, chunkKey);
                }
            }
        }
    }

    @Override
    public void onTickEnd() {
        flushTick();
    }

    public void flushTick() {
        for (Map.Entry<String, Map<Long, ChunkReplicationState>> peerEntry : peerStates.entrySet()) {
            String peerName = peerEntry.getKey();
            synchronized (peerGate(peerName)) {
                Map<Long, ChunkReplicationState> chunks = peerEntry.getValue();
                if (peerStates.get(peerName) != chunks) {
                    continue;
                }
                List<ChunkDiffBatch> batches = new ArrayList<>();
                List<ChunkReplicationState> drainedStates = new ArrayList<>();
                int blockTotal = 0;
                for (ChunkReplicationState state : chunks.values()) {
                    if (!state.isBulkSent()) {
                        continue;
                    }
                    if (state.queuedDiffCount() == 0L) {
                        continue;
                    }
                    ChunkReplicationState.DrainResult drained = state.drain();
                    if (drained.isEmpty()) {
                        continue;
                    }
                    long sequence = state.nextBroadcastSeq();
                    batches.add(new ChunkDiffBatch(state.chunkKey(), sequence, drained.blocks(), drained.lights(), drained.entities()));
                    drainedStates.add(state);
                    blockTotal += drained.blocks().size();
                }
                if (batches.isEmpty()) {
                    continue;
                }
                WireMessage.ChunkDiff message = new WireMessage.ChunkDiff(batches);
                if (!network.send(peerName, message)) {
                    for (ChunkReplicationState state : drainedStates) {
                        invalidateForBulkResend(peerName, state);
                        notifyBulkRetry(peerName, state.chunkKey());
                    }
                    continue;
                }
                diffsSent.incrementAndGet();
                blocksSent.addAndGet(blockTotal);
            }
        }
    }

    public List<Long> chunksFor(String peerName) {
        Map<Long, ChunkReplicationState> chunks = peerStates.get(peerName);
        if (chunks == null || chunks.isEmpty()) {
            return List.of();
        }
        return new ArrayList<>(chunks.keySet());
    }

    public List<Long> subscribedChunkKeys(UUID worldId) {
        Map<Long, ConcurrentHashMap<String, ChunkReplicationState>> worldMap = worldSubscribers.get(worldId);
        if (worldMap == null || worldMap.isEmpty()) {
            return List.of();
        }
        List<Long> keys = new ArrayList<>(worldMap.size());
        for (Map.Entry<Long, ConcurrentHashMap<String, ChunkReplicationState>> entry : worldMap.entrySet()) {
            if (!entry.getValue().isEmpty()) {
                keys.add(entry.getKey());
            }
        }
        return keys;
    }

    public long lastBroadcastSeq(String peerName, long chunkKey) {
        ChunkReplicationState state = stateFor(peerName, chunkKey, false);
        return state == null ? 0L : state.lastBroadcastSeq();
    }

    public Stats statsSnapshot() {
        return new Stats(bulkSent.get(), diffsSent.get(), blocksSent.get(), resyncRequests.get(), preShipBulksDeferred.get());
    }

    public int peerCount() {
        return peerStates.size();
    }

    public int totalSubscriptionCount() {
        int total = 0;
        for (Map<Long, ChunkReplicationState> chunks : peerStates.values()) {
            total += chunks.size();
        }
        return total;
    }

    public boolean hasSubscribers(long chunkKey) {
        for (Map<Long, ConcurrentHashMap<String, ChunkReplicationState>> worldMap : worldSubscribers.values()) {
            ConcurrentHashMap<String, ChunkReplicationState> chunkPeers = worldMap.get(chunkKey);
            if (chunkPeers != null && !chunkPeers.isEmpty()) {
                return true;
            }
        }
        return false;
    }

    public boolean hasSubscribers(World world, long chunkKey) {
        if (world == null) {
            return hasSubscribers(chunkKey);
        }
        Map<Long, ConcurrentHashMap<String, ChunkReplicationState>> worldMap = worldSubscribers.get(world.getUID());
        if (worldMap == null) {
            return false;
        }
        ConcurrentHashMap<String, ChunkReplicationState> chunkPeers = worldMap.get(chunkKey);
        return chunkPeers != null && !chunkPeers.isEmpty();
    }

    private void notifyEviction(UUID worldId, long chunkKey) {
        ChunkEvictionListener listener = evictionListener;
        if (listener != null) {
            listener.onChunkEvicted(worldId, chunkKey);
        }
    }

    private void subscribeLocked(String peerName, SubscriptionRef subscription, World world, long chunkKey) {
        Map<Long, Set<SubscriptionRef>> peerChunks = peerSubscriptions.computeIfAbsent(peerName, ignored -> new ConcurrentHashMap<>());
        Set<SubscriptionRef> subscriptions = peerChunks.computeIfAbsent(chunkKey, ignored -> new HashSet<>());
        subscriptions.add(subscription);
        ChunkReplicationState state = stateFor(peerName, chunkKey, true);
        registerWorldSubscriber(world, chunkKey, state);
    }

    private boolean hasSubscriptionLocked(String peerName, SubscriptionRef subscription, long chunkKey) {
        Map<Long, Set<SubscriptionRef>> peerChunks = peerSubscriptions.get(peerName);
        if (peerChunks == null) {
            return false;
        }
        Set<SubscriptionRef> subscriptions = peerChunks.get(chunkKey);
        return subscriptions != null && subscriptions.contains(subscription);
    }

    private void unsubscribeLocked(String peerName, SubscriptionRef subscription, long chunkKey) {
        Map<Long, Set<SubscriptionRef>> peerChunks = peerSubscriptions.get(peerName);
        if (peerChunks == null) {
            return;
        }
        Set<SubscriptionRef> subscriptions = peerChunks.get(chunkKey);
        if (subscriptions == null || !subscriptions.remove(subscription)) {
            return;
        }
        if (!subscriptions.isEmpty()) {
            return;
        }
        peerChunks.remove(chunkKey);
        if (peerChunks.isEmpty()) {
            peerSubscriptions.remove(peerName, peerChunks);
        }
        Map<Long, ChunkReplicationState> chunks = peerStates.get(peerName);
        if (chunks == null || chunks.remove(chunkKey) == null) {
            return;
        }
        if (chunks.isEmpty()) {
            peerStates.remove(peerName, chunks);
        }
        for (Map.Entry<UUID, Map<Long, ConcurrentHashMap<String, ChunkReplicationState>>> worldEntry : worldSubscribers.entrySet()) {
            Map<Long, ConcurrentHashMap<String, ChunkReplicationState>> worldMap = worldEntry.getValue();
            Map<String, ChunkReplicationState> chunkPeers = worldMap.get(chunkKey);
            if (chunkPeers == null) {
                continue;
            }
            chunkPeers.remove(peerName);
            if (chunkPeers.isEmpty()) {
                worldMap.remove(chunkKey, chunkPeers);
                notifyEviction(worldEntry.getKey(), chunkKey);
            }
        }
        Map<Long, CanonicalHashCache> hashMap = peerHashes.get(peerName);
        if (hashMap != null) {
            hashMap.remove(chunkKey);
        }
    }

    private void invalidateForBulkResend(String peerName, ChunkReplicationState state) {
        state.resetBulk();
        Map<Long, CanonicalHashCache> hashMap = peerHashes.get(peerName);
        if (hashMap != null) {
            hashMap.remove(state.chunkKey());
        }
    }

    private void notifyBulkRetry(String peerName, long chunkKey) {
        BulkRetryListener listener = bulkRetryListener;
        if (listener != null) {
            listener.onBulkRetryRequired(peerName, chunkKey);
        }
    }

    private ChunkReplicationState stateFor(String peerName, long chunkKey, boolean create) {
        if (create) {
            Map<Long, ChunkReplicationState> chunks = peerStates.computeIfAbsent(peerName, ignored -> new ConcurrentHashMap<>());
            return chunks.computeIfAbsent(chunkKey, key -> new ChunkReplicationState(peerName, key));
        }
        Map<Long, ChunkReplicationState> chunks = peerStates.get(peerName);
        if (chunks == null) {
            return null;
        }
        return chunks.get(chunkKey);
    }

    private void registerWorldSubscriber(World world, long chunkKey, ChunkReplicationState state) {
        if (world == null) {
            return;
        }
        Map<Long, ConcurrentHashMap<String, ChunkReplicationState>> worldMap = worldSubscribers.computeIfAbsent(world.getUID(), ignored -> new ConcurrentHashMap<>());
        ConcurrentHashMap<String, ChunkReplicationState> chunkPeers = worldMap.computeIfAbsent(chunkKey, ignored -> new ConcurrentHashMap<>());
        chunkPeers.put(state.peerName(), state);
    }

    private ConcurrentHashMap<String, ChunkReplicationState> subscribersFor(World world, long chunkKey) {
        if (world == null) {
            return null;
        }
        Map<Long, ConcurrentHashMap<String, ChunkReplicationState>> worldMap = worldSubscribers.get(world.getUID());
        if (worldMap == null) {
            return null;
        }
        return worldMap.get(chunkKey);
    }

    private void cacheCanonicalHash(String peerName, long chunkKey, long hash) {
        Map<Long, CanonicalHashCache> hashMap = peerHashes.computeIfAbsent(peerName, ignored -> new ConcurrentHashMap<>());
        CanonicalHashCache cache = hashMap.computeIfAbsent(chunkKey, ignored -> new CanonicalHashCache());
        cache.hash = hash;
    }

    private void markHashDirty(String peerName, long chunkKey) {
        Map<Long, CanonicalHashCache> hashMap = peerHashes.get(peerName);
        if (hashMap == null) {
            return;
        }
        CanonicalHashCache cache = hashMap.get(chunkKey);
        if (cache != null) {
            cache.hash = 0L;
        }
    }

    private Object peerGate(String peerName) {
        return peerGates.computeIfAbsent(peerName, ignored -> new Object());
    }
}
