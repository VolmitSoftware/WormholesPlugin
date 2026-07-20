package art.arcane.wormholes.render;

import art.arcane.wormholes.platform.WormholesPlatform;
import com.github.retrooper.packetevents.event.PacketListenerAbstract;
import com.github.retrooper.packetevents.event.PacketListenerPriority;
import com.github.retrooper.packetevents.event.PacketSendEvent;
import com.github.retrooper.packetevents.netty.buffer.ByteBufHelper;
import com.github.retrooper.packetevents.protocol.packettype.PacketType;
import com.github.retrooper.packetevents.protocol.player.ClientVersion;

import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

import org.bukkit.entity.Player;

public final class ProjectionClientChunkTracker extends PacketListenerAbstract implements ProjectionChunkVisibility {
    private final ConcurrentHashMap<UUID, PlayerChunks> sentChunks;
    private final AtomicLong lifecycleClock;

    public ProjectionClientChunkTracker() {
        super(PacketListenerPriority.MONITOR);
        this.sentChunks = new ConcurrentHashMap<UUID, PlayerChunks>();
        this.lifecycleClock = new AtomicLong();
    }

    @Override
    public void onPacketSend(PacketSendEvent event) {
        if (event.isCancelled()) {
            return;
        }
        UUID playerId = event.getUser().getUUID();
        if (playerId == null) {
            return;
        }
        if (event.getPacketType() == PacketType.Play.Server.CHUNK_DATA) {
            int readerIndex = ByteBufHelper.readerIndex(event.getByteBuf());
            int chunkX;
            int chunkZ;
            try {
                chunkX = ByteBufHelper.readInt(event.getByteBuf());
                chunkZ = ByteBufHelper.readInt(event.getByteBuf());
            } finally {
                ByteBufHelper.readerIndex(event.getByteBuf(), readerIndex);
            }
            long chunkKey = chunkKey(chunkX, chunkZ);
            PlayerChunks chunks = sentChunks.computeIfAbsent(playerId, ignored -> newPlayerChunks());
            long loadToken = reserveLoad(chunks, chunkKey);
            event.getTasksAfterSend().add(() -> markSent(playerId, chunks, chunkKey, loadToken));
            return;
        }
        if (event.getPacketType() == PacketType.Play.Server.UNLOAD_CHUNK) {
            int readerIndex = ByteBufHelper.readerIndex(event.getByteBuf());
            int chunkX;
            int chunkZ;
            try {
                if (event.getServerVersion().toClientVersion().isNewerThanOrEquals(ClientVersion.V_1_20_2)) {
                    long packed = ByteBufHelper.readLong(event.getByteBuf());
                    chunkX = (int) packed;
                    chunkZ = (int) (packed >> 32);
                } else {
                    chunkX = ByteBufHelper.readInt(event.getByteBuf());
                    chunkZ = ByteBufHelper.readInt(event.getByteBuf());
                }
            } finally {
                ByteBufHelper.readerIndex(event.getByteBuf(), readerIndex);
            }
            markUnsent(playerId, chunkKey(chunkX, chunkZ));
            return;
        }
        if (event.getPacketType() == PacketType.Play.Server.JOIN_GAME
            || event.getPacketType() == PacketType.Play.Server.RESPAWN) {
            reset(playerId);
            return;
        }
        if (event.getPacketType() == PacketType.Play.Server.DISCONNECT) {
            forget(playerId);
        }
    }

    @Override
    public boolean isChunkSent(Player observer, int chunkX, int chunkZ) {
        if (observer == null || !observer.isOnline()) {
            return false;
        }
        if (WormholesPlatform.supportsSentChunkQuery()) {
            return WormholesPlatform.isChunkSent(observer, chunkX, chunkZ);
        }
        PlayerChunks chunks = sentChunks.get(observer.getUniqueId());
        return chunks != null && chunks.sent.contains(chunkKey(chunkX, chunkZ));
    }

    @Override
    public long revision(Player observer) {
        if (observer == null) {
            return Long.MIN_VALUE;
        }
        return revision(observer.getUniqueId());
    }

    @Override
    public long chunkRevision(Player observer, int chunkX, int chunkZ) {
        if (observer == null) {
            return Long.MIN_VALUE;
        }
        return chunkRevision(observer.getUniqueId(), chunkX, chunkZ);
    }

    public void forget(UUID playerId) {
        if (playerId != null) {
            sentChunks.remove(playerId);
        }
    }

    public void clear() {
        sentChunks.clear();
    }

    static long chunkKey(int chunkX, int chunkZ) {
        return ((long) chunkX & 0xFFFFFFFFL) | (((long) chunkZ & 0xFFFFFFFFL) << 32);
    }

    void markSent(UUID playerId, long chunkKey) {
        PlayerChunks chunks = sentChunks.computeIfAbsent(playerId, ignored -> newPlayerChunks());
        long loadToken = reserveLoad(chunks, chunkKey);
        markSent(playerId, chunks, chunkKey, loadToken);
    }

    void markUnsent(UUID playerId, long chunkKey) {
        PlayerChunks chunks = sentChunks.computeIfAbsent(playerId, ignored -> newPlayerChunks());
        chunks.pendingLoads.remove(Long.valueOf(chunkKey));
        chunks.sent.remove(chunkKey);
        chunks.contentRevisions.remove(Long.valueOf(chunkKey));
        chunks.revision.set(lifecycleClock.incrementAndGet());
    }

    boolean isTracked(UUID playerId, int chunkX, int chunkZ) {
        PlayerChunks chunks = sentChunks.get(playerId);
        return chunks != null && chunks.sent.contains(chunkKey(chunkX, chunkZ));
    }

    long revision(UUID playerId) {
        PlayerChunks chunks = sentChunks.get(playerId);
        return chunks == null ? Long.MIN_VALUE : chunks.revision.get();
    }

    long chunkRevision(UUID playerId, int chunkX, int chunkZ) {
        PlayerChunks chunks = sentChunks.get(playerId);
        if (chunks == null) {
            return Long.MIN_VALUE;
        }
        Long revision = chunks.contentRevisions.get(Long.valueOf(chunkKey(chunkX, chunkZ)));
        return revision == null ? Long.MIN_VALUE : revision.longValue();
    }

    private void reset(UUID playerId) {
        sentChunks.put(playerId, newPlayerChunks());
    }

    private PlayerChunks newPlayerChunks() {
        return new PlayerChunks(lifecycleClock.incrementAndGet());
    }

    private static long reserveLoad(PlayerChunks chunks, long chunkKey) {
        long loadToken = chunks.loadSequence.incrementAndGet();
        chunks.pendingLoads.put(Long.valueOf(chunkKey), Long.valueOf(loadToken));
        return loadToken;
    }

    private void markSent(UUID playerId, PlayerChunks chunks, long chunkKey, long loadToken) {
        if (sentChunks.get(playerId) != chunks
            || !chunks.pendingLoads.remove(Long.valueOf(chunkKey), Long.valueOf(loadToken))) {
            return;
        }
        chunks.sent.add(chunkKey);
        long revision = lifecycleClock.incrementAndGet();
        chunks.contentRevisions.put(Long.valueOf(chunkKey), Long.valueOf(revision));
        chunks.revision.set(revision);
    }

    private static final class PlayerChunks {
        private final Set<Long> sent;
        private final ConcurrentHashMap<Long, Long> pendingLoads;
        private final ConcurrentHashMap<Long, Long> contentRevisions;
        private final AtomicLong loadSequence;
        private final AtomicLong revision;

        private PlayerChunks(long revision) {
            this.sent = ConcurrentHashMap.newKeySet();
            this.pendingLoads = new ConcurrentHashMap<Long, Long>();
            this.contentRevisions = new ConcurrentHashMap<Long, Long>();
            this.loadSequence = new AtomicLong();
            this.revision = new AtomicLong(revision);
        }
    }
}
