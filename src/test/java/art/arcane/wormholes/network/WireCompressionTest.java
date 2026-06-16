package art.arcane.wormholes.network;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class WireCompressionTest {
    private static byte[] randomBytes(int length, long seed) {
        Random random = new Random(seed);
        byte[] data = new byte[length];
        random.nextBytes(data);
        return data;
    }

    private static byte[] compressibleBytes(int length, long seed) {
        Random random = new Random(seed);
        byte[] data = new byte[length];
        for (int i = 0; i < length; i++) {
            data[i] = (byte) ('A' + random.nextInt(8));
        }
        return data;
    }

    private static int readLittleEndianInt(byte[] data, int offset) {
        return (data[offset] & 0xFF)
            | ((data[offset + 1] & 0xFF) << 8)
            | ((data[offset + 2] & 0xFF) << 16)
            | ((data[offset + 3] & 0xFF) << 24);
    }

    private static CompressionDictionary trainDictionary(int version) {
        List<byte[]> samples = new ArrayList<>();
        Random random = new Random(0xABCDL);
        for (int i = 0; i < 256; i++) {
            byte[] sample = new byte[1024 + random.nextInt(1024)];
            for (int j = 0; j < sample.length; j++) {
                sample[j] = (byte) ('a' + random.nextInt(16));
            }
            samples.add(sample);
        }
        return CompressionDictionary.train(samples, 8 * 1024, version);
    }

    @Test
    void dictlessRoundTripPreservesCompressiblePayload() throws IOException {
        WireCompression compression = new WireCompression(WireCompression.DEFAULT_LEVEL);
        try {
            byte[] payload = compressibleBytes(4096, 1L);
            byte[] encoded = compression.encode(payload, false);
            assertEquals(WireCompression.MODE_ZSTD_DICTLESS, encoded[0]);
            WireCompression.DecodeResult decoded = compression.decode(encoded);
            assertArrayEquals(payload, decoded.payload());
            assertEquals(WireCompression.MODE_ZSTD_DICTLESS, decoded.mode());
            assertEquals(0, decoded.dictVersion());
        } finally {
            compression.close();
        }
    }

    @Test
    void dictlessRoundTripPreservesRandomPayload() throws IOException {
        WireCompression compression = new WireCompression(WireCompression.DEFAULT_LEVEL);
        try {
            byte[] payload = randomBytes(8192, 2L);
            byte[] encoded = compression.encode(payload, false);
            WireCompression.DecodeResult decoded = compression.decode(encoded);
            assertArrayEquals(payload, decoded.payload());
        } finally {
            compression.close();
        }
    }

    @Test
    void emptyPayloadEncodesAsNoneMode() throws IOException {
        WireCompression compression = new WireCompression(WireCompression.DEFAULT_LEVEL);
        try {
            byte[] payload = new byte[0];
            byte[] encoded = compression.encode(payload, false);
            assertEquals(WireCompression.MODE_NONE, encoded[0]);
            WireCompression.DecodeResult decoded = compression.decode(encoded);
            assertArrayEquals(payload, decoded.payload());
        } finally {
            compression.close();
        }
    }

    @Test
    void belowThresholdPayloadIsForcedNoneMode() throws IOException {
        WireCompression compression = new WireCompression(WireCompression.DEFAULT_LEVEL);
        try {
            byte[] payload = compressibleBytes(WireCompression.COMPRESS_THRESHOLD_BYTES - 1, 3L);
            byte[] encoded = compression.encode(payload, true);
            assertEquals(WireCompression.MODE_NONE, encoded[0]);
        } finally {
            compression.close();
        }
    }

    @Test
    void largePayloadRoundTripsThroughDictless() throws IOException {
        WireCompression compression = new WireCompression(WireCompression.DEFAULT_LEVEL);
        try {
            byte[] payload = compressibleBytes(1024 * 1024, 4L);
            byte[] encoded = compression.encode(payload, false);
            WireCompression.DecodeResult decoded = compression.decode(encoded);
            assertArrayEquals(payload, decoded.payload());
        } finally {
            compression.close();
        }
    }

    @Test
    void dictModeEncodesWithVersionPrefix() throws IOException {
        WireCompression compression = new WireCompression(WireCompression.DEFAULT_LEVEL);
        try {
            CompressionDictionary dictionary = trainDictionary(42);
            compression.installDictionary(dictionary);
            byte[] payload = compressibleBytes(4096, 5L);
            byte[] encoded = compression.encode(payload, true);
            assertEquals(WireCompression.MODE_ZSTD_DICT, encoded[0]);
            int version = readLittleEndianInt(encoded, 1);
            assertEquals(42, version);
            WireCompression.DecodeResult decoded = compression.decode(encoded);
            assertArrayEquals(payload, decoded.payload());
            assertEquals(42, decoded.dictVersion());
        } finally {
            compression.close();
        }
    }

    @Test
    void dictNotNegotiatedFallsBackToDictless() throws IOException {
        WireCompression compression = new WireCompression(WireCompression.DEFAULT_LEVEL);
        try {
            CompressionDictionary dictionary = trainDictionary(7);
            compression.installDictionary(dictionary);
            byte[] payload = compressibleBytes(4096, 6L);
            byte[] encoded = compression.encode(payload, false);
            assertEquals(WireCompression.MODE_ZSTD_DICTLESS, encoded[0]);
        } finally {
            compression.close();
        }
    }

    @Test
    void clearDictionaryRevertsToDictless() throws IOException {
        WireCompression compression = new WireCompression(WireCompression.DEFAULT_LEVEL);
        try {
            CompressionDictionary dictionary = trainDictionary(11);
            compression.installDictionary(dictionary);
            assertTrue(compression.hasDictionary());
            compression.clearDictionary();
            assertFalse(compression.hasDictionary());
            byte[] payload = compressibleBytes(4096, 7L);
            byte[] encoded = compression.encode(payload, true);
            assertEquals(WireCompression.MODE_ZSTD_DICTLESS, encoded[0]);
        } finally {
            compression.close();
        }
    }

    @Test
    void unknownModeIsRejected() {
        WireCompression compression = new WireCompression(WireCompression.DEFAULT_LEVEL);
        try {
            byte[] bogus = new byte[]{(byte) 99, 0, 0, 0, 0};
            assertThrows(IOException.class, () -> compression.decode(bogus));
        } finally {
            compression.close();
        }
    }

    @Test
    void missingModeByteIsRejected() {
        WireCompression compression = new WireCompression(WireCompression.DEFAULT_LEVEL);
        try {
            assertThrows(IOException.class, () -> compression.decode(new byte[0]));
        } finally {
            compression.close();
        }
    }

    @Test
    void truncatedDictVersionIsRejected() {
        WireCompression compression = new WireCompression(WireCompression.DEFAULT_LEVEL);
        try {
            byte[] truncated = new byte[]{WireCompression.MODE_ZSTD_DICT, 1, 2};
            assertThrows(IOException.class, () -> compression.decode(truncated));
        } finally {
            compression.close();
        }
    }

    @Test
    void dictDecodeWithoutInstalledDictionaryIsRejected() throws IOException {
        WireCompression encoderCompression = new WireCompression(WireCompression.DEFAULT_LEVEL);
        WireCompression decoderCompression = new WireCompression(WireCompression.DEFAULT_LEVEL);
        try {
            CompressionDictionary dictionary = trainDictionary(13);
            encoderCompression.installDictionary(dictionary);
            byte[] payload = compressibleBytes(4096, 8L);
            byte[] encoded = encoderCompression.encode(payload, true);
            assertEquals(WireCompression.MODE_ZSTD_DICT, encoded[0]);
            assertThrows(IOException.class, () -> decoderCompression.decode(encoded));
        } finally {
            encoderCompression.close();
            decoderCompression.close();
        }
    }

    @Test
    void compressionLevelIsClampedToValidRange() {
        WireCompression below = new WireCompression(-5);
        try {
            assertEquals(WireCompression.MIN_LEVEL, below.compressionLevel());
        } finally {
            below.close();
        }
        WireCompression above = new WireCompression(999);
        try {
            assertEquals(WireCompression.MAX_LEVEL, above.compressionLevel());
        } finally {
            above.close();
        }
    }

    @Test
    void incompressiblePayloadFallsBackToNoneMode() throws IOException {
        WireCompression compression = new WireCompression(WireCompression.DEFAULT_LEVEL);
        try {
            byte[] payload = randomBytes(2048, 9L);
            byte[] encoded = compression.encode(payload, false);
            assertNotNull(encoded);
            WireCompression.DecodeResult decoded = compression.decode(encoded);
            assertArrayEquals(payload, decoded.payload());
        } finally {
            compression.close();
        }
    }

    @Test
    void switchingDictionaryUsesNewVersion() throws IOException {
        WireCompression compression = new WireCompression(WireCompression.DEFAULT_LEVEL);
        try {
            CompressionDictionary first = trainDictionary(100);
            CompressionDictionary second = trainDictionary(101);
            compression.installDictionary(first);
            byte[] payload = compressibleBytes(4096, 10L);
            byte[] firstFrame = compression.encode(payload, true);
            assertEquals(100, readLittleEndianInt(firstFrame, 1));
            compression.installDictionary(second);
            byte[] secondFrame = compression.encode(payload, true);
            assertEquals(101, readLittleEndianInt(secondFrame, 1));
            assertFalse(Arrays.equals(firstFrame, secondFrame));
        } finally {
            compression.close();
        }
    }
}
