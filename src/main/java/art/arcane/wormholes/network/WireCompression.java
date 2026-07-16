package art.arcane.wormholes.network;

import com.github.luben.zstd.Zstd;
import com.github.luben.zstd.ZstdCompressCtx;
import com.github.luben.zstd.ZstdDecompressCtx;
import com.github.luben.zstd.ZstdDictCompress;
import com.github.luben.zstd.ZstdDictDecompress;

import java.io.IOException;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Iterator;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.locks.ReentrantReadWriteLock;
import java.util.logging.Level;
import java.util.logging.Logger;

public final class WireCompression {
    public static final byte MODE_NONE = 0;
    public static final byte MODE_ZSTD_DICTLESS = 1;
    public static final byte MODE_ZSTD_DICT = 2;
    public static final int COMPRESS_THRESHOLD_BYTES = 256;
    public static final int MAX_DECOMPRESSED_BYTES = 32 * 1024 * 1024;
    public static final int DEFAULT_LEVEL = 3;
    public static final int MIN_LEVEL = 1;
    public static final int MAX_LEVEL = 22;
    public static final int MAX_POOL_SIZE = 16;

    private static final Logger LOG = Logger.getLogger(WireCompression.class.getName());
    private static final Object ZSTD_PROBE_LOCK = new Object();
    private static final int MAX_RETIRED_DICTIONARIES = 2;
    private static final int SCRATCH_RETAIN_LIMIT_BYTES = 1024 * 1024;
    private static final byte[] EMPTY_DICTIONARY = new byte[0];
    private static final ThreadLocal<byte[]> COMPRESS_SCRATCH = new ThreadLocal<>();
    private static volatile Boolean zstdUsable;

    private volatile int compressionLevel;
    private final AtomicReference<DictionaryState> dictionaryState = new AtomicReference<>(DictionaryState.EMPTY);
    private final ReentrantReadWriteLock dictLock = new ReentrantReadWriteLock();
    private final ArrayDeque<RetiredDictionary> retired = new ArrayDeque<>();
    private final Deque<PooledCompressContext> compressPool = new ArrayDeque<>();
    private final Deque<PooledDecompressContext> decompressPool = new ArrayDeque<>();
    private final AtomicLong rawBytesIn = new AtomicLong();
    private final AtomicLong wireBytesIn = new AtomicLong();
    private final AtomicLong rawBytesOut = new AtomicLong();
    private final AtomicLong wireBytesOut = new AtomicLong();
    private final AtomicLong noneCount = new AtomicLong();
    private final AtomicLong dictlessCount = new AtomicLong();
    private final AtomicLong dictModeCount = new AtomicLong();
    private volatile boolean closed;

    public WireCompression(int compressionLevel) {
        this.compressionLevel = clampLevel(compressionLevel);
    }

    public static int clampLevel(int level) {
        if (level < MIN_LEVEL) {
            return MIN_LEVEL;
        }
        if (level > MAX_LEVEL) {
            return MAX_LEVEL;
        }
        return level;
    }

    public static boolean isZstdUsable() {
        Boolean usable = zstdUsable;
        if (usable != null) {
            return usable.booleanValue();
        }
        synchronized (ZSTD_PROBE_LOCK) {
            if (zstdUsable == null) {
                try {
                    Zstd.compressBound(64L);
                    zstdUsable = Boolean.TRUE;
                } catch (Throwable ex) {
                    zstdUsable = Boolean.FALSE;
                    LOG.log(Level.WARNING, "zstd-jni native library unavailable; wire frames will ship uncompressed (MODE_NONE)", ex);
                }
            }
            return zstdUsable.booleanValue();
        }
    }

    public int compressionLevel() {
        return compressionLevel;
    }

    public void setCompressionLevel(int level) {
        int nextLevel = clampLevel(level);
        dictLock.writeLock().lock();
        try {
            ensureMutable();
            if (compressionLevel == nextLevel) {
                return;
            }
            compressionLevel = nextLevel;
            DictionaryState previous = dictionaryState.get();
            if (previous.dictionary != null) {
                DictionaryState replacement = new DictionaryState(
                    previous.dictionary,
                    new ZstdDictCompress(previous.dictionary.bytes(), nextLevel),
                    new ZstdDictDecompress(previous.dictionary.bytes())
                );
                dictionaryState.set(replacement);
                clearPools();
                previous.release();
            } else {
                clearPools();
            }
        } finally {
            dictLock.writeLock().unlock();
        }
    }

    public void installDictionary(CompressionDictionary dictionary) {
        Objects.requireNonNull(dictionary, "dictionary");
        dictLock.writeLock().lock();
        try {
            ensureMutable();
            ZstdDictCompress compress = new ZstdDictCompress(dictionary.bytes(), compressionLevel);
            ZstdDictDecompress decompress = new ZstdDictDecompress(dictionary.bytes());
            DictionaryState previous = dictionaryState.getAndSet(new DictionaryState(dictionary, compress, decompress));
            clearPools();
            if (previous.dictionary != null) {
                if (previous.dictionary.version() == dictionary.version()) {
                    previous.release();
                } else {
                    if (previous.compress != null) {
                        previous.compress.close();
                    }
                    retired.addFirst(new RetiredDictionary(previous.dictionary.version(), previous.decompress));
                }
            }
            Iterator<RetiredDictionary> iterator = retired.iterator();
            while (iterator.hasNext()) {
                RetiredDictionary entry = iterator.next();
                if (entry.version == dictionary.version()) {
                    iterator.remove();
                    entry.release();
                }
            }
            while (retired.size() > MAX_RETIRED_DICTIONARIES) {
                retired.removeLast().release();
            }
        } finally {
            dictLock.writeLock().unlock();
        }
    }

    public void clearDictionary() {
        dictLock.writeLock().lock();
        try {
            if (closed) {
                return;
            }
            clearDictionaryLocked();
        } finally {
            dictLock.writeLock().unlock();
        }
    }

    public void close() {
        dictLock.writeLock().lock();
        try {
            if (closed) {
                return;
            }
            closed = true;
            clearDictionaryLocked();
        } finally {
            dictLock.writeLock().unlock();
        }
    }

    public CompressionDictionary currentDictionary() {
        return dictionaryState.get().dictionary;
    }

    public boolean hasDictionary() {
        return dictionaryState.get().dictionary != null;
    }

    public byte[] encode(byte[] payload, boolean dictNegotiated) throws IOException {
        return encode(payload, dictNegotiated, compressionLevel);
    }

    public byte[] encode(byte[] payload, boolean dictNegotiated, int levelOverride) throws IOException {
        if (!dictNegotiated) {
            return encode(payload, 0, levelOverride);
        }
        CompressionDictionary current = currentDictionary();
        return encode(payload, current == null ? 0 : current.version(), levelOverride);
    }

    public byte[] encode(byte[] payload, int negotiatedDictVersion) throws IOException {
        return encode(payload, negotiatedDictVersion, compressionLevel);
    }

    private byte[] encode(byte[] payload, int negotiatedDictVersion, int levelOverride) throws IOException {
        Objects.requireNonNull(payload, "payload");
        ensureOpen();
        if (payload.length < COMPRESS_THRESHOLD_BYTES || !isZstdUsable()) {
            return frameNone(payload);
        }
        dictLock.readLock().lock();
        try {
            ensureOpen();
            DictionaryState state = dictionaryState.get();
            boolean useDict = usableDictVersion(state, negotiatedDictVersion);
            int headerLength = useDict ? 5 : 1;
            long bound = Zstd.compressBound(payload.length);
            if (bound + headerLength > Integer.MAX_VALUE - 8) {
                return frameNone(payload);
            }
            byte[] scratch = compressScratch(headerLength + (int) bound);
            try {
                PooledCompressContext pooled = borrowCompressCtx();
                ZstdCompressCtx ctx = pooled.context;
                long written;
                try {
                    ctx.setLevel(clampLevel(levelOverride));
                    if (useDict) {
                        ctx.loadDict(state.compress);
                        pooled.dictionaryLoaded = true;
                    } else if (pooled.dictionaryLoaded) {
                        ctx.loadDict(EMPTY_DICTIONARY);
                        pooled.dictionaryLoaded = false;
                    }
                    written = ctx.compressByteArray(scratch, headerLength, scratch.length - headerLength,
                        payload, 0, payload.length);
                } finally {
                    recycleCompressCtx(pooled);
                }
                if (Zstd.isError(written)) {
                    return frameNone(payload);
                }
                int compressedLength = (int) written;
                if (compressedLength >= payload.length) {
                    return frameNone(payload);
                }
                byte mode = useDict ? MODE_ZSTD_DICT : MODE_ZSTD_DICTLESS;
                byte[] framed = new byte[headerLength + compressedLength];
                framed[0] = mode;
                if (useDict) {
                    writeLittleEndianInt(framed, 1, state.dictionary.version());
                }
                System.arraycopy(scratch, headerLength, framed, headerLength, compressedLength);
                recordOut(payload.length, framed.length, mode);
                return framed;
            } finally {
                releaseCompressScratch(scratch);
            }
        } catch (IOException ex) {
            throw ex;
        } catch (Throwable ex) {
            markZstdUnusable(ex);
            return frameNone(payload);
        } finally {
            dictLock.readLock().unlock();
        }
    }

    public byte[] encodeFramedFrame(byte typeId, byte[] payload, int negotiatedDictVersion) throws IOException {
        Objects.requireNonNull(payload, "payload");
        ensureOpen();
        if (payload.length < COMPRESS_THRESHOLD_BYTES || !isZstdUsable()) {
            return plainFramed(typeId, payload);
        }
        dictLock.readLock().lock();
        try {
            ensureOpen();
            DictionaryState state = dictionaryState.get();
            boolean useDict = usableDictVersion(state, negotiatedDictVersion);
            int headerLength = useDict ? 10 : 6;
            long bound = Zstd.compressBound(payload.length);
            if (bound + headerLength > Integer.MAX_VALUE - 8) {
                return plainFramed(typeId, payload);
            }
            byte[] scratch = compressScratch(headerLength + (int) bound);
            try {
                PooledCompressContext pooled = borrowCompressCtx();
                ZstdCompressCtx ctx = pooled.context;
                long written;
                try {
                    ctx.setLevel(compressionLevel);
                    if (useDict) {
                        ctx.loadDict(state.compress);
                        pooled.dictionaryLoaded = true;
                    } else if (pooled.dictionaryLoaded) {
                        ctx.loadDict(EMPTY_DICTIONARY);
                        pooled.dictionaryLoaded = false;
                    }
                    written = ctx.compressByteArray(scratch, headerLength, scratch.length - headerLength,
                        payload, 0, payload.length);
                } finally {
                    recycleCompressCtx(pooled);
                }
                if (Zstd.isError(written) || (int) written >= payload.length) {
                    return plainFramed(typeId, payload);
                }
                int compressedLength = (int) written;
                byte mode = useDict ? MODE_ZSTD_DICT : MODE_ZSTD_DICTLESS;
                int frameLength = 1 + 1 + (useDict ? 4 : 0) + compressedLength;
                byte[] frame = new byte[4 + frameLength];
                writeBigEndianInt(frame, 0, frameLength);
                frame[4] = typeId;
                frame[5] = mode;
                int bodyOffset = 6;
                if (useDict) {
                    writeLittleEndianInt(frame, 6, state.dictionary.version());
                    bodyOffset = 10;
                }
                System.arraycopy(scratch, headerLength, frame, bodyOffset, compressedLength);
                recordOut(payload.length, frameLength - 1, mode);
                return frame;
            } finally {
                releaseCompressScratch(scratch);
            }
        } catch (IOException ex) {
            throw ex;
        } catch (Throwable ex) {
            markZstdUnusable(ex);
            return plainFramed(typeId, payload);
        } finally {
            dictLock.readLock().unlock();
        }
    }

    public DecodeResult decode(byte[] frameBody) throws IOException {
        return decode(frameBody, MAX_DECOMPRESSED_BYTES);
    }

    public DecodeResult decode(byte[] frameBody, int maxDecompressedBytes) throws IOException {
        Objects.requireNonNull(frameBody, "frameBody");
        ensureOpen();
        if (maxDecompressedBytes <= 0 || maxDecompressedBytes > MAX_DECOMPRESSED_BYTES) {
            throw new IllegalArgumentException("maxDecompressedBytes must be between 1 and " + MAX_DECOMPRESSED_BYTES);
        }
        if (frameBody.length < 1) {
            throw new IOException("compression mode byte missing");
        }
        byte mode = frameBody[0];
        switch (mode) {
            case MODE_NONE: {
                if (frameBody.length - 1 > maxDecompressedBytes) {
                    throw new IOException("uncompressed payload exceeds " + maxDecompressedBytes + " bytes");
                }
                byte[] payload = new byte[frameBody.length - 1];
                System.arraycopy(frameBody, 1, payload, 0, payload.length);
                recordIn(payload.length, frameBody.length, MODE_NONE);
                return new DecodeResult(mode, 0, payload);
            }
            case MODE_ZSTD_DICTLESS: {
                dictLock.readLock().lock();
                try {
                    ensureOpen();
                    byte[] payload = decompressDictless(frameBody, 1, frameBody.length - 1, maxDecompressedBytes);
                    recordIn(payload.length, frameBody.length, MODE_ZSTD_DICTLESS);
                    return new DecodeResult(mode, 0, payload);
                } finally {
                    dictLock.readLock().unlock();
                }
            }
            case MODE_ZSTD_DICT: {
                if (frameBody.length < 5) {
                    throw new IOException("dict-version field truncated");
                }
                int version = readLittleEndianInt(frameBody, 1);
                dictLock.readLock().lock();
                try {
                    ensureOpen();
                    ZstdDictDecompress dictDecompress = resolveDecodeDictionary(version);
                    if (dictDecompress == null) {
                        throw new IOException("missing dictionary version " + version + " for inbound frame");
                    }
                    byte[] payload = decompressWithDict(frameBody, 5, frameBody.length - 5, dictDecompress, maxDecompressedBytes);
                    recordIn(payload.length, frameBody.length, MODE_ZSTD_DICT);
                    return new DecodeResult(mode, version, payload);
                } finally {
                    dictLock.readLock().unlock();
                }
            }
            default:
                throw new IOException("unknown compression mode " + mode);
        }
    }

    public Stats snapshot() {
        return new Stats(
            rawBytesIn.get(),
            wireBytesIn.get(),
            rawBytesOut.get(),
            wireBytesOut.get(),
            noneCount.get(),
            dictlessCount.get(),
            dictModeCount.get()
        );
    }

    public void resetStats() {
        rawBytesIn.set(0L);
        wireBytesIn.set(0L);
        rawBytesOut.set(0L);
        wireBytesOut.set(0L);
        noneCount.set(0L);
        dictlessCount.set(0L);
        dictModeCount.set(0L);
    }

    private static boolean usableDictVersion(DictionaryState state, int negotiatedDictVersion) {
        return negotiatedDictVersion > 0
            && state.compress != null
            && state.dictionary != null
            && state.dictionary.version() == negotiatedDictVersion;
    }

    private static void markZstdUnusable(Throwable ex) {
        if (zstdUsable != Boolean.FALSE) {
            zstdUsable = Boolean.FALSE;
            LOG.log(Level.WARNING, "zstd-jni compression failed; falling back to uncompressed wire frames (MODE_NONE)", ex);
        }
    }

    private void ensureMutable() {
        if (closed) {
            throw new IllegalStateException("wire compression is closed");
        }
    }

    private void ensureOpen() throws IOException {
        if (closed) {
            throw new IOException("wire compression is closed");
        }
    }

    private void clearDictionaryLocked() {
        DictionaryState previous = dictionaryState.getAndSet(DictionaryState.EMPTY);
        clearPools();
        previous.release();
        while (!retired.isEmpty()) {
            retired.removeFirst().release();
        }
    }

    private static byte[] compressScratch(int size) {
        byte[] scratch = COMPRESS_SCRATCH.get();
        if (scratch == null || scratch.length < size) {
            scratch = new byte[size];
            COMPRESS_SCRATCH.set(scratch);
        }
        return scratch;
    }

    private static void releaseCompressScratch(byte[] scratch) {
        if (scratch.length > SCRATCH_RETAIN_LIMIT_BYTES) {
            COMPRESS_SCRATCH.remove();
        }
    }

    private ZstdDictDecompress resolveDecodeDictionary(int version) {
        DictionaryState state = dictionaryState.get();
        if (state.decompress != null && state.dictionary != null && state.dictionary.version() == version) {
            return state.decompress;
        }
        for (RetiredDictionary entry : retired) {
            if (entry.version == version) {
                return entry.decompress;
            }
        }
        return null;
    }

    private byte[] frameNone(byte[] payload) {
        byte[] body = prependMode(payload, MODE_NONE, 0, false);
        recordOut(payload.length, body.length, MODE_NONE);
        return body;
    }

    private byte[] plainFramed(byte typeId, byte[] payload) {
        int frameLength = 1 + 1 + payload.length;
        byte[] frame = new byte[4 + frameLength];
        writeBigEndianInt(frame, 0, frameLength);
        frame[4] = typeId;
        frame[5] = MODE_NONE;
        System.arraycopy(payload, 0, frame, 6, payload.length);
        recordOut(payload.length, payload.length + 1, MODE_NONE);
        return frame;
    }

    private void recordOut(int rawLength, int wireLength, byte mode) {
        rawBytesOut.addAndGet(rawLength);
        wireBytesOut.addAndGet(wireLength);
        bumpModeCounter(mode);
    }

    private void recordIn(int rawLength, int wireLength, byte mode) {
        rawBytesIn.addAndGet(rawLength);
        wireBytesIn.addAndGet(wireLength);
        bumpModeCounter(mode);
    }

    private void bumpModeCounter(byte mode) {
        switch (mode) {
            case MODE_NONE -> noneCount.incrementAndGet();
            case MODE_ZSTD_DICTLESS -> dictlessCount.incrementAndGet();
            case MODE_ZSTD_DICT -> dictModeCount.incrementAndGet();
            default -> {
            }
        }
    }

    private byte[] decompressDictless(byte[] body, int offset, int length, int maxDecompressedBytes) throws IOException {
        long originalSize = Zstd.getFrameContentSize(body, offset, length);
        if (originalSize > maxDecompressedBytes) {
            throw new IOException("zstd payload exceeds " + maxDecompressedBytes + " bytes");
        }
        if (originalSize < 0L) {
            return streamDecompress(body, offset, length, null, maxDecompressedBytes);
        }
        PooledDecompressContext pooled = borrowDecompressCtx();
        ZstdDecompressCtx ctx = pooled.context;
        try {
            if (pooled.dictionaryLoaded) {
                ctx.loadDict(EMPTY_DICTIONARY);
                pooled.dictionaryLoaded = false;
            }
            byte[] out = new byte[(int) originalSize];
            long written = ctx.decompressByteArray(out, 0, out.length, body, offset, length);
            if (Zstd.isError(written)) {
                throw new IOException("zstd decompression failed: " + Zstd.getErrorName(written));
            }
            if ((int) written != out.length) {
                byte[] trimmed = new byte[(int) written];
                System.arraycopy(out, 0, trimmed, 0, trimmed.length);
                return trimmed;
            }
            return out;
        } finally {
            recycleDecompressCtx(pooled);
        }
    }

    private byte[] decompressWithDict(byte[] body, int offset, int length, ZstdDictDecompress dictDecompress,
                                      int maxDecompressedBytes) throws IOException {
        long originalSize = Zstd.getFrameContentSize(body, offset, length);
        if (originalSize > maxDecompressedBytes) {
            throw new IOException("zstd payload exceeds " + maxDecompressedBytes + " bytes");
        }
        if (originalSize < 0L) {
            return streamDecompress(body, offset, length, dictDecompress, maxDecompressedBytes);
        }
        PooledDecompressContext pooled = borrowDecompressCtx();
        ZstdDecompressCtx ctx = pooled.context;
        try {
            ctx.loadDict(dictDecompress);
            pooled.dictionaryLoaded = true;
            byte[] out = new byte[(int) originalSize];
            long written = ctx.decompressByteArray(out, 0, out.length, body, offset, length);
            if (Zstd.isError(written)) {
                throw new IOException("zstd dict decompression failed: " + Zstd.getErrorName(written));
            }
            if ((int) written != out.length) {
                byte[] trimmed = new byte[(int) written];
                System.arraycopy(out, 0, trimmed, 0, trimmed.length);
                return trimmed;
            }
            return out;
        } finally {
            recycleDecompressCtx(pooled);
        }
    }

    private byte[] streamDecompress(byte[] body, int offset, int length, ZstdDictDecompress dictDecompress,
                                    int maxDecompressedBytes) throws IOException {
        long requestedSize = Math.max((long) length * 4L, 1024L);
        int probedSize = (int) Math.min((long) maxDecompressedBytes, requestedSize);
        PooledDecompressContext pooled = borrowDecompressCtx();
        ZstdDecompressCtx ctx = pooled.context;
        try {
            if (dictDecompress != null) {
                ctx.loadDict(dictDecompress);
                pooled.dictionaryLoaded = true;
            } else if (pooled.dictionaryLoaded) {
                ctx.loadDict(EMPTY_DICTIONARY);
                pooled.dictionaryLoaded = false;
            }
            byte[] out = new byte[probedSize];
            long written = ctx.decompressByteArray(out, 0, out.length, body, offset, length);
            while (Zstd.isError(written)) {
                String name = Zstd.getErrorName(written);
                if (name != null && name.contains("dstSize_tooSmall") && out.length < maxDecompressedBytes) {
                    int next = Math.min(maxDecompressedBytes, out.length * 2);
                    if (next == out.length) {
                        throw new IOException("zstd payload exceeds " + maxDecompressedBytes + " bytes");
                    }
                    out = new byte[next];
                    written = ctx.decompressByteArray(out, 0, out.length, body, offset, length);
                    continue;
                }
                throw new IOException("zstd decompression failed: " + name);
            }
            byte[] trimmed = new byte[(int) written];
            System.arraycopy(out, 0, trimmed, 0, trimmed.length);
            return trimmed;
        } finally {
            recycleDecompressCtx(pooled);
        }
    }

    private static byte[] prependMode(byte[] payload, byte mode, int dictVersion, boolean includeVersion) {
        if (!includeVersion) {
            byte[] body = new byte[payload.length + 1];
            body[0] = mode;
            System.arraycopy(payload, 0, body, 1, payload.length);
            return body;
        }
        byte[] body = new byte[payload.length + 5];
        body[0] = mode;
        writeLittleEndianInt(body, 1, dictVersion);
        System.arraycopy(payload, 0, body, 5, payload.length);
        return body;
    }

    private static int readLittleEndianInt(byte[] data, int offset) {
        return (data[offset] & 0xFF)
            | ((data[offset + 1] & 0xFF) << 8)
            | ((data[offset + 2] & 0xFF) << 16)
            | ((data[offset + 3] & 0xFF) << 24);
    }

    private static void writeLittleEndianInt(byte[] data, int offset, int value) {
        data[offset] = (byte) (value & 0xFF);
        data[offset + 1] = (byte) ((value >>> 8) & 0xFF);
        data[offset + 2] = (byte) ((value >>> 16) & 0xFF);
        data[offset + 3] = (byte) ((value >>> 24) & 0xFF);
    }

    private static void writeBigEndianInt(byte[] data, int offset, int value) {
        data[offset] = (byte) ((value >>> 24) & 0xFF);
        data[offset + 1] = (byte) ((value >>> 16) & 0xFF);
        data[offset + 2] = (byte) ((value >>> 8) & 0xFF);
        data[offset + 3] = (byte) (value & 0xFF);
    }

    private PooledCompressContext borrowCompressCtx() {
        synchronized (compressPool) {
            PooledCompressContext pooled = compressPool.pollFirst();
            if (pooled != null) {
                return pooled;
            }
        }
        return new PooledCompressContext(new ZstdCompressCtx());
    }

    private void recycleCompressCtx(PooledCompressContext pooled) {
        synchronized (compressPool) {
            if (compressPool.size() < MAX_POOL_SIZE) {
                compressPool.addFirst(pooled);
                return;
            }
        }
        pooled.context.close();
    }

    private PooledDecompressContext borrowDecompressCtx() {
        synchronized (decompressPool) {
            PooledDecompressContext pooled = decompressPool.pollFirst();
            if (pooled != null) {
                return pooled;
            }
        }
        return new PooledDecompressContext(new ZstdDecompressCtx());
    }

    private void recycleDecompressCtx(PooledDecompressContext pooled) {
        synchronized (decompressPool) {
            if (decompressPool.size() < MAX_POOL_SIZE) {
                decompressPool.addFirst(pooled);
                return;
            }
        }
        pooled.context.close();
    }

    private void clearPools() {
        synchronized (compressPool) {
            for (PooledCompressContext pooled : compressPool) {
                pooled.context.close();
            }
            compressPool.clear();
        }
        synchronized (decompressPool) {
            for (PooledDecompressContext pooled : decompressPool) {
                pooled.context.close();
            }
            decompressPool.clear();
        }
    }

    public record Stats(long rawBytesIn, long wireBytesIn, long rawBytesOut, long wireBytesOut,
                        long noneCount, long dictlessCount, long dictModeCount) {
        public double ratioIn() {
            return rawBytesIn <= 0L ? 0.0D : ((double) wireBytesIn) / ((double) rawBytesIn);
        }

        public double ratioOut() {
            return rawBytesOut <= 0L ? 0.0D : ((double) wireBytesOut) / ((double) rawBytesOut);
        }
    }

    private static final class PooledCompressContext {
        private final ZstdCompressCtx context;
        private boolean dictionaryLoaded;

        private PooledCompressContext(ZstdCompressCtx context) {
            this.context = context;
        }
    }

    private static final class PooledDecompressContext {
        private final ZstdDecompressCtx context;
        private boolean dictionaryLoaded;

        private PooledDecompressContext(ZstdDecompressCtx context) {
            this.context = context;
        }
    }

    public record DecodeResult(byte mode, int dictVersion, byte[] payload) {
    }

    private static final class RetiredDictionary {
        private final int version;
        private final ZstdDictDecompress decompress;

        private RetiredDictionary(int version, ZstdDictDecompress decompress) {
            this.version = version;
            this.decompress = decompress;
        }

        private void release() {
            if (decompress != null) {
                decompress.close();
            }
        }
    }

    private static final class DictionaryState {
        private static final DictionaryState EMPTY = new DictionaryState(null, null, null);

        private final CompressionDictionary dictionary;
        private final ZstdDictCompress compress;
        private final ZstdDictDecompress decompress;

        private DictionaryState(CompressionDictionary dictionary, ZstdDictCompress compress, ZstdDictDecompress decompress) {
            this.dictionary = dictionary;
            this.compress = compress;
            this.decompress = decompress;
        }

        private void release() {
            if (compress != null) {
                compress.close();
            }
            if (decompress != null) {
                decompress.close();
            }
        }
    }
}
