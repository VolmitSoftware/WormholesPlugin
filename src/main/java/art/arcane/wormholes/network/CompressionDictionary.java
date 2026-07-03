package art.arcane.wormholes.network;

import com.github.luben.zstd.Zstd;
import com.github.luben.zstd.ZstdCompressCtx;
import com.github.luben.zstd.ZstdDictCompress;
import com.github.luben.zstd.ZstdDictTrainer;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.List;

public final class CompressionDictionary {
    public static final int HASH_LENGTH = 32;
    public static final byte[] ZERO_HASH = new byte[HASH_LENGTH];

    private final byte[] dictBytes;
    private final byte[] hash;
    private final int version;

    private CompressionDictionary(byte[] dictBytes, byte[] hash) {
        this.dictBytes = dictBytes;
        this.hash = hash;
        this.version = deriveVersion(hash);
    }

    public static CompressionDictionary of(byte[] dictBytes) {
        if (dictBytes == null || dictBytes.length == 0) {
            throw new IllegalArgumentException("empty dictionary bytes");
        }
        return new CompressionDictionary(dictBytes.clone(), sha256(dictBytes));
    }

    public static CompressionDictionary train(List<byte[]> samples, int targetSize) {
        if (samples == null || samples.isEmpty()) {
            throw new IllegalStateException("no samples available for dictionary training");
        }
        long totalBytes = 0L;
        for (byte[] sample : samples) {
            if (sample == null) {
                continue;
            }
            totalBytes += sample.length;
        }
        if (totalBytes <= 0L) {
            throw new IllegalStateException("samples are all empty");
        }
        int budget = Math.max(targetSize * 8, (int) Math.min(Integer.MAX_VALUE - targetSize, totalBytes + targetSize));
        ZstdDictTrainer trainer = new ZstdDictTrainer(budget, targetSize);
        for (byte[] sample : samples) {
            if (sample == null || sample.length == 0) {
                continue;
            }
            if (!trainer.addSample(sample)) {
                break;
            }
        }
        byte[] dictBytes = trainer.trainSamples();
        if (dictBytes.length == 0) {
            throw new IllegalStateException("zstd dictionary training produced empty result");
        }
        return new CompressionDictionary(dictBytes, sha256(dictBytes));
    }

    public static CompressionDictionary load(Path file) throws IOException {
        byte[] bytes = Files.readAllBytes(file);
        if (bytes.length == 0) {
            throw new IOException("empty dictionary file: " + file);
        }
        return new CompressionDictionary(bytes, sha256(bytes));
    }

    public static long compressedSizeSum(List<byte[]> samples, byte[] dictBytes, int level) {
        long total = 0L;
        ZstdCompressCtx ctx = new ZstdCompressCtx();
        ZstdDictCompress dict = null;
        try {
            dict = new ZstdDictCompress(dictBytes, level);
            ctx.setLevel(level);
            ctx.loadDict(dict);
            for (byte[] sample : samples) {
                if (sample == null || sample.length == 0) {
                    continue;
                }
                byte[] out = new byte[(int) Zstd.compressBound(sample.length)];
                long written = ctx.compressByteArray(out, 0, out.length, sample, 0, sample.length);
                total += Zstd.isError(written) ? sample.length : written;
            }
        } finally {
            ctx.close();
            if (dict != null) {
                dict.close();
            }
        }
        return total;
    }

    public static boolean sameHash(byte[] left, byte[] right) {
        if (left == null || right == null || left.length != right.length) {
            return false;
        }
        return MessageDigest.isEqual(left, right);
    }

    public byte[] bytes() {
        return dictBytes;
    }

    public byte[] hash() {
        return hash;
    }

    public String hashHex8() {
        return hexShort(hash);
    }

    public int version() {
        return version;
    }

    public Path save(Path dictDirectory) throws IOException {
        Files.createDirectories(dictDirectory);
        Path target = dictDirectory.resolve("wire-" + hashHex8() + ".zdict");
        Path tmp = Files.createTempFile(dictDirectory, "wire-", ".zdict.tmp");
        try {
            Files.write(tmp, dictBytes);
            try {
                Files.move(tmp, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            } catch (UnsupportedOperationException ignored) {
                Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING);
            }
        } finally {
            Files.deleteIfExists(tmp);
        }
        return target;
    }

    private static int deriveVersion(byte[] hash) {
        int version = ((hash[0] & 0x7F) << 24)
            | ((hash[1] & 0xFF) << 16)
            | ((hash[2] & 0xFF) << 8)
            | (hash[3] & 0xFF);
        return version == 0 ? 1 : version;
    }

    private static byte[] sha256(byte[] data) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return digest.digest(data);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }

    private static String hexShort(byte[] hash) {
        StringBuilder builder = new StringBuilder(16);
        int prefix = Math.min(8, hash.length);
        for (int i = 0; i < prefix; i++) {
            String hex = Integer.toHexString(hash[i] & 0xFF);
            if (hex.length() == 1) {
                builder.append('0');
            }
            builder.append(hex);
        }
        return builder.toString();
    }
}
