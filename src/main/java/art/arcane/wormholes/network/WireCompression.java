package art.arcane.wormholes.network;

import com.github.luben.zstd.Zstd;
import com.github.luben.zstd.ZstdCompressCtx;
import com.github.luben.zstd.ZstdDecompressCtx;
import com.github.luben.zstd.ZstdDictCompress;
import com.github.luben.zstd.ZstdDictDecompress;

import java.io.IOException;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.logging.Level;
import java.util.logging.Logger;

public final class WireCompression {
    private static final Logger LOG = Logger.getLogger(WireCompression.class.getName());
    private static final Object ZSTD_PROBE_LOCK = new Object();
    private static volatile Boolean zstdUsable;
    public record Stats(long rawBytesIn, long wireBytesIn, long rawBytesOut, long wireBytesOut,
                        long noneCount, long dictlessCount, long dictModeCount) {
        public double ratioIn() {
            return rawBytesIn <= 0L ? 0.0D : ((double) wireBytesIn) / ((double) rawBytesIn);
        }

        public double ratioOut() {
            return rawBytesOut <= 0L ? 0.0D : ((double) wireBytesOut) / ((double) rawBytesOut);
        }
    }

    public static final byte MODE_NONE = 0;
    public static final byte MODE_ZSTD_DICTLESS = 1;
    public static final byte MODE_ZSTD_DICT = 2;
    public static final int COMPRESS_THRESHOLD_BYTES = 256;
    public static final int MAX_DECOMPRESSED_BYTES = 32 * 1024 * 1024;
    public static final int DEFAULT_LEVEL = 3;
    public static final int MIN_LEVEL = 1;
    public static final int MAX_LEVEL = 22;
    public static final int MAX_POOL_SIZE = 16;

    private final int compressionLevel;
    private final AtomicReference<DictionaryState> dictionaryState = new AtomicReference<>(DictionaryState.EMPTY);
    private final Deque<ZstdCompressCtx> compressPool = new ArrayDeque<>();
    private final Deque<ZstdDecompressCtx> decompressPool = new ArrayDeque<>();
    private final AtomicLong rawBytesIn = new AtomicLong();
    private final AtomicLong wireBytesIn = new AtomicLong();
    private final AtomicLong rawBytesOut = new AtomicLong();
    private final AtomicLong wireBytesOut = new AtomicLong();
    private final AtomicLong noneCount = new AtomicLong();
    private final AtomicLong dictlessCount = new AtomicLong();
    private final AtomicLong dictModeCount = new AtomicLong();

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

    public int compressionLevel() {
        return compressionLevel;
    }

    public void installDictionary(CompressionDictionary dictionary) {
        Objects.requireNonNull(dictionary, "dictionary");
        ZstdDictCompress compress = new ZstdDictCompress(dictionary.bytes(), compressionLevel);
        ZstdDictDecompress decompress = new ZstdDictDecompress(dictionary.bytes());
        DictionaryState previous = dictionaryState.getAndSet(new DictionaryState(dictionary, compress, decompress));
        previous.release();
        clearPools();
    }

    public void clearDictionary() {
        DictionaryState previous = dictionaryState.getAndSet(DictionaryState.EMPTY);
        previous.release();
        clearPools();
    }

    public CompressionDictionary currentDictionary() {
        return dictionaryState.get().dictionary;
    }

    public boolean hasDictionary() {
        return dictionaryState.get().dictionary != null;
    }

    public byte[] encode(byte[] payload, boolean dictNegotiated) throws IOException {
        Objects.requireNonNull(payload, "payload");
        if (payload.length < COMPRESS_THRESHOLD_BYTES || !isZstdUsable()) {
            return frameNone(payload);
        }
        try {
            DictionaryState state = dictionaryState.get();
            boolean useDict = dictNegotiated && state.compress != null;
            ZstdCompressCtx ctx = borrowCompressCtx();
            try {
                ctx.setLevel(compressionLevel);
                if (useDict) {
                    ctx.loadDict(state.compress);
                }
                long bound = Zstd.compressBound(payload.length);
                if (bound > Integer.MAX_VALUE - 5) {
                    return frameNone(payload);
                }
                byte[] compressed = new byte[(int) bound];
                long written = ctx.compressByteArray(compressed, 0, compressed.length, payload, 0, payload.length);
                if (Zstd.isError(written)) {
                    return frameNone(payload);
                }
                int compressedLength = (int) written;
                if (compressedLength >= payload.length) {
                    return frameNone(payload);
                }
                byte[] body = new byte[compressedLength];
                System.arraycopy(compressed, 0, body, 0, compressedLength);
                if (useDict) {
                    byte[] framed = prependMode(body, MODE_ZSTD_DICT, state.dictionary.version(), true);
                    recordOut(payload.length, framed.length, MODE_ZSTD_DICT);
                    return framed;
                }
                byte[] framed = prependMode(body, MODE_ZSTD_DICTLESS, 0, false);
                recordOut(payload.length, framed.length, MODE_ZSTD_DICTLESS);
                return framed;
            } finally {
                recycleCompressCtx(ctx);
            }
        } catch (Throwable ex) {
            markZstdUnusable(ex);
            return frameNone(payload);
        }
    }

    private byte[] frameNone(byte[] payload) {
        byte[] body = prependMode(payload, MODE_NONE, 0, false);
        recordOut(payload.length, body.length, MODE_NONE);
        return body;
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

    private static void markZstdUnusable(Throwable ex) {
        if (zstdUsable != Boolean.FALSE) {
            zstdUsable = Boolean.FALSE;
            LOG.log(Level.WARNING, "zstd-jni compression failed; falling back to uncompressed wire frames (MODE_NONE)", ex);
        }
    }

    public DecodeResult decode(byte[] frameBody) throws IOException {
        Objects.requireNonNull(frameBody, "frameBody");
        if (frameBody.length < 1) {
            throw new IOException("compression mode byte missing");
        }
        byte mode = frameBody[0];
        switch (mode) {
            case MODE_NONE: {
                byte[] payload = new byte[frameBody.length - 1];
                System.arraycopy(frameBody, 1, payload, 0, payload.length);
                recordIn(payload.length, frameBody.length, MODE_NONE);
                return new DecodeResult(mode, 0, payload);
            }
            case MODE_ZSTD_DICTLESS: {
                byte[] payload = decompressDictless(frameBody, 1, frameBody.length - 1);
                recordIn(payload.length, frameBody.length, MODE_ZSTD_DICTLESS);
                return new DecodeResult(mode, 0, payload);
            }
            case MODE_ZSTD_DICT: {
                if (frameBody.length < 5) {
                    throw new IOException("dict-version field truncated");
                }
                int version = readLittleEndianInt(frameBody, 1);
                DictionaryState state = dictionaryState.get();
                if (state.decompress == null || state.dictionary == null || state.dictionary.version() != version) {
                    throw new IOException("missing dictionary version " + version + " for inbound frame");
                }
                byte[] payload = decompressWithDict(frameBody, 5, frameBody.length - 5, state.decompress);
                recordIn(payload.length, frameBody.length, MODE_ZSTD_DICT);
                return new DecodeResult(mode, version, payload);
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

    public void close() {
        clearDictionary();
        clearPools();
    }

    private byte[] decompressDictless(byte[] body, int offset, int length) throws IOException {
        long originalSize = Zstd.getFrameContentSize(body, offset, length);
        if (originalSize < 0L || originalSize > MAX_DECOMPRESSED_BYTES) {
            return streamDecompress(body, offset, length, null);
        }
        ZstdDecompressCtx ctx = borrowDecompressCtx();
        try {
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
            recycleDecompressCtx(ctx);
        }
    }

    private byte[] decompressWithDict(byte[] body, int offset, int length, ZstdDictDecompress dictDecompress) throws IOException {
        long originalSize = Zstd.getFrameContentSize(body, offset, length);
        if (originalSize < 0L || originalSize > MAX_DECOMPRESSED_BYTES) {
            return streamDecompress(body, offset, length, dictDecompress);
        }
        ZstdDecompressCtx ctx = borrowDecompressCtx();
        try {
            ctx.loadDict(dictDecompress);
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
            recycleDecompressCtx(ctx);
        }
    }

    private byte[] streamDecompress(byte[] body, int offset, int length, ZstdDictDecompress dictDecompress) throws IOException {
        int probedSize = Math.min(MAX_DECOMPRESSED_BYTES, Math.max(length * 4, 1024));
        ZstdDecompressCtx ctx = borrowDecompressCtx();
        try {
            if (dictDecompress != null) {
                ctx.loadDict(dictDecompress);
            }
            byte[] out = new byte[probedSize];
            long written = ctx.decompressByteArray(out, 0, out.length, body, offset, length);
            while (Zstd.isError(written)) {
                String name = Zstd.getErrorName(written);
                if (name != null && name.contains("dstSize_tooSmall") && out.length < MAX_DECOMPRESSED_BYTES) {
                    int next = Math.min(MAX_DECOMPRESSED_BYTES, out.length * 2);
                    if (next == out.length) {
                        throw new IOException("zstd payload exceeds " + MAX_DECOMPRESSED_BYTES + " bytes");
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
            recycleDecompressCtx(ctx);
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

    private ZstdCompressCtx borrowCompressCtx() {
        synchronized (compressPool) {
            ZstdCompressCtx ctx = compressPool.pollFirst();
            if (ctx != null) {
                return ctx;
            }
        }
        return new ZstdCompressCtx();
    }

    private void recycleCompressCtx(ZstdCompressCtx ctx) {
        synchronized (compressPool) {
            if (compressPool.size() < MAX_POOL_SIZE) {
                compressPool.addFirst(ctx);
                return;
            }
        }
        ctx.close();
    }

    private ZstdDecompressCtx borrowDecompressCtx() {
        synchronized (decompressPool) {
            ZstdDecompressCtx ctx = decompressPool.pollFirst();
            if (ctx != null) {
                return ctx;
            }
        }
        return new ZstdDecompressCtx();
    }

    private void recycleDecompressCtx(ZstdDecompressCtx ctx) {
        synchronized (decompressPool) {
            if (decompressPool.size() < MAX_POOL_SIZE) {
                decompressPool.addFirst(ctx);
                return;
            }
        }
        ctx.close();
    }

    private void clearPools() {
        synchronized (compressPool) {
            for (ZstdCompressCtx ctx : compressPool) {
                ctx.close();
            }
            compressPool.clear();
        }
        synchronized (decompressPool) {
            for (ZstdDecompressCtx ctx : decompressPool) {
                ctx.close();
            }
            decompressPool.clear();
        }
    }

    public record DecodeResult(byte mode, int dictVersion, byte[] payload) {
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
