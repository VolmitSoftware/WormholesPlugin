package art.arcane.wormholes.network.replication;

import art.arcane.wormholes.network.view.ViewSlice;

import java.io.ByteArrayInputStream;
import java.io.DataInputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.ConcurrentHashMap;

public final class RemoteChunkStore {
    public static final int DEFAULT_DIFF_WINDOW_SIZE = 32;
    public static final long DEFAULT_RESYNC_TIMEOUT_MS = 5_000L;

    public static final class ReplicatedChunk {
        private final long chunkKey;
        private volatile byte[] bulkPayload;
        private volatile ViewSlice slice;
        private volatile long lastAppliedSeq;
        private volatile long firstGapMillis;
        private final TreeMap<Long, ChunkDiffBatch> pending = new TreeMap<>();

        private ReplicatedChunk(long chunkKey) {
            this.chunkKey = chunkKey;
        }

        public long chunkKey() {
            return chunkKey;
        }

        public byte[] bulkPayload() {
            return bulkPayload;
        }

        public ViewSlice slice() {
            return slice;
        }

        public long lastAppliedSeq() {
            return lastAppliedSeq;
        }
    }

    private final int diffWindowSize;
    private final long resyncTimeoutMillis;
    private final Map<Long, ReplicatedChunk> chunks = new ConcurrentHashMap<>();

    public RemoteChunkStore() {
        this(DEFAULT_DIFF_WINDOW_SIZE, DEFAULT_RESYNC_TIMEOUT_MS);
    }

    public RemoteChunkStore(int diffWindowSize, long resyncTimeoutMillis) {
        this.diffWindowSize = Math.max(1, diffWindowSize);
        this.resyncTimeoutMillis = Math.max(0L, resyncTimeoutMillis);
    }

    public int diffWindowSize() {
        return diffWindowSize;
    }

    public long resyncTimeoutMillis() {
        return resyncTimeoutMillis;
    }

    public ReplicatedChunk get(long chunkKey) {
        return chunks.get(chunkKey);
    }

    public List<Long> chunkKeys() {
        return new ArrayList<>(chunks.keySet());
    }

    public ReplicatedChunk applyBulk(ChunkBulk bulk) throws IOException {
        ViewSlice decoded = ViewSlice.read(new DataInputStream(new ByteArrayInputStream(bulk.bulkPayload())));
        ReplicatedChunk chunk = chunks.computeIfAbsent(bulk.chunkKey(), ReplicatedChunk::new);
        synchronized (chunk) {
            if (chunk.slice != null && bulk.sequence() < chunk.lastAppliedSeq) {
                // A stale re-bulk that arrived after newer diffs were already applied; ignore it so it
                // cannot rewind the chunk to an older state.
                return chunk;
            }
            chunk.bulkPayload = bulk.bulkPayload();
            chunk.slice = decoded;
            chunk.lastAppliedSeq = bulk.sequence();
            chunk.firstGapMillis = 0L;
            // Drop diffs this bulk already supersedes, but KEEP any buffered diffs newer than the bulk
            // and re-apply them, so an in-flight change (e.g. a block break) survives a re-bulk instead
            // of being silently cleared.
            chunk.pending.headMap(bulk.sequence() + 1L).clear();
            drainBuffered(chunk);
        }
        return chunk;
    }

    public ApplyOutcome applyDiff(ChunkDiffBatch batch) {
        ReplicatedChunk chunk = chunks.get(batch.chunkKey());
        if (chunk == null) {
            return new ApplyOutcome(false, true, batch.chunkKey(), 0L);
        }
        synchronized (chunk) {
            long expected = chunk.lastAppliedSeq + 1L;
            if (batch.sequence() < expected) {
                return new ApplyOutcome(true, false, batch.chunkKey(), chunk.lastAppliedSeq);
            }
            if (batch.sequence() == expected) {
                applyBatchToSlice(chunk, batch);
                chunk.lastAppliedSeq = batch.sequence();
                drainBuffered(chunk);
                if (chunk.pending.isEmpty()) {
                    chunk.firstGapMillis = 0L;
                }
                return new ApplyOutcome(true, false, batch.chunkKey(), chunk.lastAppliedSeq);
            }
            chunk.pending.put(batch.sequence(), batch);
            if (chunk.firstGapMillis == 0L) {
                chunk.firstGapMillis = System.currentTimeMillis();
            }
            if (chunk.pending.size() > diffWindowSize) {
                long expectedSequence = chunk.lastAppliedSeq + 1L;
                chunk.pending.clear();
                chunk.firstGapMillis = 0L;
                return new ApplyOutcome(false, true, batch.chunkKey(), expectedSequence);
            }
            return new ApplyOutcome(true, false, batch.chunkKey(), chunk.lastAppliedSeq);
        }
    }

    public List<ChunkResyncRequest> collectTimeouts(long nowMillis) {
        List<ChunkResyncRequest> requests = new ArrayList<>();
        for (ReplicatedChunk chunk : chunks.values()) {
            synchronized (chunk) {
                if (chunk.firstGapMillis == 0L || chunk.pending.isEmpty()) {
                    continue;
                }
                if (nowMillis - chunk.firstGapMillis < resyncTimeoutMillis) {
                    continue;
                }
                long expectedSequence = chunk.lastAppliedSeq + 1L;
                chunk.pending.clear();
                chunk.firstGapMillis = 0L;
                requests.add(new ChunkResyncRequest(chunk.chunkKey(), expectedSequence));
            }
        }
        return requests;
    }

    public long hashAt(long chunkKey) {
        ReplicatedChunk chunk = chunks.get(chunkKey);
        if (chunk == null) {
            return 0L;
        }
        ViewSlice slice = chunk.slice;
        if (slice == null) {
            return 0L;
        }
        return slice.contentHash();
    }

    public void remove(long chunkKey) {
        chunks.remove(chunkKey);
    }

    public void clear() {
        chunks.clear();
    }

    public int chunkCount() {
        return chunks.size();
    }

    public List<Long> mismatches(List<ChunkHashProbe.ChunkHashEntry> entries) {
        List<Long> mismatchKeys = new ArrayList<>();
        for (ChunkHashProbe.ChunkHashEntry entry : entries) {
            ReplicatedChunk chunk = chunks.get(entry.chunkKey());
            if (chunk == null) {
                mismatchKeys.add(entry.chunkKey());
                continue;
            }
            if (chunk.lastAppliedSeq < entry.sequence()) {
                mismatchKeys.add(entry.chunkKey());
                continue;
            }
            if (entry.hash() == 0L) {
                continue;
            }
            long localHash = hashAt(entry.chunkKey());
            if (localHash != entry.hash()) {
                mismatchKeys.add(entry.chunkKey());
            }
        }
        return Collections.unmodifiableList(mismatchKeys);
    }

    public record ApplyOutcome(boolean applied, boolean resyncRequested, long chunkKey, long expectedSequenceOrLastApplied) {
    }

    private void drainBuffered(ReplicatedChunk chunk) {
        while (!chunk.pending.isEmpty()) {
            Map.Entry<Long, ChunkDiffBatch> head = chunk.pending.firstEntry();
            long expected = chunk.lastAppliedSeq + 1L;
            if (head.getKey() < expected) {
                chunk.pending.pollFirstEntry();
                continue;
            }
            if (head.getKey() > expected) {
                return;
            }
            chunk.pending.pollFirstEntry();
            applyBatchToSlice(chunk, head.getValue());
            chunk.lastAppliedSeq = head.getKey();
        }
    }

    private void applyBatchToSlice(ReplicatedChunk chunk, ChunkDiffBatch batch) {
        ViewSlice current = chunk.slice;
        if (current == null) {
            return;
        }
        for (BlockChange change : batch.blocks()) {
            int worldX = (((int) (chunk.chunkKey() >> 32)) << 4) + BlockChange.unpackX(change.packedXyz());
            int worldZ = (((int) chunk.chunkKey()) << 4) + BlockChange.unpackZ(change.packedXyz());
            int worldY = BlockChange.unpackY(change.packedXyz());
            if (!current.contains(worldX, worldY, worldZ)) {
                continue;
            }
            int cellIndex = current.cellIndex(worldX, worldY, worldZ);
            int paletteIndex = resolvePaletteIndex(current, change.state());
            if (paletteIndex < 0) {
                continue;
            }
            current.indices()[cellIndex] = (short) paletteIndex;
        }
        for (LightDiff diff : batch.lights()) {
            applyLightDiff(current, diff);
        }
    }

    private static int resolvePaletteIndex(ViewSlice slice, String state) {
        if (state == null) {
            return -1;
        }
        List<String> palette = slice.palette();
        int existing = palette.indexOf(state);
        if (existing >= 0) {
            return existing;
        }
        if (palette.size() > 65535) {
            return -1;
        }
        palette.add(state);
        return palette.size() - 1;
    }

    private void applyLightDiff(ViewSlice slice, LightDiff diff) {
        int sectionMinY = diff.sectionY() * 16;
        int sectionMaxY = sectionMinY + 15;
        if (sectionMaxY < slice.minY() || sectionMinY >= slice.minY() + slice.sizeY()) {
            return;
        }
        byte[] target = slice.light();
        byte[] source = diff.data();
        for (int ly = 0; ly < 16; ly++) {
            int worldY = sectionMinY + ly;
            if (worldY < slice.minY() || worldY >= slice.minY() + slice.sizeY()) {
                continue;
            }
            for (int lz = 0; lz < 16; lz++) {
                for (int lx = 0; lx < 16; lx++) {
                    int worldX = slice.minX() + lx;
                    int worldZ = slice.minZ() + lz;
                    if (!slice.contains(worldX, worldY, worldZ)) {
                        continue;
                    }
                    int sourceIndex = (ly << 8) | (lz << 4) | lx;
                    if ((sourceIndex >> 1) >= source.length) {
                        continue;
                    }
                    int nibble = (sourceIndex & 1) == 0 ? (source[sourceIndex >> 1] & 0x0F) : ((source[sourceIndex >> 1] >> 4) & 0x0F);
                    int cellIndex = slice.cellIndex(worldX, worldY, worldZ);
                    byte existing = target[cellIndex];
                    if (diff.lightType() == LightDiff.TYPE_SKYLIGHT) {
                        target[cellIndex] = (byte) (((nibble & 0x0F) << 4) | (existing & 0x0F));
                    } else {
                        target[cellIndex] = (byte) ((existing & 0xF0) | (nibble & 0x0F));
                    }
                }
            }
        }
    }
}
