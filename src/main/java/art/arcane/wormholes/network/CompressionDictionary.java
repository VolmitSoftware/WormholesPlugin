package art.arcane.wormholes.network;

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

    private CompressionDictionary(byte[] dictBytes, byte[] hash, int version) {
        this.dictBytes = dictBytes;
        this.hash = hash;
        this.version = version;
    }

    public static CompressionDictionary of(byte[] dictBytes, int version) {
        if (dictBytes == null || dictBytes.length == 0) {
            throw new IllegalArgumentException("empty dictionary bytes");
        }
        return new CompressionDictionary(dictBytes.clone(), sha256(dictBytes), version);
    }

    public static CompressionDictionary train(List<byte[]> samples, int targetSize, int version) {
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
        return new CompressionDictionary(dictBytes, sha256(dictBytes), version);
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

    public CompressionDictionary withVersion(int newVersion) {
        return new CompressionDictionary(dictBytes, hash, newVersion);
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

    public static CompressionDictionary load(Path file, int version) throws IOException {
        byte[] bytes = Files.readAllBytes(file);
        if (bytes.length == 0) {
            throw new IOException("empty dictionary file: " + file);
        }
        return new CompressionDictionary(bytes, sha256(bytes), version);
    }

    public static boolean sameHash(byte[] left, byte[] right) {
        if (left == null || right == null || left.length != right.length) {
            return false;
        }
        return MessageDigest.isEqual(left, right);
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
