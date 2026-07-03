package art.arcane.wormholes.network.replication;

import art.arcane.wormholes.network.view.ViewSlice;

import java.io.ByteArrayInputStream;
import java.io.DataInputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.ConcurrentHashMap;

public final class RemoteChunkStore {
    public static final int DEFAULT_DIFF_WINDOW_SIZE = 32;
    public static final long DEFAULT_RESYNC_TIMEOUT_MS = 5_000L;

    public static final class ReplicatedChunk {
        private final long chunkKey;
        private volatile ViewSlice slice;
        private volatile long lastAppliedSeq;
        private volatile long firstGapMillis;
        private final TreeMap<Long, ChunkDiffBatch> pending = new TreeMap<>();
        private final HashMap<String, Integer> paletteLookup = new HashMap<>(32);
        private long cachedContentHash;
        private boolean contentHashValid;

        private ReplicatedChunk(long chunkKey) {
            this.chunkKey = chunkKey;
        }

        public long chunkKey() {
            return chunkKey;
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
            chunk.slice = decoded;
            chunk.paletteLookup.clear();
            List<String> palette = decoded.palette();
            for (int i = 0; i < palette.size(); i++) {
                chunk.paletteLookup.putIfAbsent(palette.get(i), i);
            }
            chunk.contentHashValid = false;
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
        synchronized (chunk) {
            ViewSlice slice = chunk.slice;
            if (slice == null) {
                return 0L;
            }
            if (!chunk.contentHashValid) {
                chunk.cachedContentHash = slice.contentHash();
                chunk.contentHashValid = true;
            }
            return chunk.cachedContentHash;
        }
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
        if (!batch.blocks().isEmpty()) {
            chunk.contentHashValid = false;
        }
        for (BlockChange change : batch.blocks()) {
            int worldX = (((int) (chunk.chunkKey() >> 32)) << 4) + BlockChange.unpackX(change.packedXyz());
            int worldZ = (((int) chunk.chunkKey()) << 4) + BlockChange.unpackZ(change.packedXyz());
            int worldY = BlockChange.unpackY(change.packedXyz());
            if (!current.contains(worldX, worldY, worldZ)) {
                continue;
            }
            int cellIndex = current.cellIndex(worldX, worldY, worldZ);
            int paletteIndex = resolvePaletteIndex(chunk, current, change.state());
            if (paletteIndex < 0) {
                continue;
            }
            current.indices()[cellIndex] = (short) paletteIndex;
        }
        for (LightDiff diff : batch.lights()) {
            applyLightDiff(current, diff);
        }
    }

    private static int resolvePaletteIndex(ReplicatedChunk chunk, ViewSlice slice, String state) {
        if (state == null) {
            return -1;
        }
        Integer existing = chunk.paletteLookup.get(state);
        if (existing != null) {
            return existing.intValue();
        }
        List<String> palette = slice.palette();
        if (palette.size() > 65535) {
            return -1;
        }
        palette.add(state);
        int index = palette.size() - 1;
        chunk.paletteLookup.put(state, index);
        return index;
    }

    private void applyLightDiff(ViewSlice slice, LightDiff diff) {
        int sectionMinY = diff.sectionY() * 16;
        int sectionMaxY = sectionMinY + 15;
        if (sectionMaxY < slice.minY() || sectionMinY >= slice.minY() + slice.sizeY()) {
            return;
        }
        if (diff.data() != null) {
            applyFullLight(slice, diff, sectionMinY);
        } else if (diff.sparseCells() != null && diff.sparseLevels() != null) {
            applySparseLight(slice, diff, sectionMinY);
        }
    }

    private static void applyFullLight(ViewSlice slice, LightDiff diff, int sectionMinY) {
        int minY = slice.minY();
        int lyStart = Math.max(0, minY - sectionMinY);
        int lyEnd = Math.min(15, minY + slice.sizeY() - 1 - sectionMinY);
        int lxEnd = Math.min(15, slice.sizeX() - 1);
        int lzEnd = Math.min(15, slice.sizeZ() - 1);
        byte[] target = slice.light();
        byte[] source = diff.data();
        boolean sky = diff.lightType() == LightDiff.TYPE_SKYLIGHT;
        for (int ly = lyStart; ly <= lyEnd; ly++) {
            int yBase = (sectionMinY + ly - minY) * slice.sizeZ();
            int sourceYBase = ly << 8;
            for (int lz = 0; lz <= lzEnd; lz++) {
                int rowBase = (yBase + lz) * slice.sizeX();
                int sourceRow = sourceYBase | (lz << 4);
                for (int lx = 0; lx <= lxEnd; lx++) {
                    int sourceIndex = sourceRow | lx;
                    if ((sourceIndex >> 1) >= source.length) {
                        continue;
                    }
                    int nibble = (sourceIndex & 1) == 0 ? (source[sourceIndex >> 1] & 0x0F) : ((source[sourceIndex >> 1] >> 4) & 0x0F);
                    applyLightCell(target, rowBase + lx, sky, nibble);
                }
            }
        }
    }

    private static void applySparseLight(ViewSlice slice, LightDiff diff, int sectionMinY) {
        int minY = slice.minY();
        int maxYExclusive = minY + slice.sizeY();
        int[] cells = diff.sparseCells();
        byte[] levels = diff.sparseLevels();
        byte[] target = slice.light();
        boolean sky = diff.lightType() == LightDiff.TYPE_SKYLIGHT;
        for (int i = 0; i < cells.length; i++) {
            int cell = cells[i];
            int lx = cell & 0xF;
            int lz = (cell >> 4) & 0xF;
            int ly = (cell >> 8) & 0xF;
            int worldY = sectionMinY + ly;
            if (worldY < minY || worldY >= maxYExclusive || lx >= slice.sizeX() || lz >= slice.sizeZ()) {
                continue;
            }
            if ((i >> 1) >= levels.length) {
                continue;
            }
            int nibble = (i & 1) == 0 ? (levels[i >> 1] & 0x0F) : ((levels[i >> 1] >> 4) & 0x0F);
            int cellIndex = ((worldY - minY) * slice.sizeZ() + lz) * slice.sizeX() + lx;
            applyLightCell(target, cellIndex, sky, nibble);
        }
    }

    private static void applyLightCell(byte[] target, int cellIndex, boolean sky, int nibble) {
        byte existing = target[cellIndex];
        if (sky) {
            target[cellIndex] = (byte) (((nibble & 0x0F) << 4) | (existing & 0x0F));
        } else {
            target[cellIndex] = (byte) ((existing & 0xF0) | (nibble & 0x0F));
        }
    }
}
