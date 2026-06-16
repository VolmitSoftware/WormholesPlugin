package art.arcane.wormholes.network.replication;

import art.arcane.wormholes.network.NetworkManager;
import art.arcane.wormholes.network.WireMessage;
import art.arcane.wormholes.network.view.ViewSlice;

import org.bukkit.World;

import java.io.ByteArrayInputStream;
import java.io.DataInputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

public final class ChunkReplicationManager implements BlockChangeFeed {
    public record ReplicationConfig(long maxQueuedDiffsPerPeer) {
        public static ReplicationConfig defaults() {
            return new ReplicationConfig(4096L);
        }
    }

    public record Stats(long bulkSent, long diffsSent, long blocksSent, long resyncRequests, long preShipBulksDeferred) {
    }

    private static final class CanonicalHashCache {
        private volatile long hash;
        private volatile boolean dirty;
        private volatile byte[] lastBulkPayload;

        private CanonicalHashCache() {
            this.hash = 0L;
            this.dirty = true;
            this.lastBulkPayload = null;
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

    @FunctionalInterface
    public interface CanonicalPayloadResolver {
        byte[] resolve(String peerName, long chunkKey);
    }

    private final NetworkManager network;
    private volatile ReplicationConfig config;
    private volatile CanonicalPayloadResolver payloadResolver;
    private final Map<String, Map<Long, ChunkReplicationState>> peerStates = new ConcurrentHashMap<>();
    private final Map<UUID, Map<Long, ConcurrentHashMap<String, ChunkReplicationState>>> worldSubscribers = new ConcurrentHashMap<>();
    private final Map<String, Map<Long, CanonicalHashCache>> peerHashes = new ConcurrentHashMap<>();
    private final Map<String, Map<UUID, Map<Long, PreShipState>>> peerPreShip = new ConcurrentHashMap<>();
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

    public void setPayloadResolver(CanonicalPayloadResolver resolver) {
        this.payloadResolver = resolver;
    }

    public ReplicationConfig config() {
        return config;
    }

    public boolean isBulked(String peerName, long chunkKey) {
        ChunkReplicationState state = stateFor(peerName, chunkKey, false);
        return state != null && state.isBulkSent();
    }

    public void subscribe(String peerName, World world, long chunkKey) {
        ChunkReplicationState state = stateFor(peerName, chunkKey, true);
        registerWorldSubscriber(world, chunkKey, state);
    }

    public void subscribePreShip(String peerName, UUID portalId, World world, List<Long> chunkKeys) {
        if (peerName == null || portalId == null || chunkKeys == null || chunkKeys.isEmpty()) {
            return;
        }
        Map<UUID, Map<Long, PreShipState>> portalMap = peerPreShip.computeIfAbsent(peerName, ignored -> new ConcurrentHashMap<>());
        Map<Long, PreShipState> chunkMap = portalMap.computeIfAbsent(portalId, ignored -> new ConcurrentHashMap<>());
        for (Long chunkKey : chunkKeys) {
            long key = chunkKey.longValue();
            ChunkReplicationState state = stateFor(peerName, key, true);
            registerWorldSubscriber(world, key, state);
            chunkMap.putIfAbsent(key, new PreShipState(portalId));
            preShipBulksDeferred.incrementAndGet();
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
        Map<UUID, Map<Long, PreShipState>> portalMap = peerPreShip.get(peerName);
        if (portalMap == null) {
            return;
        }
        Map<Long, PreShipState> removed = portalMap.remove(portalId);
        if (removed == null) {
            return;
        }
        for (Long chunkKey : removed.keySet()) {
            unsubscribe(peerName, chunkKey.longValue());
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

    public void unsubscribe(String peerName, long chunkKey) {
        Map<Long, ChunkReplicationState> chunks = peerStates.get(peerName);
        if (chunks == null) {
            return;
        }
        ChunkReplicationState removed = chunks.remove(chunkKey);
        if (removed == null) {
            return;
        }
        for (Map<Long, ConcurrentHashMap<String, ChunkReplicationState>> worldMap : worldSubscribers.values()) {
            Map<String, ChunkReplicationState> chunkPeers = worldMap.get(chunkKey);
            if (chunkPeers == null) {
                continue;
            }
            chunkPeers.remove(peerName);
            if (chunkPeers.isEmpty()) {
                worldMap.remove(chunkKey, chunkPeers);
            }
        }
        Map<Long, CanonicalHashCache> hashMap = peerHashes.get(peerName);
        if (hashMap != null) {
            hashMap.remove(chunkKey);
        }
    }

    public void clearPeer(String peerName) {
        Map<Long, ChunkReplicationState> chunks = peerStates.remove(peerName);
        if (chunks != null) {
            for (Map<Long, ConcurrentHashMap<String, ChunkReplicationState>> worldMap : worldSubscribers.values()) {
                for (Map.Entry<Long, ConcurrentHashMap<String, ChunkReplicationState>> entry : worldMap.entrySet()) {
                    ConcurrentHashMap<String, ChunkReplicationState> chunkPeers = entry.getValue();
                    chunkPeers.remove(peerName);
                    if (chunkPeers.isEmpty()) {
                        worldMap.remove(entry.getKey(), chunkPeers);
                    }
                }
            }
        }
        peerHashes.remove(peerName);
        peerPreShip.remove(peerName);
    }

    public long allocateBulkSequence(String peerName, long chunkKey) {
        ChunkReplicationState state = stateFor(peerName, chunkKey, true);
        return state.nextBroadcastSeq();
    }

    public void recordBulkSent(String peerName, long chunkKey, long sequence) {
        ChunkReplicationState state = stateFor(peerName, chunkKey, true);
        state.markBulkSent();
        bulkSent.incrementAndGet();
    }

    public void sendBulk(String peerName, long chunkKey, byte[] payload) {
        ChunkReplicationState state = stateFor(peerName, chunkKey, true);
        long sequence = state.nextBroadcastSeq();
        state.markBulkSent();
        WireMessage.ChunkBulkBatch batch = new WireMessage.ChunkBulkBatch(List.of(new ChunkBulk(chunkKey, sequence, payload)));
        network.send(peerName, batch);
        bulkSent.incrementAndGet();
        cacheCanonicalHash(peerName, chunkKey, payload);
    }

    public void recordCanonicalHash(String peerName, long chunkKey, byte[] payload) {
        cacheCanonicalHash(peerName, chunkKey, payload);
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
        if (!cache.dirty) {
            return cache.hash;
        }
        CanonicalPayloadResolver resolver = payloadResolver;
        if (resolver != null) {
            byte[] fresh = resolver.resolve(peerName, chunkKey);
            if (fresh != null) {
                cache.lastBulkPayload = fresh;
                cache.hash = hashOfPayload(fresh);
                cache.dirty = false;
                return cache.hash;
            }
        }
        return 0L;
    }

    public void requestResync(String peerName, long chunkKey) {
        ChunkReplicationState state = stateFor(peerName, chunkKey, true);
        state.resetBulk();
        resyncRequests.incrementAndGet();
        Map<Long, CanonicalHashCache> hashMap = peerHashes.get(peerName);
        if (hashMap != null) {
            hashMap.remove(chunkKey);
        }
    }

    @Override
    public void onBlockChange(World world, long chunkKey, BlockChange change) {
        ConcurrentHashMap<String, ChunkReplicationState> subscribers = subscribersFor(world, chunkKey);
        if (subscribers == null || subscribers.isEmpty()) {
            return;
        }
        long capacity = config.maxQueuedDiffsPerPeer();
        for (ChunkReplicationState state : subscribers.values()) {
            state.appendBlock(change, capacity);
            markHashDirty(state.peerName(), chunkKey);
        }
    }

    @Override
    public void onLightChange(World world, long chunkKey, LightDiff diff) {
        ConcurrentHashMap<String, ChunkReplicationState> subscribers = subscribersFor(world, chunkKey);
        if (subscribers == null || subscribers.isEmpty()) {
            return;
        }
        long capacity = config.maxQueuedDiffsPerPeer();
        for (ChunkReplicationState state : subscribers.values()) {
            state.appendLight(diff, capacity);
            markHashDirty(state.peerName(), chunkKey);
        }
    }

    @Override
    public void onBlockEntityChange(World world, long chunkKey, BlockEntityDiff diff) {
        ConcurrentHashMap<String, ChunkReplicationState> subscribers = subscribersFor(world, chunkKey);
        if (subscribers == null || subscribers.isEmpty()) {
            return;
        }
        long capacity = config.maxQueuedDiffsPerPeer();
        for (ChunkReplicationState state : subscribers.values()) {
            state.appendBlockEntity(diff, capacity);
            markHashDirty(state.peerName(), chunkKey);
        }
    }

    @Override
    public void onTickEnd() {
        flushTick();
    }

    public void flushTick() {
        for (Map.Entry<String, Map<Long, ChunkReplicationState>> peerEntry : peerStates.entrySet()) {
            String peerName = peerEntry.getKey();
            Map<Long, ChunkReplicationState> chunks = peerEntry.getValue();
            List<ChunkDiffBatch> batches = new ArrayList<>();
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
                blockTotal += drained.blocks().size();
            }
            if (batches.isEmpty()) {
                continue;
            }
            WireMessage.ChunkDiff message = new WireMessage.ChunkDiff(batches);
            network.send(peerName, message);
            diffsSent.incrementAndGet();
            blocksSent.addAndGet(blockTotal);
        }
    }

    public List<Long> chunksFor(String peerName) {
        Map<Long, ChunkReplicationState> chunks = peerStates.get(peerName);
        if (chunks == null || chunks.isEmpty()) {
            return List.of();
        }
        return new ArrayList<>(chunks.keySet());
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

    private void cacheCanonicalHash(String peerName, long chunkKey, byte[] payload) {
        if (payload == null) {
            return;
        }
        Map<Long, CanonicalHashCache> hashMap = peerHashes.computeIfAbsent(peerName, ignored -> new ConcurrentHashMap<>());
        CanonicalHashCache cache = hashMap.computeIfAbsent(chunkKey, ignored -> new CanonicalHashCache());
        cache.lastBulkPayload = payload;
        cache.hash = hashOfPayload(payload);
        cache.dirty = false;
    }

    private static long hashOfPayload(byte[] payload) {
        if (payload == null) {
            return 0L;
        }
        try {
            return ViewSlice.read(new DataInputStream(new ByteArrayInputStream(payload))).contentHash();
        } catch (IOException e) {
            return 0L;
        }
    }

    private void markHashDirty(String peerName, long chunkKey) {
        Map<Long, CanonicalHashCache> hashMap = peerHashes.get(peerName);
        if (hashMap == null) {
            return;
        }
        CanonicalHashCache cache = hashMap.get(chunkKey);
        if (cache != null) {
            cache.dirty = true;
        }
    }
}
